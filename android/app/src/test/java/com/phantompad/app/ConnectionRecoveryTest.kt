package com.phantompad.app

import com.phantompad.app.gamepad.LatencyMeter
import com.phantompad.app.protocol.FrameParser
import com.phantompad.app.protocol.InputSnapshot
import com.phantompad.app.protocol.PhantomProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionRecoveryTest {
    @Test
    fun expiredPongsAreCountedOnceAndCannotChangeTheRtt() {
        var time = 0L
        val meter = LatencyMeter { time }
        meter.onPingSent(65535)
        time += 15_000_000
        assertTrue(meter.onPongReceived(65535))
        assertEquals(15.0, meter.stats.value.lastMs!!, 0.01)
        meter.onPingSent(0)
        time += 3_000_000_000
        assertEquals(1, meter.expire())
        assertEquals(0, meter.expire())
        assertFalse(meter.onPongReceived(0))
        assertNull(meter.stats.value.lastMs)
        assertEquals(1, meter.stats.value.lost)
        assertEquals(15.0, meter.stats.value.avgMs!!, 0.01)
    }

    @Test
    fun resetClearsPendingPingsAndSamples() {
        val meter = LatencyMeter { 0L }
        meter.onPingSent(22)
        meter.reset()
        assertFalse(meter.onPongReceived(22))
        assertEquals(LatencyMeter.LatencyStats(), meter.stats.value)
    }

    @Test
    fun reconnectBackoffIsBoundedAndResetsAfterASession() {
        val retry = ReconnectBackoff()
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 8000L), List(5) { retry.nextDelayMs() })
        assertEquals(5, retry.attempts)
        retry.reset()
        assertEquals(0, retry.attempts)
        assertEquals(1000L, retry.nextDelayMs())
    }

    @Test
    fun aMissingInputByteDoesNotConsumeThePongHeader() {
        val parser = FrameParser()
        val truncated = PhantomProtocol.encodeInput(InputSnapshot(), 10).dropLast(1).toByteArray()
        parser.append(truncated)
        assertNull(parser.next())
        val pong = PhantomProtocol.encodePong(11)
        parser.append(pong)
        assertEquals(11, PhantomProtocol.parseSeq(parser.next()!!.bytes))
        assertEquals(1, parser.rejectedFrames)
        assertEquals(truncated.size, parser.droppedBytes)
        assertEquals(0, parser.pendingBytes)
    }
}
