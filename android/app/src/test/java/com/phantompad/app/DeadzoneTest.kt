package com.phantompad.app

import com.phantompad.app.gamepad.StickMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeadzoneTest {

    @Test
    fun `inside deadzone returns zero`() {
        assertEquals(0f to 0f, StickMath.radialDeadzone(0.05f, 0.05f, 0.1f))
        assertEquals(0f to 0f, StickMath.radialDeadzone(0f, 0f, 0.1f))
    }

    @Test
    fun `full deflection stays full`() {
        val (x, y) = StickMath.radialDeadzone(1f, 0f, 0.1f)
        assertEquals(1f, x, 1e-4f)
        assertEquals(0f, y, 1e-4f)
    }

    @Test
    fun `response is continuous at deadzone edge`() {
        val (x, _) = StickMath.radialDeadzone(0.11f, 0f, 0.1f)
        assertTrue("x=$x", x > 0f && x < 0.05f)
    }

    @Test
    fun `sign is preserved in 1d deadzone`() {
        assertEquals(-0.5f, StickMath.applyDeadzone(-0.55f, 0.1f), 1e-3f)
        assertEquals(0.5f, StickMath.applyDeadzone(0.55f, 0.1f), 1e-3f)
        assertEquals(0f, StickMath.applyDeadzone(0.05f, 0.1f), 1e-6f)
    }

    @Test
    fun `sensitivity clamps to unit circle`() {
        assertEquals(1f, StickMath.responseCurve(0.9f, 1.4f), 1e-6f)
        assertEquals(-1f, StickMath.responseCurve(-0.9f, 1.4f), 1e-6f)
        assertEquals(0.45f, StickMath.responseCurve(0.9f, 0.5f), 1e-6f)
    }
}
