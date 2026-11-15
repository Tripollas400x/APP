package com.phantompad.app.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.phantompad.app.protocol.ButtonId
import com.phantompad.app.protocol.InputSnapshot
import com.phantompad.app.protocol.PhantomProtocol
import com.phantompad.app.util.ConnectionLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.Executor

/**
 * Método A: registro HID directo con BluetoothHidDevice (Android 9+).
 *
 * Windows verá un mando HID genérico (DirectInput), NO un DualShock 4 ni un
 * mando Xbox. Varias capas de fabricante (ColorOS incluido) pueden bloquear
 * el registro: si ocurre, la app lo informa y sugiere el método B.
 *
 * Estados en vivo: la app registra el servicio HID al abrir (prepare) y
 * reacciona a onConnectionStateChanged / onAppStatusChanged, de modo que si
 * el PC se conecta desde ajustes Bluetooth o desde Windows, la app lo
 * muestra sin pulsar nada. El botón Conectar también detecta el caso
 * "ya conectado a nivel de sistema" y no repite el registro.
 *
 * Reporte (9 bytes de datos + id 0x01):
 *   lx, ly, rz (stick derecho X), rz/ry (stick derecho Y), l2, r2,
 *   botones_lo, botones_hi, hat(|padding).
 */
@SuppressLint("MissingPermission") // los permisos se verifican en la UI antes de conectar
class HidTransport(
    private val context: Context,
    private val log: ConnectionLog
) : BaseTransport() {

    @Volatile private var hid: BluetoothHidDevice? = null
    @Volatile private var device: BluetoothDevice? = null
    private var adapter: BluetoothAdapter? = null
    private var expectedAddress: String? = null
    private var generation = 0
    private var registered = false
    private var registration: CompletableDeferred<Boolean>? = null
    private var connection: CompletableDeferred<Boolean>? = null
    private val operations = Mutex()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { mainHandler.post(it) }

    val connectedAddress: String? get() = device?.address

    suspend fun prepare() = operations.withLock {
        if (ensureRegistered()) {
            discoverConnectedHost()
        } else {
            emit(TransportEvent.Info("Perfil HID no disponible ahora. Consulta el diagnóstico."))
        }
    }

    override suspend fun connect(device: BluetoothDevice): Boolean = operations.withLock {
        expectedAddress = device.address
        val previous = this.device
        if (previous != null && previous.address != device.address) {
            runCatching { hid?.disconnect(previous) }
            this.device = null
        }
        _state.value = TransportState.CONNECTING

        if (!ensureRegistered()) return@withLock fail(
            "Android no confirmó el registro HID. ColorOS puede limitar el perfil o exigir la app en primer plano."
        )
        discoverConnectedHost()
        if (_state.value == TransportState.CONNECTED && this.device?.address == device.address) {
            return@withLock true
        }

        val confirmation = CompletableDeferred<Boolean>()
        connection = confirmation
        try {
            val requested = runCatching { hid?.connect(device) == true }.getOrElse {
                return@withLock fail("Conexión HID: ${it.message ?: it.javaClass.simpleName}")
            }
            if (!requested) return@withLock fail(
                "Android rechazó la conexión HID. Intenta conectar desde Windows o usa Receptor Windows."
            )
            val connected = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { confirmation.await() }
            if (connected == true && _state.value == TransportState.CONNECTED) return@withLock true
            runCatching { hid?.disconnect(device) }
            fail("Windows no confirmó la conexión HID con ${device.safeDisplayName()}.")
        } finally {
            connection = null
        }
    }

    override suspend fun sendInput(snapshot: InputSnapshot) {
        val target = device ?: throw IOException("Sin host HID")
        val proxy = hid ?: throw IOException("Perfil HID no disponible")
        withContext(Dispatchers.IO) {
            if (!proxy.sendReport(target, REPORT_ID, buildReport(snapshot))) {
                throw IOException("Android rechazó el reporte HID")
            }
        }
    }

    override suspend fun releaseAll() = sendInput(InputSnapshot())

    override fun close() {
        releaseProfile()
        expectedAddress = null
        _state.value = TransportState.DISCONNECTED
    }

    private fun releaseProfile() {
        generation++
        val proxy = hid
        val target = device
        hid = null
        device = null
        registered = false
        registration?.complete(false)
        connection?.complete(false)
        if (proxy != null) {
            if (target != null) runCatching { proxy.disconnect(target) }
            runCatching { proxy.unregisterApp() }
            runCatching { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, proxy) }
        }
        _deviceName.value = null
    }

    private suspend fun ensureRegistered(): Boolean {
        if (hid != null && registered) return true
        releaseProfile()
        val radio = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return false
        adapter = radio
        val id = generation
        val ready = CompletableDeferred<Boolean>()
        registration = ready
        var accepted = false
        try {
            val requested = runCatching {
                radio.getProfileProxy(context, serviceListener(radio, id), BluetoothProfile.HID_DEVICE)
            }.getOrElse {
                log.add("HID getProfileProxy: ${it.message ?: it.javaClass.simpleName}")
                false
            }
            if (!requested) return false
            accepted = withTimeoutOrNull(REGISTRATION_TIMEOUT_MS) { ready.await() } == true && registered
            return accepted
        } finally {
            registration = null
            if (!accepted && generation == id) releaseProfile()
        }
    }

    private fun serviceListener(radio: BluetoothAdapter, id: Int) = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            mainHandler.post {
                if (id != generation) {
                    runCatching { radio.closeProfileProxy(profile, proxy) }
                    return@post
                }
                val hidProxy = proxy as BluetoothHidDevice
                hid = hidProxy
                val sdp = BluetoothHidDeviceAppSdpSettings(
                    "Phantom Pad", "Mando virtual Phantom Pad", "Phantom Pad",
                    BluetoothHidDevice.SUBCLASS2_GAMEPAD, REPORT_DESCRIPTOR
                )
                val requested = runCatching { hidProxy.registerApp(sdp, null, null, mainExecutor, callback(id)) }
                    .getOrElse {
                        log.add("HID registerApp: ${it.message ?: it.javaClass.simpleName}")
                        false
                    }
                if (!requested) {
                    log.add("HID: Android rechazó registerApp().")
                    registration?.complete(false)
                }
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            mainHandler.post {
                if (profile != BluetoothProfile.HID_DEVICE || id != generation) return@post
                releaseProfile()
                fail("El servicio HID se desconectó.")
            }
        }
    }

    private fun callback(id: Int) = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, isRegistered: Boolean) {
            if (id != generation) return
            registered = isRegistered
            registration?.complete(isRegistered)
            if (isRegistered) {
                log.add("HID: registro confirmado por Android.")
                // pluggedDevice puede ser el cable virtual recordado y no un canal activo.
                discoverConnectedHost()
            } else {
                releaseProfile()
                fail("Android retiró el registro HID. Mantén Phantom Pad en primer plano.")
            }
        }

        override fun onConnectionStateChanged(host: BluetoothDevice, state: Int) {
            if (id != generation || !registered || !matchesHost(host)) return
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> markConnected(host)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (device?.address == host.address) {
                        device = null
                        _deviceName.value = null
                        _state.value = TransportState.DISCONNECTED
                        emit(TransportEvent.Info("HID desconectado de ${host.safeDisplayName()}."))
                    }
                    if (expectedAddress == host.address) connection?.complete(false)
                }
            }
        }
    }

    private fun matchesHost(host: BluetoothDevice): Boolean {
        val address = expectedAddress ?: device?.address
        return address == null || address == host.address
    }

    private fun discoverConnectedHost() {
        val connected = runCatching { hid?.connectedDevices.orEmpty() }.getOrDefault(emptyList())
        connected.firstOrNull(::matchesHost)?.let(::markConnected)
    }

    private fun markConnected(host: BluetoothDevice) {
        if (!registered || !matchesHost(host)) return
        device = host
        _deviceName.value = host.safeDisplayName()
        _state.value = TransportState.CONNECTED
        connection?.complete(true)
    }

    private fun fail(message: String): Boolean {
        log.add(message)
        _state.value = TransportState.FAILED
        emit(TransportEvent.Failed(message))
        return false
    }

    private fun buildReport(s: InputSnapshot): ByteArray {
        val data = ByteArray(REPORT_DATA_LEN)
        data[0] = PhantomProtocol.axisByte(s.lx)
        data[1] = PhantomProtocol.axisByte(s.ly)
        data[2] = PhantomProtocol.axisByte(s.rx)
        data[3] = PhantomProtocol.axisByte(s.ry)
        data[4] = PhantomProtocol.triggerByte(s.l2)
        data[5] = PhantomProtocol.triggerByte(s.r2)
        data[6] = (s.buttons and 0xFF).toByte()
        data[7] = ((s.buttons shr 8) and 0xFF).toByte()
        data[8] = hatByte(s).toByte()
        return data
    }

    private fun hatByte(s: InputSnapshot): Int {
        // Hat switch estándar HID: 0=N, 1=NE, 2=E, 3=SE, 4=S, 5=SO, 6=O, 7=NO.
        // El neutral (sin dirección) usa el Null State (0x0F) del descriptor.
        val up = s.buttons and ButtonId.DPAD_UP.bit != 0
        val down = s.buttons and ButtonId.DPAD_DOWN.bit != 0
        val left = s.buttons and ButtonId.DPAD_LEFT.bit != 0
        val right = s.buttons and ButtonId.DPAD_RIGHT.bit != 0
        return when {
            up && right -> 1  // Noreste
            right && down -> 3 // Sureste
            down && left -> 5  // Suroeste
            left && up -> 7    // Noroeste
            up -> 0
            right -> 2
            down -> 4
            left -> 6
            else -> 0x0F
        }
    }

    companion object {
        private const val REPORT_ID = 0x01
        private const val REPORT_DATA_LEN = 9
        private const val REGISTRATION_TIMEOUT_MS = 12_000L
        private const val CONNECT_TIMEOUT_MS = 12_000L

        /**
         * Descriptor HID de mando: 4 ejes con signo, 2 gatillos sin signo,
         * 16 botones y hat switch de 4 bits. Ver docs/HID_NOTES.md.
         */
        val REPORT_DESCRIPTOR: ByteArray = byteArrayOf(
            0x05, 0x01, // Usage Page (Generic Desktop)
            0x09, 0x05, // Usage (Game Pad)
            0xA1.toByte(), 0x01, // Collection (Application)
            0x85.toByte(), 0x01, //   Report ID (1)
            0x09, 0x01, //   Usage (Pointer)
            0xA1.toByte(), 0x00, //   Collection (Physical)
            0x09, 0x30, //     Usage (X)
            0x09, 0x31, //     Usage (Y)
            0x09, 0x32, //     Usage (Z)
            0x09, 0x35, //     Usage (Rz)
            0x15, 0x81.toByte(), //     Logical Minimum (-127)
            0x25, 0x7F, //     Logical Maximum (127)
            0x75, 0x08, //     Report Size (8)
            0x95.toByte(), 0x04, //     Report Count (4)
            0x81.toByte(), 0x02, //     Input (Data, Var, Abs)
            0xC0.toByte(),       //   End Collection
            0x09, 0x33, //   Usage (Rx)  -> L2
            0x09, 0x34, //   Usage (Ry)  -> R2
            0x15, 0x00, //   Logical Minimum (0)
            0x26, 0xFF.toByte(), 0x00, // Logical Maximum (255)
            0x75, 0x08, //     Report Size (8)
            0x95.toByte(), 0x02, //     Report Count (2)
            0x81.toByte(), 0x02, //     Input (Data, Var, Abs)
            0x05, 0x09, //   Usage Page (Button)
            0x19, 0x01, //   Usage Minimum (1)
            0x29, 0x10, //   Usage Maximum (16)
            0x15, 0x00, //   Logical Minimum (0)
            0x25, 0x01, //   Logical Maximum (1)
            0x75, 0x01, //   Report Size (1)
            0x95.toByte(), 0x10, //   Report Count (16)
            0x81.toByte(), 0x02, //   Input (Data, Var, Abs)
            0x05, 0x01, //   Usage Page (Generic Desktop)
            0x09, 0x39, //   Usage (Hat switch)
            0x15, 0x00, //   Logical Minimum (0)
            0x25, 0x07, //   Logical Maximum (7)
            0x35, 0x00, //   Physical Minimum (0)
            0x46, 0x3B, 0x01, // Physical Maximum (315)
            0x65, 0x14, //   Unit (Degrees)
            0x75, 0x04, //   Report Size (4)
            0x95.toByte(), 0x01, //   Report Count (1)
            0x81.toByte(), 0x42, //   Input (Data, Var, Abs, Null State)
            0x65, 0x00, //   Restablecer unidad antes del padding
            0x75, 0x04, //   Report Size (4)
            0x95.toByte(), 0x01, //   Report Count (1)
            0x81.toByte(), 0x03, //   Input (Const, Var, Abs)
            0xC0.toByte()        // End Collection
        )
    }
}
