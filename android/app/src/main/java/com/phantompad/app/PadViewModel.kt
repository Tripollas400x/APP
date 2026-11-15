package com.phantompad.app

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.phantompad.app.bt.BluetoothManager
import com.phantompad.app.bt.BtDeviceUi
import com.phantompad.app.data.AppSettings
import com.phantompad.app.data.ProfileStore
import com.phantompad.app.data.SettingsStore
import com.phantompad.app.data.TransportModeWire
import com.phantompad.app.gamepad.GamepadState
import com.phantompad.app.gamepad.InputHub
import com.phantompad.app.gamepad.LatencyMeter
import com.phantompad.app.sensors.GyroController
import com.phantompad.app.transport.HidTransport
import com.phantompad.app.transport.RfcommTransport
import com.phantompad.app.transport.Transport
import com.phantompad.app.transport.TransportEvent
import com.phantompad.app.transport.TransportState
import com.phantompad.app.util.BatteryMonitor
import com.phantompad.app.util.ConnectionLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel central: posee el estado del mando, los ajustes, el transporte
 * activo y el bucle de envío. Toda la app gira alrededor de esta clase.
 */
class PadViewModel(app: Application) : AndroidViewModel(app) {

    val log = ConnectionLog()
    val bt = BluetoothManager(app, log)

    private val settingsStore = SettingsStore(app, viewModelScope)
    val settings: StateFlow<AppSettings> = settingsStore.settings

    val profileStore = ProfileStore(app, viewModelScope)

    val state = GamepadState()
    val gyro = GyroController(app)
    val hub = InputHub(viewModelScope, state, settings, gyro)
    val latency = LatencyMeter()
    val battery = BatteryMonitor(app)

    private var transport: Transport? = null
    private var eventJob: Job? = null
    private var connectionJob: Job? = null
    private val connectionLock = Mutex()
    private var requestedMode: TransportModeWire? = null
    private var passiveHidAllowed = true
    private var foreground = false
    private var gamepadVisible = false

    private val _transportState = MutableStateFlow(TransportState.DISCONNECTED)
    val transportState: StateFlow<TransportState> = _transportState

    private val _connectedDevice = MutableStateFlow<BtDeviceUi?>(null)
    val connectedDevice: StateFlow<BtDeviceUi?> = _connectedDevice

    init {
        // Atenuar el giroscopio según ajustes al arrancar.
        viewModelScope.launch { gyro.setEnabled(settings.value.gyroEnabled) }
        viewModelScope.launch {
            var mode = settings.value.transportMode
            settings.collect { current ->
                if (mode != current.transportMode) {
                    mode = current.transportMode
                    passiveHidAllowed = true
                    requestedMode = null
                    replaceConnection {
                        closeTransport()
                        _transportState.value = TransportState.DISCONNECTED
                    }
                    val closing = connectionJob
                    closing?.invokeOnCompletion { viewModelScope.launch { ensureHidMonitor() } }
                } else {
                    ensureHidMonitor()
                }
            }
        }
        viewModelScope.launch {
            bt.enabled.collect { on ->
                if (!on && requestedMode == null && transport is HidTransport) {
                    replaceConnection {
                        closeTransport()
                        _transportState.value = TransportState.DISCONNECTED
                    }
                    connectionJob?.invokeOnCompletion { viewModelScope.launch { ensureHidMonitor() } }
                }
                if (on) ensureHidMonitor()
            }
        }
    }

    // ---------------------------------------------------------------- ajustes

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            settingsStore.update(transform)
            gyro.setEnabled(settings.value.gyroEnabled)
        }
    }

    fun layoutOf() = profileStore.layoutOf(settings.value.activeProfile)

    fun saveLayout(layout: com.phantompad.app.gamepad.PadLayout) {
        viewModelScope.launch { profileStore.save(settings.value.activeProfile, layout) }
    }

    fun resetLayout() {
        viewModelScope.launch {
            profileStore.reset(settings.value.activeProfile)
            log.add("Layout ${settings.value.activeProfile.title} restaurado")
        }
    }

    // -------------------------------------------------------------- conexión

    fun connectTo(device: BtDeviceUi) {
        val mode = settings.value.transportMode
        requestedMode = mode
        replaceConnection {
            closeTransport()
            var hasConnected = false
            val retry = ReconnectBackoff()
            try {
                while (true) {
                    _transportState.value = TransportState.CONNECTING
                    if (bt.hasPermissions() && bt.enabled.value) {
                        val target = bt.getDevice(device.address)
                        if (target != null) {
                            bt.stopScan()
                            if (!bt.isBonded(device.address)) {
                                log.add("Emparejando con ${device.name}.")
                                if (!bt.ensureBonded(target)) break
                                bt.refreshBonded()
                            }
                            val t = createTransport(mode)
                            transport = t
                            observeEvents(t)
                            if (attemptConnect(t, target) && t.state.value == TransportState.CONNECTED) {
                                retry.reset()
                                hasConnected = true
                                _connectedDevice.value = device.copy(bonded = true)
                                _transportState.value = TransportState.CONNECTED
                                state.reset()
                                hub.start(t)
                                updateSettings {
                                    it.copy(lastDeviceAddress = device.address, lastDeviceName = device.name)
                                }
                                t.state.first { it != TransportState.CONNECTED }
                                log.add("Enlace perdido con ${device.name}.")
                            }
                            closeTransport()
                        }
                    } else {
                        log.add("Conexión pendiente: comprueba Bluetooth y los permisos.")
                    }
                    if (!settings.value.autoReconnect || (!hasConnected && retry.attempts >= 4)) break
                    val wait = retry.nextDelayMs()
                    log.add("Reintento con ${device.name} en ${wait / 1000} s.")
                    delay(wait)
                }
                _transportState.value = TransportState.FAILED
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.add("Conexión: ${e.message ?: e.javaClass.simpleName}")
                _transportState.value = TransportState.FAILED
            } finally {
                closeTransport()
            }
        }
    }

    private suspend fun attemptConnect(t: Transport, target: BluetoothDevice): Boolean =
        try {
            t.connect(target)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.add("Fallo al conectar: ${e.message ?: e.javaClass.simpleName}")
            false
        }

    fun quickReconnect() {
        val last = settings.value.lastDeviceAddress ?: return
        connectTo(BtDeviceUi(settings.value.lastDeviceName ?: "PC", last, bt.isBonded(last)))
    }

    fun disconnect() {
        passiveHidAllowed = false
        requestedMode = null
        replaceConnection {
            closeTransport()
            _transportState.value = TransportState.DISCONNECTED
        }
    }

    private fun replaceConnection(block: suspend () -> Unit) {
        connectionJob?.cancel()
        connectionJob = viewModelScope.launch {
            connectionLock.withLock { block() }
        }
    }

    private fun createTransport(mode: TransportModeWire): Transport =
        if (mode == TransportModeWire.HID) HidTransport(getApplication(), log)
        else RfcommTransport(viewModelScope, bt, log, latency)

    private suspend fun closeTransport() = withContext(NonCancellable) {
        val old = transport
        transport = null
        eventJob?.cancel()
        eventJob = null
        state.reset()
        hub.stopAndJoin()
        if (old?.state?.value == TransportState.CONNECTED) {
            // Si el enlace ya falló, Windows libera el mando al cerrarse el socket.
            withTimeoutOrNull(350) { runCatching { old.releaseAll() } }
        }
        old?.close()
        _connectedDevice.value = null
        latency.reset()
    }

    fun notifyPermissionsGranted() {
        bt.refreshBonded()
        ensureHidMonitor()
    }

    private fun ensureHidMonitor() {
        if (connectionJob?.isActive == true || transport != null || !foreground || !passiveHidAllowed) return
        if (settings.value.transportMode != TransportModeWire.HID || !bt.hasPermissions() || !bt.enabled.value) return
        requestedMode = null
        replaceConnection {
            val hid = HidTransport(getApplication(), log)
            transport = hid
            observeEvents(hid)
            try {
                hid.prepare()
                hid.state.collect { current ->
                    _transportState.value = current
                    state.reset()
                    if (current == TransportState.CONNECTED) {
                        val address = hid.connectedAddress.orEmpty()
                        _connectedDevice.value = BtDeviceUi(hid.deviceName.value ?: "PC", address, true)
                        if (!hub.isRunning) hub.start(hid)
                    } else {
                        hub.stopAndJoin()
                        _connectedDevice.value = null
                    }
                }
            } finally {
                closeTransport()
            }
        }
    }

    private fun observeEvents(t: Transport) {
        eventJob?.cancel()
        eventJob = viewModelScope.launch {
            t.events.collect { event ->
                if (transport !== t) return@collect
                when (event) {
                    is TransportEvent.Rumble -> vibrate(event.small, event.large)
                    TransportEvent.ReleaseInputs -> state.reset()
                    is TransportEvent.Info -> log.add(event.message)
                    is TransportEvent.Failed -> log.add("FALLO: ${event.message}")
                }
            }
        }
    }

    fun pauseGamepad() {
        gamepadVisible = false
        updateHubControls()
    }

    fun resumeGamepad() {
        gamepadVisible = true
        updateHubControls()
    }

    fun setForeground(active: Boolean) {
        foreground = active
        updateHubControls()
        if (active && requestedMode == null && transport is HidTransport &&
            transport?.state?.value == TransportState.FAILED
        ) {
            replaceConnection {
                closeTransport()
                _transportState.value = TransportState.DISCONNECTED
            }
            connectionJob?.invokeOnCompletion { viewModelScope.launch { ensureHidMonitor() } }
        } else if (active) {
            ensureHidMonitor()
        }
    }

    private fun updateHubControls() {
        hub.controlsActive = gamepadVisible && foreground
        if (!hub.controlsActive) state.reset()
    }

    private fun vibrate(small: Int, large: Int) {
        if (!settings.value.vibrationEnabled) return
        val strength = maxOf(small, large).coerceIn(0, 255)
        if (strength == 0) return
        val app = getApplication<Application>()
        val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            app.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(40)
            }
        }
    }

    override fun onCleared() {
        hub.stop()
        gyro.setEnabled(false)
        battery.dispose()
        bt.dispose()
        transport?.close()
        transport = null
        super.onCleared()
    }
}
