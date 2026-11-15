package com.phantompad.app

import com.phantompad.app.protocol.FrameParser
import com.phantompad.app.protocol.PhantomProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests del parser incremental FrameParser (espejo de FramePipeTests.cs en
 * C#): frames fragmentados, concatenados, basura, CRC inválido, tipos
 * desconocidos y RELEASE_ALL (5 bytes; el lector antiguo consumía 3 de
 * cuerpo y desincronizaba el stream).
 */
class FrameParserTest {

    private fun sampleInput(seq: Int): ByteArray {
        val f = ByteArray(PhantomProtocol.LEN_INPUT)
        f[0] = PhantomProtocol.MAGIC0.toByte()
        f[1] = PhantomProtocol.MAGIC1.toByte()
        f[2] = PhantomProtocol.PROTOCOL_VERSION.toByte()
        f[3] = PhantomProtocol.TYPE_INPUT.toByte()
        f[4] = (seq and 0xFF).toByte()
        f[5] = ((seq shr 8) and 0xFF).toByte()
        f[6] = 0x01
        f[PhantomProtocol.LEN_INPUT - 1] =
            PhantomProtocol.crc8(f, 0, PhantomProtocol.LEN_INPUT - 1).toByte()
        return f
    }

    @Test
    fun `frame fragmentado byte a byte se recompone`() {
        val parser = FrameParser()
        val pong = PhantomProtocol.encodePong(0x0102)
        for (i in 0 until pong.size - 1) {
            parser.append(byteArrayOf(pong[i]))
            assertTrue(parser.next() == null)
        }
        parser.append(byteArrayOf(pong[pong.size - 1]))
        val frame = parser.next()
        assertTrue(frame != null && frame.type == PhantomProtocol.TYPE_PONG)
        assertEquals(0x0102, PhantomProtocol.parseSeq(frame!!.bytes))
    }

    @Test
    fun `dos frames concatenados en una lectura`() {
        val parser = FrameParser()
        parser.append(sampleInput(1) + sampleInput(2))
        val first = parser.next()!!
        assertEquals(1, PhantomProtocol.parseSeq(first.bytes))
        val second = parser.next()!!
        assertEquals(2, PhantomProtocol.parseSeq(second.bytes))
        assertTrue(parser.next() == null)
    }

    @Test
    fun `basura delante se resincroniza`() {
        val parser = FrameParser()
        parser.append(byteArrayOf(0x00, 0xFF.toByte(), 0x13, 0x37, 0x42))
        assertTrue(parser.next() == null)
        parser.append(PhantomProtocol.encodePong(0x77))
        val frame = parser.next()!!
        assertEquals(PhantomProtocol.TYPE_PONG, frame.type)
        assertEquals(0x77, PhantomProtocol.parseSeq(frame.bytes))
    }

    @Test
    fun `frame con CRC invalido se descarta y el stream se recupera`() {
        val parser = FrameParser()
        val corrupt = sampleInput(9)
        corrupt[corrupt.size - 1] = (corrupt[corrupt.size - 1].toInt() xor 0xFF).toByte()
        parser.append(corrupt + sampleInput(10))
        val frame = parser.next()!!
        assertEquals(10, PhantomProtocol.parseSeq(frame.bytes))
        assertEquals(1, parser.rejectedFrames)
    }

    @Test
    fun `tipo desconocido resincroniza sin perder el siguiente frame`() {
        val parser = FrameParser()
        parser.append(
            byteArrayOf(
                PhantomProtocol.MAGIC0.toByte(), PhantomProtocol.MAGIC1.toByte(),
                PhantomProtocol.PROTOCOL_VERSION.toByte(), 0xAB.toByte(), 1, 2, 3, 4, 5
            )
        )
        assertTrue(parser.next() == null)
        parser.append(PhantomProtocol.encodePong(0x21))
        val frame = parser.next()!!
        assertEquals(PhantomProtocol.TYPE_PONG, frame.type)
        assertEquals(0x21, PhantomProtocol.parseSeq(frame.bytes))
    }

    @Test
    fun `release all se consume completo (5 bytes, no 3)`() {
        val parser = FrameParser()
        val release = PhantomProtocol.encodeReleaseAll()
        assertEquals(PhantomProtocol.LEN_RELEASE_ALL, release.size)
        // El parser recibe el RELEASE_ALL seguido de un PONG: si el cuerpo del
        // RELEASE_ALL se consumiera mal, el PONG se corrompería.
        parser.append(release + PhantomProtocol.encodePong(0x42))
        val first = parser.next()!!
        assertEquals(PhantomProtocol.TYPE_RELEASE_ALL, first.type)
        assertArrayEquals(release, first.bytes)
        val second = parser.next()!!
        assertEquals(PhantomProtocol.TYPE_PONG, second.type)
        assertEquals(0x42, PhantomProtocol.parseSeq(second.bytes))
    }

    @Test
    fun `volumen alto mixto sobrevive`() {
        val parser = FrameParser()
        val expected = mutableListOf<Int>()
        val stream = mutableListOf<Byte>()
        val rnd = kotlin.random.Random(42)
        for (i in 0 until 200) {
            when (i % 5) {
                0, 1 -> { stream.addAll(sampleInput(i).toList()); expected.add(i) }
                2 -> { stream.addAll(PhantomProtocol.encodePong(i).toList()); expected.add(i) }
                3 -> {
                    val bad = sampleInput(i)
                    bad[bad.size - 1] = (bad[bad.size - 1].toInt() xor 0xAA).toByte()
                    stream.addAll(bad.toList())
                }
                else -> stream.add((0x10 + (i and 0x0F)).toByte())
            }
        }
        val data = stream.toByteArray()
        var offset = 0
        while (offset < data.size) {
            val take = minOf(rnd.nextInt(1, 50), data.size - offset)
            parser.append(data, offset, take)
            offset += take
        }
        val read = mutableListOf<Int>()
        while (true) {
            val frame = parser.next() ?: break
            read.add(PhantomProtocol.parseSeq(frame.bytes))
        }
        assertEquals(expected, read)
    }

    @Test
    fun `append vacio no rompe el estado`() {
        val parser = FrameParser()
        parser.append(ByteArray(0))
        assertTrue(parser.next() == null)
        assertFalse(parser.pendingBytes > 0)
    }
}
