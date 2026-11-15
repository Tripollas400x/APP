package com.phantompad.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.phantompad.app.gamepad.StickMath
import com.phantompad.app.ui.theme.Accent
import com.phantompad.app.ui.theme.BorderGlow
import com.phantompad.app.ui.theme.SurfaceElevated

/**
 * Joystick analógico virtual. Sigue a UN dedo (por id) para no interferir con
 * otros controles: multitáctil real. Aplica zona muerta radial, sensibilidad,
 * retorno al centro al soltar y valores continuos [-1, 1].
 */
@Composable
fun Joystick(
    side: Dp,
    opacity: Float,
    deadzone: Float,
    sensitivity: Float,
    hapticsEnabled: Boolean,
    onMove: (Float, Float) -> Unit,
    onPress: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var knob by remember { mutableStateOf(Offset.Zero) }
    var pressed by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    Box(
        modifier = modifier
            .size(side)
            .graphicsLayer { alpha = opacity }
            .pointerInput(deadzone, sensitivity) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    if (hapticsEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onPress(true)
                    val activeId = down.id
                    applyPointer(down.position, size, deadzone, sensitivity) { k, x, y ->
                        knob = k
                        onMove(x, y)
                    }
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change: PointerInputChange =
                            event.changes.firstOrNull { it.id == activeId } ?: continue
                        if (change.changedToUp()) break
                        applyPointer(change.position, size, deadzone, sensitivity) { k, x, y ->
                            knob = k
                            onMove(x, y)
                        }
                        change.consume()
                    }
                    knob = Offset.Zero
                    onMove(0f, 0f)
                    onPress(false)
                    pressed = false
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val center = this.center
            val ringRadius = this.size.minDimension / 2f - 4.dp.toPx()
            drawCircle(
                color = SurfaceElevated.copy(alpha = 0.55f),
                radius = ringRadius,
                center = center
            )
            drawCircle(
                color = if (pressed) Accent.copy(alpha = 0.8f) else BorderGlow,
                radius = ringRadius,
                center = center,
                style = Stroke(width = 1.5.dp.toPx())
            )
            drawLine(
                color = BorderGlow.copy(alpha = 0.6f),
                start = Offset(center.x - ringRadius, center.y),
                end = Offset(center.x + ringRadius, center.y),
                strokeWidth = 1.dp.toPx()
            )
            drawLine(
                color = BorderGlow.copy(alpha = 0.6f),
                start = Offset(center.x, center.y - ringRadius),
                end = Offset(center.x, center.y + ringRadius),
                strokeWidth = 1.dp.toPx()
            )
            val knobRadius = ringRadius * 0.42f
            drawCircle(
                color = (if (pressed) Accent else SurfaceElevated).copy(alpha = 0.92f),
                radius = knobRadius,
                center = center + knob
            )
            drawCircle(
                color = if (pressed) Accent else BorderGlow,
                radius = knobRadius,
                center = center + knob,
                style = Stroke(width = 1.dp.toPx())
            )
        }
    }
}

private inline fun applyPointer(
    position: Offset,
    size: IntSize,
    deadzone: Float,
    sensitivity: Float,
    onResult: (Offset, Float, Float) -> Unit
) {
    val radius = minOf(size.width, size.height) / 2f
    if (radius <= 0f) return
    val center = Offset(size.width / 2f, size.height / 2f)
    val dx = (position.x - center.x) / radius
    val dy = (position.y - center.y) / radius
    val (dzx, dzy) = StickMath.radialDeadzone(dx, dy, deadzone)
    val nx = StickMath.responseCurve(dzx, sensitivity)
    val ny = StickMath.responseCurve(dzy, sensitivity)
    val knob = Offset(nx, ny) * (radius * 0.82f)
    onResult(knob, nx, ny)
}
