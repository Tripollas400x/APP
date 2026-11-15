package com.phantompad.app.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.SystemClock
import com.phantompad.app.bt.BluetoothManager
import com.phantompad.app.gamepad.LatencyMeter
import com.phantompad.app.protocol.FrameParser
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
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Método B: cliente RFCOMM/SPP hacia PhantomPadReceiver.exe.
 *
 * Flujo de conexión corregido:
 *  1. Se cancela el descubrimiento Bluetooth y se ESPERA a que la pila lo
 *     detenga de verdad (un discovery en marcha provoca fallos de SDP y de
 *     canal en muchos equipos).
 *  2. Se crea el socket (seguro primero; sin cifrado como alternativa) y se
 *     conecta con TIMEOUT real: socket.connect() puede bloquearse para
 *     siempre, así que se corre en un hilo y si excede el plazo se cierra el
 *     socket, lo que aborta la llamada.
 *  3. El hilo lector usa el parser incremental [FrameParser]: tolera frames
 *     fragmentados, concatenados, CRC inválido y RELEASE_ALL (5 bytes, antes
 *     se leían 3 de cuerpo y se desincronizaba el stream).
 *
 * Un único escritor (mutex) evita interleaving de frames.
 */
@SuppressLint("MissingPermission") // los permisos se verifican en la UI antes de conectar
class RfcommTransport(
    private val scope: CoroutineScope,
    private val bt: BluetoothManager,
    private val log: ConnectionLog,
    private val latency: LatencyMeter
) : BaseTransport() {

    private var socket: BluetoothSocket? = null
    private var out: DataOutputStream? = null
    private var readerThread: Thread? = null
    private var pingJob: Job? = null
    private val writeMutex = Mutex()
    private val connectMutex = Mutex()
    private val pendingPings = ConcurrentHashMap<Int, Long>()

    @Volatile
    private var firstPacketSeen = false

    private var sendSeq = 0
    private var pingSeq = 0

    @Volatile
    private var closing = false

    override suspend fun connect(device: BluetoothDevice): Boolean {
        connectMutex.withLock {
            closing = false
            firstPacketSeen = false
            _state.value = TransportState.CONNECTING
            emit(TransportEvent.Info("Conectando con ${device.safeDisplayName()}..."))
            return try {
                var connected: BluetoothSocket? = null
                var lastError: IOException? = null
                withContext(Dispatchers.IO) {
                    // 1) Detener el descubrimiento ANTES de tocar el canal:
                    //    con discovery activo, el SDP lookup y el connect
                    //    fallan o tardan una eternidad.
                    stopDiscoveryAndWait()

                    // 2) Probar seguro y, si el host lo rechaza, sin cifrado.
                    //    El receptor publica BluetoothEncryptionAllowNullAuthentication,
                    //    que acepta ambos; el orden evita depender del inseguro
                    //    cuando no hace falta.
                    val attempts = listOf(
                        "seguro" to { device.createRfcommSocketToServiceRecord(SPP_UUID) },
                        "sin cifrado" to { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) }
                    )
                    for ((label, make) in attempts) {
                        if (closing) return@withContext
                        var s: BluetoothSocket? = null
                        try {
                            log.add("RFCOMM: abriendo canal $label...")
                            s = make()
                            socket = s
                            if (connectWithTimeout(s, CONNECT_TIMEOUT_MS)) {
                                connected = s
                                log.add("RFCOMM: canal $label establecido.")
                                break
                            }
                            lastError = lastError ?: IOException("Canal $label: connect() no respondió en ${CONNECT_TIMEOUT_MS / 1000} s")
                        } catch (e: IOException) {
                            lastError = e
                            log.add("RFCOMM: canal $label falló: ${e.message ?: e.javaClass.simpleName}")
                        } finally {
                            if (connected == null) {
                                runCatching { s?.close() }
                                runCatching { if (socket === s) socket = null }
                            }
                        }
                        delay(300) // dejar que la pila BT enfríe entre intentos
                    }

                    val s = connected ?: throw lastError ?: IOException("No se pudo abrir el canal RFCOMM")
                    out = DataOutputStream(BufferedOutputStream(s.outputStream))
                    _deviceName.value = device.safeDisplayName()
                    startReader(s)
                }
                if (closing) {
                    runCatching { connected?.close() }
                    return false
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
    }

    /**
     * Cancela el discovery del adaptador y espera (máx. 2 s) a que la pila
     * lo confirme; conectarse con discovery activo es causa clásica de
     * "socket closed" y de SDP que no resuelve el servicio.
     */
    private fun stopDiscoveryAndWait() {
        val discovering = try {
            bt.isDiscovering
        } catch (_: SecurityException) {
            false
        }
        if (!discovering) return
        log.add("RFCOMM: cancelando descubrimiento Bluetooth antes de conectar...")
        bt.stopScan()
        val deadline = SystemClock.elapsedRealtime() + 2_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!bt.isDiscovering) {
                log.add("RFCOMM: descubrimiento detenido.")
                return
            }
            runCatching { Thread.sleep(100) }
        }
        log.add("RFCOMM: el discovery sigue activo; se intenta conectar igualmente.")
    }

    /**
     * socket.connect() se bloquea sin límite; corre en un hilo dedicado y si
     * excede el plazo se cierra el socket para abortarlo. Devuelve true solo
     * si la conexión llegó a establecerse.
     */
    private fun connectWithTimeout(s: BluetoothSocket, timeoutMs: Long): Boolean {
        val attempt = AtomicBoolean(false)
        val error = arrayOfNulls<Exception>(1)
        val thread = Thread {
            try {
                s.connect()
                attempt.set(true)
            } catch (e: Exception) {
                error[0] = e
            }
        }
        thread.name = "phantompad-rfcomm-connect"
        thread.isDaemon = true
        thread.start()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (thread.isAlive && SystemClock.elapsedRealtime() < deadline) {
            thread.join(150)
        }
        if (thread.isAlive) {
            // Abortar el connect bloqueado cerrando el socket por debajo.
            runCatching { s.close() }
            thread.join(1_000)
            log.add("RFCOMM: connect() excedió ${timeoutMs} ms; intento abortado.")
        }
        if (attempt.get()) return true
        throw error[0] ?: IOException("connect() agotó el plazo de $timeoutMs ms")
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
            val parser = FrameParser()
            val chunk = ByteArray(2048)
            try {
                val input = socket.inputStream
                while (!closing && _state.value != TransportState.FAILED) {
                    val n = input.read(chunk)
                    if (n <= 0) throw IOException("Stream cerrado por el receptor")
                    parser.append(chunk, 0, n)
                    var frame = parser.next()
                    while (frame != null) {
                        if (!firstPacketSeen) {
                            firstPacketSeen = true
                            log.add("RFCOMM: primer paquete recibido del receptor (enlace bidireccional OK).")
                        }
                        handleFrame(frame.type, frame.bytes)
                        frame = parser.next()
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

    private fun handleFrame(type: Int, frame: ByteArray) {
        when (type) {
            PhantomProtocol.TYPE_PONG -> {
                val seq = PhantomProtocol.parseSeq(frame)
                pendingPings.remove(seq)
                latency.onPongReceived(seq)
            }
            PhantomProtocol.TYPE_RUMBLE -> {
                val (small, large) = PhantomProtocol.parseRumble(frame)
                emit(TransportEvent.Rumble(small, large))
            }
            PhantomProtocol.TYPE_RELEASE_ALL -> {
                // El receptor pidió liberar (failsafe): el estado local se limpia.
                emit(TransportEvent.Info("El receptor solicitó RELEASE_ALL"))
            }
            else -> Unit // tipos no esperados: el parser ya los descartó
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

    private fun BluetoothDevice.safeDisplayName(): String = try {
        name ?: address
    } catch (_: SecurityException) {
        address
    }

    companion object {
        /** UUID del servicio SPP publicado por PhantomPadReceiver (docs/PROTOCOL.md). */
        val SPP_UUID: UUID = UUID.fromString("8e6f4d2a-1b3c-4e5f-9a7d-2c3b4a5d6e7f")
        private const val CONNECT_TIMEOUT_MS = 12_000L
        private const val PING_INTERVAL_MS = 1000L
    }
}
