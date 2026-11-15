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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.phantompad.app.protocol.ButtonId
import com.phantompad.app.ui.theme.BorderGlow
import com.phantompad.app.ui.theme.DirectionBlue

/**
 * Cruceta direccional con zonas cruzadas y soporte de diagonales (dos
 * direcciones a la vez). Cada dirección es un bit independiente.
 */
@Composable
fun DPad(
    side: Dp,
    opacity: Float,
    hapticsEnabled: Boolean,
    onDirection: (ButtonId, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    interactive: Boolean = true
) {
    var pressedDirs by remember { mutableStateOf(setOf<ButtonId>()) }
    val haptic = LocalHapticFeedback.current

    Box(
        modifier = modifier
            .size(side)
            .graphicsLayer { alpha = opacity }
            .then(if (interactive) Modifier.pointerInput(hapticsEnabled) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (hapticsEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    // Cada gesto sigue SIEMPRE al puntero que lo inició (por id):
                    // un segundo dedo sobre la cruceta no secuestra el control.
                    val activeId = down.id
                    var dirs = zonesFor(down.position, size)
                    dirs.forEach { onDirection(it, true) }
                    pressedDirs = dirs
                    down.consume()
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val tracked = event.changes.firstOrNull { it.id == activeId } ?: break
                            val next = zonesFor(tracked.position, size)
                            (next - dirs).forEach { onDirection(it, true) }
                            (dirs - next).forEach { onDirection(it, false) }
                            dirs = next
                            pressedDirs = next
                            if (tracked.changedToUp()) break
                            tracked.consume()
                        }
                    } finally {
                        // Al soltar, cancelarse o perder el dedo: nada queda presionado.
                        dirs.forEach { onDirection(it, false) }
                        pressedDirs = emptySet()
                    }
                }
            } else Modifier)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCross(pressedDirs)
        }
    }
}

private fun zonesFor(position: Offset, size: androidx.compose.ui.unit.IntSize): Set<ButtonId> {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val dx = position.x - cx
    val dy = position.y - cy
    val threshold = minOf(size.width, size.height).coerceAtLeast(1) * 0.18f
    val result = mutableSetOf<ButtonId>()
    if (dy < -threshold) result += ButtonId.DPAD_UP
    if (dy > threshold) result += ButtonId.DPAD_DOWN
    if (dx < -threshold) result += ButtonId.DPAD_LEFT
    if (dx > threshold) result += ButtonId.DPAD_RIGHT
    return result
}

private fun DrawScope.drawCross(pressedDirs: Set<ButtonId>) {
    val c = center
    val unit = size.minDimension
    drawCircle(Color(0xFF242426), unit * .48f)
    drawCircle(BorderGlow, unit * .47f, style = Stroke(1.dp.toPx()))
    DPadDirections.forEachIndexed { index, id ->
        val pressed = id in pressedDirs
        rotate(index * 90f, c) {
            val start = Offset(c.x - unit * .153f, c.y - unit * .425f)
            val size = Size(unit * .306f, unit * .28f)
            val curve = CornerRadius(unit * .06f)
            drawRoundRect(
                Brush.verticalGradient(
                    if (pressed) listOf(Color(0xFF373E49), Color(0xFF252A32))
                    else listOf(Color(0xFF474749), Color(0xFF171718)),
                    start.y, start.y + size.height
                ),
                start, size, curve
            )
            drawRoundRect(
                if (pressed) DirectionBlue else BorderGlow, start, size, curve,
                style = Stroke(1.dp.toPx())
            )
            val arrowY = c.y - unit * .29f
            val arrow = Path().apply {
                moveTo(c.x, arrowY - unit * .047f)
                lineTo(c.x + unit * .047f, arrowY + unit * .027f)
                lineTo(c.x - unit * .047f, arrowY + unit * .027f)
                close()
            }
            drawPath(arrow, DirectionBlue.copy(alpha = if (pressed) 1f else .73f))
        }
    }
}

private val DPadDirections = listOf(ButtonId.DPAD_UP, ButtonId.DPAD_RIGHT, ButtonId.DPAD_DOWN, ButtonId.DPAD_LEFT)
