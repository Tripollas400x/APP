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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.UUID

/** RFCOMM con un socket y un único escritor por sesión. */
@SuppressLint("MissingPermission") // PadViewModel verifica permisos y adaptador.
class RfcommTransport(
    private val scope: CoroutineScope,
    private val bt: BluetoothManager,
    private val log: ConnectionLog,
    private val latency: LatencyMeter
) : BaseTransport() {

    private class Write(
        val data: ByteArray,
        val ping: Int? = null,
        val done: CompletableDeferred<Unit> = CompletableDeferred()
    )

    private class Session(val socket: BluetoothSocket) {
        val outbox = Channel<Write>(8, onUndeliveredElement = { it.done.cancel() })
        val verified = CompletableDeferred<Unit>()
        val lifetime = Job()
        var inputSequence = 0
    }

    private var opening: BluetoothSocket? = null
    @Volatile private var session: Session? = null

    override suspend fun connect(device: BluetoothDevice): Boolean {
        close()
        _state.value = TransportState.CONNECTING
        try {
            stopDiscovery()
            val channels = listOf(
                "seguro" to { device.createRfcommSocketToServiceRecord(SPP_UUID) },
                "alternativo" to { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) }
            )
            var last: IOException? = null
            for ((label, create) in channels) {
                log.add("RFCOMM: abriendo canal $label.")
                try {
                    val socket = create()
                    opening = socket
                    connectSocket(socket)
                    val current = Session(socket)
                    synchronized(latency) {
                        latency.reset()
                        session = current
                    }
                    opening = null
                    startSession(current)
                    try {
                        withTimeout(4500) { verifyChannel(current) }
                    } catch (_: TimeoutCancellationException) {
                        throw IOException("El receptor no respondió al primer PING")
                    }
                    if (session !== current) throw IOException("El canal se cerró durante la verificación")
                    _deviceName.value = device.safeDisplayName()
                    _state.value = TransportState.CONNECTED
                    log.add("RFCOMM: canal bidireccional verificado con ${_deviceName.value}.")
                    return true
                } catch (e: IOException) {
                    last = e
                    clearSockets()
                    log.add("RFCOMM $label: ${e.message}")
                    delay(300)
                }
            }
            throw last ?: IOException("Canal RFCOMM no disponible")
        } catch (e: CancellationException) {
            close()
            throw e
        } catch (e: Exception) {
            clearSockets()
            reportFailure(e)
            return false
        }
    }

    private suspend fun verifyChannel(current: Session) = coroutineScope {
        val compatibility = launch {
            // v0.2.1 espera lecturas de 4096 B. Solo se envía reposo mientras
            // llega el PONG inicial si el receptor tarda en contestar.
            delay(500)
            while (isActive && !current.verified.isCompleted) {
                send(current, Write(PhantomProtocol.encodeInput(InputSnapshot(), current.inputSequence++ and 0xFFFF)))
                delay(16)
            }
        }
        try {
            current.verified.await()
        } finally {
            compatibility.cancel()
        }
    }

    private suspend fun connectSocket(socket: BluetoothSocket) = coroutineScope {
        val result = CompletableDeferred<Unit>()
        val worker = launch(Dispatchers.IO) {
            try {
                socket.connect()
                result.complete(Unit)
            } catch (e: Exception) {
                result.completeExceptionally(e)
            }
        }
        var connected = false
        try {
            withTimeout(12_000) { result.await() }
            connected = true
        } catch (_: TimeoutCancellationException) {
            throw IOException("Tiempo de conexión agotado")
        } finally {
            if (!connected) {
                runCatching { socket.close() }
                worker.cancel()
            }
        }
    }

    private suspend fun stopDiscovery() {
        if (!bt.isDiscovering()) return
        bt.stopScan()
        val deadline = SystemClock.elapsedRealtime() + 2_000
        while (bt.isDiscovering() && SystemClock.elapsedRealtime() < deadline) delay(100)
    }

    private fun startSession(current: Session) {
        scope.launch(Dispatchers.IO + current.lifetime) {
            var active: Write? = null
            try {
                val stream = current.socket.outputStream
                for (request in current.outbox) {
                    active = request
                    synchronized(latency) {
                        if (session === current) request.ping?.let(latency::onPingSent)
                    }
                    stream.write(request.data)
                    request.done.complete(Unit)
                    active = null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                active?.done?.completeExceptionally(e)
                failSession(current, e)
            } finally {
                active?.done?.cancel()
            }
        }
        scope.launch(Dispatchers.IO + current.lifetime) {
            val parser = FrameParser()
            val buffer = ByteArray(512)
            try {
                val input = current.socket.inputStream
                while (isActive) {
                    val count = input.read(buffer)
                    if (count <= 0) throw IOException("El receptor cerró el enlace")
                    parser.append(buffer, 0, count)
                    while (true) {
                        val frame = parser.next() ?: break
                        when (frame.type) {
                            PhantomProtocol.TYPE_PONG -> {
                                synchronized(latency) {
                                    if (session === current && latency.onPongReceived(PhantomProtocol.parseSeq(frame.bytes))) {
                                        current.verified.complete(Unit)
                                    }
                                }
                            }
                            PhantomProtocol.TYPE_PING -> send(current, Write(
                                PhantomProtocol.encodePong(PhantomProtocol.parseSeq(frame.bytes))))
                            PhantomProtocol.TYPE_RUMBLE -> {
                                val (small, large) = PhantomProtocol.parseRumble(frame.bytes)
                                if (session === current) emit(TransportEvent.Rumble(small, large))
                            }
                            PhantomProtocol.TYPE_RELEASE_ALL -> if (session === current) emit(TransportEvent.ReleaseInputs)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failSession(current, e)
            }
        }
        scope.launch(current.lifetime) {
            var seq = 0
            var missed = 0
            try {
                while (isActive) {
                    val ping = seq++ and 0xFFFF
                    send(current, Write(PhantomProtocol.encodePing(ping), ping))
                    delay(1000)
                    val lost = synchronized(latency) { if (session === current) latency.expire() else 0 }
                    missed = if (lost > 0) missed + lost else 0
                    if (missed >= 3) throw IOException("El receptor dejó de responder a PING")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failSession(current, e)
            }
        }
    }

    private suspend fun send(current: Session, packet: Write) {
        try {
            withTimeout(1500) {
                current.outbox.send(packet)
                packet.done.await()
            }
        } catch (_: TimeoutCancellationException) {
            throw IOException("La escritura RFCOMM se bloqueó")
        } catch (e: CancellationException) {
            if (!current.lifetime.isActive && kotlinx.coroutines.currentCoroutineContext().isActive) {
                throw IOException("El canal RFCOMM se cerró", e)
            }
            throw e
        }
    }

    override suspend fun sendInput(snapshot: InputSnapshot) {
        val current = session ?: throw IOException("RFCOMM desconectado")
        send(current, Write(PhantomProtocol.encodeInput(snapshot, current.inputSequence++ and 0xFFFF)))
    }

    override suspend fun releaseAll() {
        session?.let { send(it, Write(PhantomProtocol.encodeReleaseAll())) }
    }

    override suspend fun onSendFailure(cause: Exception) {
        session?.let { failSession(it, cause) }
    }

    private fun failSession(current: Session, cause: Exception) {
        scope.launch {
            if (session !== current) return@launch
            if (_state.value == TransportState.CONNECTING) {
                current.verified.completeExceptionally(IOException(cause.message ?: "Canal RFCOMM cerrado", cause))
                return@launch
            }
            clearSockets()
            reportFailure(cause)
        }
    }

    private fun reportFailure(cause: Exception) {
        _state.value = TransportState.FAILED
        emit(TransportEvent.Failed("RFCOMM: ${cause.message ?: cause.javaClass.simpleName}"))
    }

    private fun clearSockets() {
        val current = synchronized(latency) {
            val previous = session
            session = null
            previous
        }
        current?.lifetime?.cancel()
        current?.outbox?.cancel()
        runCatching { current?.socket?.close() }
        runCatching { opening?.close() }
        opening = null
    }

    override fun close() {
        clearSockets()
        _deviceName.value = null
        _state.value = TransportState.DISCONNECTED
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("8e6f4d2a-1b3c-4e5f-9a7d-2c3b4a5d6e7f")
    }
}
