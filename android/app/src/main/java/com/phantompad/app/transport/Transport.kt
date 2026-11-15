package com.phantompad.app.transport

import android.bluetooth.BluetoothDevice
import com.phantompad.app.protocol.InputSnapshot
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class TransportState { DISCONNECTED, CONNECTING, CONNECTED, FAILED }

/** Método de conexión elegido por el usuario. */
enum class TransportMode(val label: String, val hint: String) {
    RECEIVER(
        "Receptor Windows (recomendado)",
        "App PhantomPadReceiver.exe en el PC. Manda virtual Xbox 360 (XInput) o DualShock 4 via ViGEmBus."
    ),
    HID(
        "HID directo (experimental)",
        "Sin receptor. Windows ve un mando HID generico (DirectInput). Depende del soporte del fabricante."
    )
}

sealed class TransportEvent {
    data class RttMeasured(val ms: Double) : TransportEvent()
    data class Rumble(val small: Int, val large: Int) : TransportEvent()
    data class Info(val message: String) : TransportEvent()
    data class Failed(val message: String) : TransportEvent()
}

/**
 * Canal de envio del estado de entrada. Implementaciones: RfcommTransport
 * (metodo B) y HidTransport (metodo A).
 */
interface Transport {
    val state: StateFlow<TransportState>
    val events: SharedFlow<TransportEvent>
    val deviceName: StateFlow<String?>

    /** Intenta conectar. Devuelve true cuando el estado es CONNECTED. */
    suspend fun connect(device: BluetoothDevice): Boolean

    /** Envia el estado actual (llamado a la frecuencia de envio configurada). */
    suspend fun sendInput(snapshot: InputSnapshot)

    /** Libera todos los botones y ejes en el otro extremo (watchdog). */
    suspend fun releaseAll()

    /** Notifica un fallo de envío detectado por el hilo de entrada. */
    suspend fun onSendFailure(cause: Exception)

    suspend fun close()
}

/** Base con utilidades comunes de estado y eventos para los transportes. */
abstract class BaseTransport : Transport {
    protected val _state = MutableStateFlow(TransportState.DISCONNECTED)
    override val state: StateFlow<TransportState> = _state

    protected val _events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<TransportEvent> = _events

    protected val _deviceName = MutableStateFlow<String?>(null)
    override val deviceName: StateFlow<String?> = _deviceName

    protected fun emit(event: TransportEvent) {
        _events.tryEmit(event)
    }

    override suspend fun onSendFailure(cause: Exception) {
        _state.value = TransportState.FAILED
        emit(TransportEvent.Failed(cause.message ?: cause.javaClass.simpleName))
    }
}
