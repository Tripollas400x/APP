package com.phantompad.app

import android.app.Application
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
import kotlinx.coroutines.Job
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
    private var reconnectJob: Job? = null
    private var connectingJob: Job? = null

    private val _transportState = MutableStateFlow(TransportState.DISCONNECTED)
    val transportState: StateFlow<TransportState> = _transportState

    private val _connectedDevice = MutableStateFlow<BtDeviceUi?>(null)
    val connectedDevice: StateFlow<BtDeviceUi?> = _connectedDevice

    init {
        // Atenuar el giroscopio según ajustes al arrancar.
        viewModelScope.launch { gyro.setEnabled(settings.value.gyroEnabled) }
        // Modo HID: registrar el servicio al abrir la app para detectar en vivo
        // la conexión del PC (sin pulsar nada).
        ensureHidMonitor()
        viewModelScope.launch {
            settings.collect { ensureHidMonitor() }
        }
        viewModelScope.launch {
            bt.enabled.collect { on ->
                if (on) {
                    ensureHidMonitor()
                } else {
                    // Bluetooth apagado: la sesión HID murió a nivel sistema.
                    if (transport is HidTransport) disconnectInternal()
                }
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
        // Cancela cualquier intento anterior para no dejar dos bucles conectando.
        connectingJob?.cancel()
        connectingJob = viewModelScope.launch {
            val target = bt.getDevice(device.address)
            if (target == null) {
                log.add("No se pudo resolver el dispositivo ${device.address}")
                return@launch
            }
            // Un discovery activo sabotea la conexión RFCOMM (SDP y canal):
            // se cancela SIEMPRE antes de crear el transporte.
            bt.stopScan()
            if (!bt.isBonded(device.address)) {
                log.add("Iniciando emparejamiento con ${device.name}...")
                val bonded = bt.ensureBonded(target)
                if (!bonded) {
                    log.add("Emparejamiento no completado.")
                    _transportState.value = TransportState.FAILED
                    return@launch
                }
                bt.refreshBonded()
            }
            _connectedDevice.value = device.copy(bonded = true)
            updateSettings { it.copy(lastDeviceAddress = device.address, lastDeviceName = device.name) }
            startTransport(target, device, retries = if (settings.value.autoReconnect) 4 else 0)
        }
    }

    fun quickReconnect() {
        val last = settings.value.lastDeviceAddress ?: return
        val name = settings.value.lastDeviceName ?: "PC"
        connectTo(BtDeviceUi(name, last, bt.isBonded(last)))
    }

    fun disconnect() {
        viewModelScope.launch { disconnectInternal() }
    }

    private suspend fun disconnectInternal() {
        reconnectJob?.cancel()
        reconnectJob = null
        hub.stop()
        eventJob?.cancel()
        eventJob = null
        transport?.close()
        transport = null
        _transportState.value = TransportState.DISCONNECTED
        _connectedDevice.value = null
        state.reset()
    }

    /** La UI avisa que ya hay permisos: reactiva la supervisión HID automática. */
    fun notifyPermissionsGranted() = ensureHidMonitor()

    /**
     * Modo HID: crea el transporte y registra el servicio HID aunque el
     * usuario no haya pulsado Conectar. Así, si el PC se conecta desde
     * ajustes Bluetooth o desde Windows, el estado se actualiza solo.
     */
    private fun ensureHidMonitor() {
        if (transport != null) return
        if (settings.value.transportMode != TransportModeWire.HID) return
        if (bt.adapter?.isEnabled != true || !bt.hasPermissions()) return
        val t = HidTransport(getApplication(), log)
        transport = t
        observeTransport(t)
        _transportState.value = TransportState.DISCONNECTED
        viewModelScope.launch { t.prepare() }
        log.add("HID: supervisión automática activa; detecta al PC sin pulsar nada.")
    }

    private suspend fun startTransport(target: android.bluetooth.BluetoothDevice, ui: BtDeviceUi, retries: Int) {
        val mode = settings.value.transportMode
        val current = transport
        val t: Transport = if (
            mode == TransportModeWire.HID &&
            current is HidTransport &&
            current.state.value != TransportState.FAILED &&
            (current.connectedAddress == null || current.connectedAddress == target.address)
        ) {
            // Reutiliza el servicio HID ya registrado (monitor): evita
            // des-registrar y volver a registrar en cada intento.
            log.add("Reutilizando el servicio HID ya registrado.")
            current
        } else {
            disconnectInternal()
            if (mode == TransportModeWire.HID) {
                HidTransport(getApplication(), log)
            } else {
                RfcommTransport(viewModelScope, bt, log, latency)
            }
        }
        transport = t
        observeTransport(t)
        _transportState.value = TransportState.CONNECTING
        var attempt = 0
        var waitMs = 1000L
        while (true) {
            latency.reset()
            state.reset()
            val ok = t.connect(target)
            if (ok) {
                _transportState.value = TransportState.CONNECTED
                state.reset()
                if (!hub.isRunning) hub.start(t)
                log.add("Modo mando activo (${mode}).")
                return
            }
            attempt++
            if (attempt > retries) {
                _transportState.value = TransportState.FAILED
                return
            }
            log.add("Reintento $attempt de $retries en ${waitMs / 1000} s...")
            delay(waitMs)
            waitMs = (waitMs * 2).coerceAtMost(8000L)
        }
    }

    private fun observeTransport(t: Transport) {
        eventJob?.cancel()
        eventJob = viewModelScope.launch {
            launch {
                t.state.collect { s ->
                    _transportState.value = s
                    when (s) {
                        TransportState.CONNECTED -> {
                            // La conexión puede llegar sola (HID): arrancar el envío.
                            if (!hub.isRunning) {
                                state.reset()
                                hub.start(t)
                            }
                        }
                        TransportState.DISCONNECTED, TransportState.FAILED -> {
                            if (hub.isRunning) {
                                hub.stop()
                                state.reset()
                            }
                            _connectedDevice.value = null
                        }
                        TransportState.CONNECTING -> Unit
                    }
                }
            }
            launch {
                t.deviceName.collect { name ->
                    if (t.state.value == TransportState.CONNECTED && name != null) {
                        _connectedDevice.value = BtDeviceUi(
                            name,
                            settings.value.lastDeviceAddress ?: "",
                            true
                        )
                    }
                }
            }
            launch {
                t.events.collect { event ->
                    when (event) {
                        is TransportEvent.Rumble -> vibrate(event.small, event.large)
                        is TransportEvent.RttMeasured -> Unit // el RTT ya vive en vm.latency.stats
                        is TransportEvent.Info -> log.add(event.message)
                        is TransportEvent.Failed -> {
                            log.add("FALLO: ${event.message}")
                            if (t.state.value != TransportState.CONNECTED) {
                                _transportState.value = TransportState.FAILED
                                hub.stop()
                            }
                        }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------- ciclo de vida

    /** Pausa del modo mando: libera el estado para no dejar botones pegados. */
    fun pauseGamepad() {
        state.reset()
        viewModelScope.launch {
            transport?.releaseAll()
        }
        hub.stop()
    }

    fun resumeGamepad() {
        val t = transport ?: return
        if (t.state.value == TransportState.CONNECTED && !hub.isRunning) {
            hub.start(t)
        }
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
        viewModelScope.launch {
            transport?.close()
            transport = null
        }
        super.onCleared()
    }
}
