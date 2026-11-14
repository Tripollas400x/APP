package com.phantompad.app

import com.phantompad.app.protocol.ButtonId
import com.phantompad.app.protocol.InputSnapshot
import com.phantompad.app.protocol.PhantomProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Vectores dorados compartidos con C# (PhantomProtocolTests) y con la
 * referencia Python (scripts/protocol_reference.py). Si este test falla,
 * el receptor y la app hablaban idiomas distintos.
 */
class ProtocolTest {

    @Test
    fun `crc8 standard check value`() {
        assertEquals(0xF4, PhantomProtocol.crc8("123456789".toByteArray()))
    }

    @Test
    fun `golden input frame matches windows receiver`() {
        val snapshot = InputSnapshot(
            buttons = ButtonId.TRIANGLE.bit or ButtonId.CIRCLE.bit or
                ButtonId.CROSS.bit or ButtonId.SQUARE.bit or ButtonId.L1.bit,
            lx = -1f, ly = 0f, rx = 1f, ry = 0f,
            l2 = 0.5f, r2 = 0.25f,
            touch = false,
            gyro = null
        )
        val frame = PhantomProtocol.encodeInput(snapshot, seq = 1)
        val expected = byteArrayOf(
            0x50.toByte(), 0x50.toByte(), 0x01, 0x01, // header
            0x01, 0x00,                               // seq = 1
            0x0F, 0x01, 0x00, 0x00,                   // buttons = 0x0000010F
            0x81.toByte(), 0x00, 0x7F, 0x00,          // lx, ly, rx, ry
            0x80.toByte(), 0x40,                      // l2, r2
            0x00,                                     // flags
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,       // giroscopio a cero
            0xB5.toByte()                             // crc
        )
        assertArrayEquals(expected, frame)
        assertEquals(PhantomProtocol.LEN_INPUT, frame.size)
    }

    @Test
    fun `ping rumble and release frames`() {
        assertArrayEquals(
            byteArrayOf(0x50.toByte(), 0x50.toByte(), 0x01, 0x02, 0x2A, 0x00, 0xFE.toByte()),
            PhantomProtocol.encodePing(0x2A)
        )
        assertArrayEquals(
            byteArrayOf(0x50.toByte(), 0x50.toByte(), 0x01, 0x10, 0x40, 0x80.toByte(), 0x74.toByte()),
            PhantomProtocol.encodeRumble(0x40, 0x80)
        )
        assertArrayEquals(
            byteArrayOf(0x50.toByte(), 0x50.toByte(), 0x01, 0x04, 0xD1.toByte()),
            PhantomProtocol.encodeReleaseAll()
        )
    }

    @Test
    fun `frame validation accepts valid and rejects corrupted`() {
        val frame = PhantomProtocol.encodeInput(InputSnapshot(), 7)
        assertEquals(PhantomProtocol.TYPE_INPUT, PhantomProtocol.frameType(frame))
        frame[10] = (frame[10] + 1).toByte()
        assertEquals(-1, PhantomProtocol.frameType(frame))
    }

    @Test
    fun `axis and trigger conversions clamp`() {
        assertEquals(127.toByte(), PhantomProtocol.axisByte(2f))
        assertEquals((-127).toByte(), PhantomProtocol.axisByte(-2f))
        assertEquals(0.toByte(), PhantomProtocol.axisByte(0f))
        assertEquals(255.toByte(), PhantomProtocol.triggerByte(1f))
        assertEquals(0.toByte(), PhantomProtocol.triggerByte(-1f))
        assertEquals(128.toByte(), PhantomProtocol.triggerByte(0.5f))
    }
}
