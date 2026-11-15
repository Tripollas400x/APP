package com.phantompad.app

import com.phantompad.app.data.ProfileId
import com.phantompad.app.gamepad.ControlBounds
import com.phantompad.app.gamepad.ElementIds
import com.phantompad.app.gamepad.LayoutProfiles
import com.phantompad.app.gamepad.PadLayout
import com.phantompad.app.gamepad.controlBounds
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutProfilesTest {
    @Test
    fun defaultProfilesKeepEveryTouchTargetVisibleAndSeparate() {
        for (profile in ProfileId.entries) {
            val layout = LayoutProfiles.default(profile)
            for (width in listOf(420f, 500f, 580f, 650f, 720f, 850f, 960f)) {
                val height = 330f
                val bounds = ElementIds.ALL.map { id ->
                    val control = layout.controlBounds(id, width, height)
                    assertTrue("$profile $width $id", control.left >= 0 && control.top >= 0 &&
                        control.left + control.width <= width + .001f && control.top + control.height <= height + .001f)
                    id to control
                }
                bounds.forEachIndexed { i, (id, control) ->
                    bounds.drop(i + 1).forEach { (otherId, other) ->
                        assertTrue("$profile $width $id / $otherId", !control.overlaps(other))
                    }
                }
            }
        }
    }

    @Test
    fun olderSavedLayoutsRetainPositionScaleAndDedicatedStickClicks() {
        val layout = Json.decodeFromString(
            PadLayout.serializer(),
            """{"elements":{"face":{"x":0.72,"y":0.61,"scale":1.15,"opacity":0.65}}}"""
        )
        val bounds = layout.controlBounds(ElementIds.FACE, 780f, 320f)
        assertEquals(1, layout.geometryVersion)
        assertEquals(.65f, layout.element(ElementIds.FACE).opacity, .001f)
        assertEquals(320f * .34f * 1.15f, bounds.width, .001f)
        assertEquals(780f * .72f, bounds.left + bounds.width / 2f, .001f)
        assertEquals(320f * .61f, bounds.top + bounds.height / 2f, .001f)
        assertEquals(.435f, layout.element(ElementIds.L3).x, .001f)
        assertEquals(.565f, layout.element(ElementIds.R3).x, .001f)
    }

    private fun ControlBounds.overlaps(other: ControlBounds): Boolean =
        minOf(left + width, other.left + other.width) - maxOf(left, other.left) > .001f &&
            minOf(top + height, other.top + other.height) - maxOf(top, other.top) > .001f
}
