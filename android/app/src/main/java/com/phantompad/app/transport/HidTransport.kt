package com.phantompad.app.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.phantompad.app.protocol.ButtonId
import com.phantompad.app.protocol.InputSnapshot
import com.phantompad.app.protocol.PhantomProtocol
import com.phantompad.app.util.ConnectionLog
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * Método A: registro HID directo con BluetoothHidDevice (Android 9+).
 *
 * Windows verá un mando HID genérico (DirectInput), NO un DualShock 4 ni un
 * mando Xbox. Varias capas de fabricante (ColorOS incluido) pueden bloquear
 * el registro: si ocurre, la app lo informa y sugiere el método B.
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

    private var hid: BluetoothHidDevice? = null
    private var adapter: BluetoothAdapter? = null
    private var device: BluetoothDevice? = null

    @Volatile
    private var registered = false

    @Volatile
    private var plugged = false

    private var continuation: CancellableContinuation<Boolean>? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { mainHandler.post(it) }

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            val hidProxy = proxy as BluetoothHidDevice
            hid = hidProxy
            val ok = try {
                hidProxy.registerApp(sdpSettings(), null, null, mainExecutor, hidCallback)
            } catch (e: Exception) {
                emit(TransportEvent.Failed("registerApp: ${e.message}"))
                false
            }
            if (!ok) {
                emit(TransportEvent.Failed("El sistema rechazó el registro HID (común en ColorOS)."))
                resumeIfWaiting(false)
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hid = null
                registered = false
                plugged = false
            }
        }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, isRegistered: Boolean) {
            registered = isRegistered
            plugged = pluggedDevice != null
            emit(
                TransportEvent.Info(
                    "HID: registered=$isRegistered plugged=${pluggedDevice != null}"
                )
            )
            if (isRegistered && pluggedDevice == null) {
                // Aún no hay host: pedimos conexión con el PC elegido.
                val target = device
                if (target != null) {
                    try {
                        hid?.connectDevice(target)
                    } catch (e: Exception) {
                        emit(TransportEvent.Failed("connectDevice: ${e.message}"))
                    }
                }
            }
            if (registered && plugged) resumeIfWaiting(true)
        }
    }

    override suspend fun connect(target: BluetoothDevice): Boolean {
        registered = false
        plugged = false
        _state.value = TransportState.CONNECTING
        device = target
        _deviceName.value = target.safeDisplayName()
        val adapterLocal = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        adapter = adapterLocal
        if (adapterLocal == null) {
            fail("Este dispositivo no tiene Bluetooth.")
            return false
        }
        val ready = withTimeoutOrNull(REGISTRATION_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                continuation = cont
                val requested = try {
                    adapterLocal.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE)
                } catch (e: Exception) {
                    emit(TransportEvent.Failed("getProfileProxy: ${e.message}"))
                    false
                }
                if (!requested) {
                    continuation = null
                    cont.resume(false)
                }
            }
        } ?: false
        if (!ready || !registered || !plugged) {
            fail("No se pudo registrar como dispositivo HID en ${REGISTRATION_TIMEOUT_MS / 1000} s. " +
                "ColorOS puede restringir este perfil; usa el modo Receptor Windows.")
            return false
        }
        _state.value = TransportState.CONNECTED
        emit(TransportEvent.Info("HID registrado. Windows debería ver un mando genérico."))
        return true
    }

    override suspend fun sendInput(snapshot: InputSnapshot) {
        val hidProxy = hid ?: throw IOException("HID no registrado")
        val target = device ?: throw IOException("Sin host HID")
        val report = buildReport(snapshot)
        withContext(Dispatchers.IO) {
            if (!hidProxy.sendReport(target, REPORT_ID, report)) {
                throw IOException("sendReport rechazado")
            }
        }
    }

    override suspend fun releaseAll() {
        try {
            sendInput(InputSnapshot())
        } catch (_: Exception) {
            // liberación tolerante a fallos
        }
    }

    override suspend fun close() {
        runCatching { hid?.unregisterApp() }
        runCatching { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid) }
        hid = null
        registered = false
        plugged = false
        _state.value = TransportState.DISCONNECTED
        emit(TransportEvent.Info("HID cerrado."))
    }

    // ------------------------------------------------------------------ interno

    private fun sdpSettings() = BluetoothHidDeviceAppSdpSettings(
        "Phantom Pad",
        "Mando virtual Phantom Pad",
        "Phantom Pad",
        BluetoothHidDevice.SUBCLASS1_GAMEPAD,
        REPORT_DESCRIPTOR
    )

    private fun resumeIfWaiting(value: Boolean) {
        val cont = continuation ?: return
        continuation = null
        if (cont.isActive) cont.resume(value)
    }

    private suspend fun fail(message: String) {
        log.add(message)
        _state.value = TransportState.FAILED
        emit(TransportEvent.Failed(message))
        resumeIfWaiting(false)
        delay(50) // ceder el hilo para que la UI procese el evento
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
        val up = s.buttons and ButtonId.DPAD_UP.bit != 0
        val down = s.buttons and ButtonId.DPAD_DOWN.bit != 0
        val left = s.buttons and ButtonId.DPAD_LEFT.bit != 0
        val right = s.buttons and ButtonId.DPAD_RIGHT.bit != 0
        return when {
            up && right -> 1
            right && down -> 2
            down && left -> 3
            left && up -> 7
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

        /**
         * Descriptor HID de mando: 4 ejes con signo, 2 gatillos sin signo,
         * 16 botones y hat switch de 4 bits. Ver docs/HID_NOTES.md.
         */
        val REPORT_DESCRIPTOR: ByteArray = byteArrayOf(
            0x05, 0x01, // Usage Page (Generic Desktop)
            0x09, 0x05, // Usage (Game Pad)
            0xA1, 0x01, // Collection (Application)
            0x85, 0x01, //   Report ID (1)
            0x09, 0x01, //   Usage (Pointer)
            0xA1, 0x00, //   Collection (Physical)
            0x09, 0x30, //     Usage (X)
            0x09, 0x31, //     Usage (Y)
            0x09, 0x32, //     Usage (Z)
            0x09, 0x35, //     Usage (Rz)
            0x15, 0x81, //     Logical Minimum (-127)
            0x25, 0x7F, //     Logical Maximum (127)
            0x75, 0x08, //     Report Size (8)
            0x95, 0x04, //     Report Count (4)
            0x81, 0x02, //     Input (Data, Var, Abs)
            0xC0,       //   End Collection
            0x09, 0x33, //   Usage (Rx)  -> L2
            0x09, 0x34, //   Usage (Ry)  -> R2
            0x15, 0x00, //   Logical Minimum (0)
            0x26, 0xFF, 0x00, // Logical Maximum (255)
            0x75, 0x08, //   Report Size (8)
            0x95, 0x02, //   Report Count (2)
            0x81, 0x02, //   Input (Data, Var, Abs)
            0x05, 0x09, //   Usage Page (Button)
            0x19, 0x01, //   Usage Minimum (1)
            0x29, 0x10, //   Usage Maximum (16)
            0x15, 0x00, //   Logical Minimum (0)
            0x25, 0x01, //   Logical Maximum (1)
            0x75, 0x01, //   Report Size (1)
            0x95, 0x10, //   Report Count (16)
            0x81, 0x02, //   Input (Data, Var, Abs)
            0x05, 0x01, //   Usage Page (Generic Desktop)
            0x09, 0x39, //   Usage (Hat switch)
            0x15, 0x00, //   Logical Minimum (0)
            0x25, 0x07, //   Logical Maximum (7)
            0x35, 0x00, //   Physical Minimum (0)
            0x46, 0x3B, 0x01, // Physical Maximum (315)
            0x65, 0x14, //   Unit (Degrees)
            0x75, 0x04, //   Report Size (4)
            0x95, 0x01, //   Report Count (1)
            0x81, 0x42, //   Input (Data, Var, Abs, Null State)
            0x75, 0x04, //   Report Size (4)
            0x95, 0x01, //   Report Count (1)
            0x81, 0x03, //   Input (Const, Var, Abs)
            0xC0        // End Collection
        )
    }
}
