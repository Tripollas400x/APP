package com.phantompad.app.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import com.phantompad.app.gamepad.LatencyMeter
import com.phantompad.app.protocol.InputSnapshot
import com.phantompad.app.protocol.PhantomProtocol
import com.phantompad.app.util.ConnectionLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Método B: cliente RFCOMM/SPP hacia PhantomPadReceiver.exe.
 * Un único escritor (mutex) evita interleaving de frames; un hilo lector
 * procesa PONG (latencia) y RUMBLE (vibración).
 */
@SuppressLint("MissingPermission") // los permisos se verifican en la UI antes de conectar
class RfcommTransport(
    private val scope: CoroutineScope,
    private val log: ConnectionLog,
    private val latency: LatencyMeter
) : BaseTransport() {

    private var socket: BluetoothSocket? = null
    private var out: DataOutputStream? = null
    private var readerThread: Thread? = null
    private var pingJob: Job? = null
    private val writeMutex = Mutex()
    private val pendingPings = ConcurrentHashMap<Int, Long>()

    private var sendSeq = 0
    private var pingSeq = 0

    @Volatile
    private var closing = false

    override suspend fun connect(device: BluetoothDevice): Boolean {
        closing = false
        _state.value = TransportState.CONNECTING
        emit(TransportEvent.Info("Conectando con ${device.safeDisplayName()}..."))
        return try {
            withContext(Dispatchers.IO) {
                // El receptor Windows publica el canal con PlainSocket (sin
                // cifrado). El canal seguro de Android puede ser rechazado por
                // eso: probamos seguro y, si falla, sin cifrado.
                var connected: BluetoothSocket? = null
                var lastError: IOException? = null
                for ((label, make) in listOf(
                    "seguro" to { device.createRfcommSocketToServiceRecord(SPP_UUID) },
                    "sin cifrado" to { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) }
                )) {
                    try {
                        log.add("RFCOMM: abriendo canal $label...")
                        val s = make()
                        socket = s
                        s.connect()
                        connected = s
                        log.add("RFCOMM: canal $label establecido.")
                        break
                    } catch (e: IOException) {
                        lastError = e
                        runCatching { socket?.close() }
                        socket = null
                        delay(300) // dejar que la pila BT enfríe entre intentos
                    }
                }
                val s = connected ?: throw lastError ?: IOException("No se pudo abrir el canal RFCOMM")
                out = DataOutputStream(BufferedOutputStream(s.outputStream))
                _deviceName.value = device.safeDisplayName()
                startReader(s)
            }
            _state.value = TransportState.CONNECTED
            emit(TransportEvent.Info("Conectado por RFCOMM/SPP."))
            log.add("RFCOMM conectado con ${device.safeDisplayName()}")
            startPingLoop()
            true
        } catch (e: SecurityException) {
            fail("Permiso Bluetooth denegado: ${e.message}")
            false
        } catch (e: IOException) {
            fail("No se pudo abrir el canal RFCOMM: ${e.message ?: "fallo de conexión"}")
            false
        } catch (e: Exception) {
            fail("Error de conexión: ${e.message}")
            false
        }
    }

    override suspend fun sendInput(snapshot: InputSnapshot) {
        val stream = out ?: throw IOException("Sin stream de salida (no conectado)")
        val frame = PhantomProtocol.encodeInput(snapshot, sendSeq++ and 0xFFFF)
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                stream.write(frame)
                stream.flush()
            }
        }
    }

    override suspend fun releaseAll() {
        safeWrite(PhantomProtocol.encodeReleaseAll())
    }

    override suspend fun close() {
        closing = true
        pingJob?.cancel()
        pingJob = null
        safeWrite(PhantomProtocol.encodeReleaseAll())
        runCatching { socket?.close() }
        withContext(Dispatchers.IO) {
            readerThread?.join(600)
        }
        readerThread = null
        socket = null
        out = null
        _state.value = TransportState.DISCONNECTED
        emit(TransportEvent.Info("Desconectado."))
    }

    // ------------------------------------------------------------------ interno

    private suspend fun safeWrite(frame: ByteArray) {
        val stream = out ?: return
        try {
            writeMutex.withLock {
                withContext(Dispatchers.IO) {
                    stream.write(frame)
                    stream.flush()
                }
            }
        } catch (_: Exception) {
            // al cerrar, fallar la escritura es normal
        }
    }

    private fun startReader(socket: BluetoothSocket) {
        readerThread = Thread {
            try {
                val input = DataInputStream(BufferedInputStream(socket.inputStream))
                val header = ByteArray(LEN_HEADER)
                val body = ByteArray(PhantomProtocol.LEN_INPUT)
                while (!closing && _state.value != TransportState.FAILED) {
                    input.readFully(header)
                    if (header[0] != PhantomProtocol.MAGIC0.toByte() ||
                        header[1] != PhantomProtocol.MAGIC1.toByte()
                    ) continue
                    val type = header[3].toInt() and 0xFF
                    when (type) {
                        PhantomProtocol.TYPE_PONG -> {
                            input.readFully(body, 0, PhantomProtocol.LEN_PING - LEN_HEADER)
                            val frame = header.copyOf() + body.copyOf(PhantomProtocol.LEN_PING - LEN_HEADER)
                            if (PhantomProtocol.frameType(frame) == PhantomProtocol.TYPE_PONG) {
                                val seq = PhantomProtocol.parseSeq(frame)
                                pendingPings.remove(seq)
                                latency.onPongReceived(seq)
                            }
                        }
                        PhantomProtocol.TYPE_RUMBLE -> {
                            input.readFully(body, 0, PhantomProtocol.LEN_RUMBLE - LEN_HEADER)
                            val frame = header.copyOf() + body.copyOf(PhantomProtocol.LEN_RUMBLE - LEN_HEADER)
                            if (PhantomProtocol.frameType(frame) == PhantomProtocol.TYPE_RUMBLE) {
                                val (small, large) = PhantomProtocol.parseRumble(frame)
                                emit(TransportEvent.Rumble(small, large))
                            }
                        }
                        PhantomProtocol.TYPE_RELEASE_ALL -> {
                            // El PC nos pide liberar: solo registramos el evento.
                            emit(TransportEvent.Info("El receptor solicitó RELEASE_ALL"))
                        }
                        else -> continue
                    }
                }
            } catch (e: Exception) {
                if (!closing) {
                    scope.launch {
                        fail("Conexión perdida: ${e.message ?: e.javaClass.simpleName}")
                    }
                }
            }
        }.apply {
            name = "phantompad-rfcomm-reader"
            isDaemon = true
            start()
        }
    }

    private fun startPingLoop() {
        pingJob = scope.launch(Dispatchers.IO) {
            while (isActive && _state.value == TransportState.CONNECTED) {
                val s = pingSeq++ and 0xFFFF
                latency.onPingSent(s)
                pendingPings[s] = System.nanoTime()
                try {
                    val stream = out ?: break
                    writeMutex.withLock {
                        withContext(Dispatchers.IO) {
                            stream.write(PhantomProtocol.encodePing(s))
                            stream.flush()
                        }
                    }
                } catch (e: Exception) {
                    if (!closing) fail("Ping falló: ${e.message}")
                    break
                }
                delay(PING_INTERVAL_MS)
                if (latency.hasStalePending(3_000)) {
                    emit(TransportEvent.Info("Sin PONG durante 3 s: conexión degradada"))
                }
            }
        }
    }

    private suspend fun fail(message: String) {
        log.add(message)
        _state.value = TransportState.FAILED
        emit(TransportEvent.Failed(message))
        runCatching { socket?.close() }
    }

    companion object {
        /** UUID del servicio SPP publicado por PhantomPadReceiver (docs/PROTOCOL.md). */
        val SPP_UUID: UUID = UUID.fromString("8e6f4d2a-1b3c-4e5f-9a7d-2c3b4a5d6e7f")
        private const val LEN_HEADER = 4
        private const val PING_INTERVAL_MS = 1000L
    }
}
