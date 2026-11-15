package com.phantompad.app.protocol

/**
 * Parser incremental de frames del protocolo (espejo exacto de FramePipe en
 * el receptor C#). Resuelve la desincronización del lector antiguo:
 *  - frames fragmentados en varias lecturas del socket,
 *  - varios frames llegando juntos en una lectura,
 *  - bytes basura delante,
 *  - frames con CRC inválido (se cuenta el rechazo y se busca la próxima cabecera),
 *  - tipos desconocidos (no se sabe su longitud: se resincroniza byte a byte).
 *
 * Uso: append() con lo que llegue del stream y next() hasta que devuelva
 * null. Consumo desde un único hilo (el hilo lector del transporte).
 */
class FrameParser {

    class Frame(val type: Int, val bytes: ByteArray)

    private var buf = ByteArray(8 * 1024)
    private var len = 0

    /** Frames completos descartados por CRC inválido. */
    var rejectedFrames = 0
        private set

    /** Bytes descartados durante la resincronización. */
    var droppedBytes = 0
        private set

    /** Bytes pendientes de procesar dentro del parser. */
    val pendingBytes: Int get() = len

    fun append(data: ByteArray, offset: Int = 0, count: Int = data.size - offset) {
        if (count <= 0) return
        if (len + count > buf.size) {
            buf = buf.copyOf(maxOf(len + count, buf.size * 2))
        }
        System.arraycopy(data, offset, buf, len, count)
        len += count
    }

    /**
     * Devuelve el siguiente frame válido o null si no hay suficientes bytes.
     * Los bytes basura y los frames corruptos se descartan internamente.
     */
    fun next(): Frame? {
        while (true) {
            if (len < PhantomProtocol.LEN_HEADER) return null

            if (buf[0] != PhantomProtocol.MAGIC0.toByte() ||
                buf[1] != PhantomProtocol.MAGIC1.toByte() ||
                buf[2] != PhantomProtocol.PROTOCOL_VERSION.toByte()
            ) {
                consume(1, discarded = true) // basura delante: resincronizar byte a byte
                continue
            }

            val type = buf[3].toInt() and 0xFF
            val expected = when (type) {
                PhantomProtocol.TYPE_INPUT -> PhantomProtocol.LEN_INPUT
                PhantomProtocol.TYPE_PING, PhantomProtocol.TYPE_PONG -> PhantomProtocol.LEN_PING
                PhantomProtocol.TYPE_RUMBLE -> PhantomProtocol.LEN_RUMBLE
                PhantomProtocol.TYPE_RELEASE_ALL -> PhantomProtocol.LEN_RELEASE_ALL
                else -> 0
            }
            if (expected == 0) {
                consume(1, discarded = true) // tipo desconocido: longitud desconocida, resincronizar
                continue
            }
            if (len < expected) return null // frame a medias: esperar más bytes

            if (PhantomProtocol.crc8(buf, 0, expected - 1) != (buf[expected - 1].toInt() and 0xFF)) {
                rejectedFrames++
                consume(1, discarded = true) // Recuperar aunque falte un byte.
                continue
            }

            val out = buf.copyOf(expected)
            consume(expected)
            return Frame(type, out)
        }
    }

    private fun consume(count: Int, discarded: Boolean = false) {
        if (discarded) droppedBytes += count
        System.arraycopy(buf, count, buf, 0, len - count)
        len -= count
    }
}
