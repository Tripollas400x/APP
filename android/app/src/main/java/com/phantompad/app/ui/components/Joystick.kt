package com.phantompad.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.phantompad.app.ui.theme.DeepBlack

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
    modifier: Modifier = Modifier,
    interactive: Boolean = true
) {
    var knob by remember { mutableStateOf(Offset.Zero) }
    var pressed by remember { mutableStateOf(false) }
    val rim by animateFloatAsState(if (pressed) 1f else .52f, tween(100), label = "stickRim")
    val haptic = LocalHapticFeedback.current
    val move by rememberUpdatedState(onMove)
    val press by rememberUpdatedState(onPress)

    Box(
        modifier = modifier
            .size(side)
            .graphicsLayer { alpha = opacity }
            .then(if (interactive) Modifier.pointerInput(deadzone, sensitivity, hapticsEnabled) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    if (hapticsEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    press(true)
                    val activeId = down.id
                    try {
                        applyPointer(down.position, size, deadzone, sensitivity) { k, x, y ->
                            knob = k
                            move(x, y)
                        }
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change: PointerInputChange =
                                event.changes.firstOrNull { it.id == activeId } ?: break
                            if (change.changedToUp()) break
                            applyPointer(change.position, size, deadzone, sensitivity) { k, x, y ->
                                knob = k
                                move(x, y)
                            }
                            change.consume()
                        }
                    } finally {
                        // Al soltar o cancelarse el gesto (salir de pantalla,
                        // desmontaje del control, pérdida de foco): el stick
                        // vuelve al centro y ningún botón queda apretado.
                        knob = Offset.Zero
                        move(0f, 0f)
                        press(false)
                        pressed = false
                    }
                }
            } else Modifier)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension * .47f
            val pad = center + knob
            drawCircle(DeepBlack, r)
            drawCircle(Color(0xFF242425), r * .84f)
            drawCircle(BorderGlow, r, style = Stroke(1.5.dp.toPx()))
            drawCircle(Color(0xFF7F7F85), r * .83f, style = Stroke(1.dp.toPx()))
            val capRadius = r * .53f
            drawCircle(Color.Black.copy(alpha = .6f), capRadius, pad + Offset(0f, r * .08f))
            drawCircle(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF56565A), Color(0xFF242426), Color(0xFF101011)),
                    center = pad - Offset(0f, capRadius * .24f), radius = capRadius * 1.27f
                ),
                capRadius, pad
            )
            drawCircle(Accent.copy(alpha = rim), capRadius, pad, style = Stroke(1.4.dp.toPx()))
            drawCircle(Accent.copy(alpha = .17f), capRadius * .78f, pad, style = Stroke(1.dp.toPx()))
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
    val knob = Offset(nx, ny) * (radius * .43f)
    onResult(knob, nx, ny)
}
