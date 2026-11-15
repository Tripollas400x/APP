package com.phantompad.app.protocol

import kotlin.math.roundToInt

/**
 * Protocolo binario Phantom Pad v1 (ver docs/PROTOCOL.md).
 *
 * Frame = cabecera 4B + payload + CRC-8 (poly 0x07, init 0x00).
 * Todos los enteros multi-byte en little-endian.
 *
 * Los vectores de prueba compartidos con C# y Python viven en
 * ProtocolTest (Kotlin), PhantomProtocolTests (C#) y scripts/protocol_reference.py.
 */
object PhantomProtocol {

    const val PROTOCOL_VERSION = 1
    const val MAGIC0 = 0x50
    const val MAGIC1 = 0x50

    const val TYPE_INPUT = 0x01
    const val TYPE_PING = 0x02
    const val TYPE_PONG = 0x03
    const val TYPE_RELEASE_ALL = 0x04
    const val TYPE_RUMBLE = 0x10

    const val FLAG_GYRO = 0x01
    const val FLAG_TOUCH = 0x02

    const val LEN_HEADER = 4
    const val LEN_INPUT = 30
    const val LEN_PING = 7
    const val LEN_RUMBLE = 7
    const val LEN_RELEASE_ALL = 5

    // ------------------------------------------------------------------ CRC-8

    fun crc8(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var crc = 0
        for (i in from until to) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x07) and 0xFF else (crc shl 1) and 0xFF
            }
        }
        return crc
    }

    // ------------------------------------------------------------ conversión

    /** Eje float [-1, 1] -> byte con signo [-127, 127], 0 = centro. */
    fun axisByte(value: Float): Byte = (value.coerceIn(-1f, 1f) * 127f).roundToInt().toByte()

    /** Gatillo float [0, 1] -> byte [0, 255]. */
    fun triggerByte(value: Float): Byte = (value.coerceIn(0f, 1f) * 255f).roundToInt().toByte()

    // ---------------------------------------------------------------- escritura

    private fun putU16(b: ByteArray, o: Int, v: Int) {
        b[o] = (v and 0xFF).toByte()
        b[o + 1] = ((v shr 8) and 0xFF).toByte()
    }

    private fun putU32(b: ByteArray, o: Int, v: Int) {
        b[o] = (v and 0xFF).toByte()
        b[o + 1] = ((v shr 8) and 0xFF).toByte()
        b[o + 2] = ((v shr 16) and 0xFF).toByte()
        b[o + 3] = ((v shr 24) and 0xFF).toByte()
    }

    private fun putI16(b: ByteArray, o: Int, v: Int) = putU16(b, o, v and 0xFFFF)

    /** Codifica el estado completo como frame INPUT de 30 bytes. */
    fun encodeInput(s: InputSnapshot, seq: Int): ByteArray {
        val b = ByteArray(LEN_INPUT)
        b[0] = MAGIC0.toByte()
        b[1] = MAGIC1.toByte()
        b[2] = PROTOCOL_VERSION.toByte()
        b[3] = TYPE_INPUT.toByte()
        putU16(b, 4, seq and 0xFFFF)
        putU32(b, 6, s.buttons)
        b[10] = axisByte(s.lx)
        b[11] = axisByte(s.ly)
        b[12] = axisByte(s.rx)
        b[13] = axisByte(s.ry)
        b[14] = triggerByte(s.l2)
        b[15] = triggerByte(s.r2)
        var flags = 0
        if (s.gyro != null) flags = flags or FLAG_GYRO
        if (s.touch) flags = flags or FLAG_TOUCH
        b[16] = flags.toByte()
        val g = s.gyro
        if (g != null) {
            val n = minOf(6, g.size)
            for (i in 0 until n) putI16(b, 17 + i * 2, g[i].toInt())
        }
        b[LEN_INPUT - 1] = crc8(b, 0, LEN_INPUT - 1).toByte()
        return b
    }

    fun encodePing(seq: Int): ByteArray = shortFrame(TYPE_PING, seq)
    fun encodePong(seq: Int): ByteArray = shortFrame(TYPE_PONG, seq)

    private fun shortFrame(type: Int, seq: Int): ByteArray {
        val b = ByteArray(LEN_PING)
        b[0] = MAGIC0.toByte()
        b[1] = MAGIC1.toByte()
        b[2] = PROTOCOL_VERSION.toByte()
        b[3] = type.toByte()
        putU16(b, 4, seq and 0xFFFF)
        b[LEN_PING - 1] = crc8(b, 0, LEN_PING - 1).toByte()
        return b
    }

    fun encodeReleaseAll(): ByteArray {
        val b = ByteArray(LEN_RELEASE_ALL)
        b[0] = MAGIC0.toByte()
        b[1] = MAGIC1.toByte()
        b[2] = PROTOCOL_VERSION.toByte()
        b[3] = TYPE_RELEASE_ALL.toByte()
        b[LEN_RELEASE_ALL - 1] = crc8(b, 0, LEN_RELEASE_ALL - 1).toByte()
        return b
    }

    fun encodeRumble(small: Int, large: Int): ByteArray {
        val b = ByteArray(LEN_RUMBLE)
        b[0] = MAGIC0.toByte()
        b[1] = MAGIC1.toByte()
        b[2] = PROTOCOL_VERSION.toByte()
        b[3] = TYPE_RUMBLE.toByte()
        b[4] = small.coerceIn(0, 255).toByte()
        b[5] = large.coerceIn(0, 255).toByte()
        b[LEN_RUMBLE - 1] = crc8(b, 0, LEN_RUMBLE - 1).toByte()
        return b
    }

    // ------------------------------------------------------------------ lectura

    /** Valida cabecera y CRC. Devuelve el tipo o -1 si el frame es inválido. */
    fun frameType(frame: ByteArray): Int {
        if (frame.size < LEN_HEADER) return -1
        if (frame[0].toInt() and 0xFF != MAGIC0) return -1
        if (frame[1].toInt() and 0xFF != MAGIC1) return -1
        if (frame[2].toInt() and 0xFF != PROTOCOL_VERSION) return -1
        val type = frame[3].toInt() and 0xFF
        val expected = when (type) {
            TYPE_INPUT -> LEN_INPUT
            TYPE_PING, TYPE_PONG, TYPE_RUMBLE -> LEN_PING
            TYPE_RELEASE_ALL -> LEN_RELEASE_ALL
            else -> return -1
        }
        if (frame.size < expected) return -1
        if (crc8(frame, 0, expected - 1) != frame[expected - 1].toInt() and 0xFF) return -1
        return type
    }

    private fun getU16(frame: ByteArray, o: Int): Int =
        (frame[o].toInt() and 0xFF) or ((frame[o + 1].toInt() and 0xFF) shl 8)

    /** Secuencia de un frame PING/PONG ya validado. */
    fun parseSeq(frame: ByteArray): Int = getU16(frame, 4)

    /** Rumble ya validado: (small, large). */
    fun parseRumble(frame: ByteArray): Pair<Int, Int> =
        (frame[4].toInt() and 0xFF) to (frame[5].toInt() and 0xFF)
}
