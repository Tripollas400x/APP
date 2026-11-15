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
import com.phantompad.app.ui.theme.Accent
import com.phantompad.app.ui.theme.BorderGlow
import com.phantompad.app.ui.theme.SurfaceElevated

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
    modifier: Modifier = Modifier
) {
    var pressedDirs by remember { mutableStateOf(setOf<ButtonId>()) }
    val haptic = LocalHapticFeedback.current

    Box(
        modifier = modifier
            .size(side)
            .graphicsLayer { alpha = opacity }
            .pointerInput(hapticsEnabled) {
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
            }
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
    val w = size.width
    val arm = w * 0.30f
    val thickness = w * 0.36f
    val center = this.center
    val radius = CornerRadius(10.dp.toPx())

    fun armColor(active: Boolean) =
        if (active) Accent.copy(alpha = 0.35f) else SurfaceElevated.copy(alpha = 0.6f)

    val up = ButtonId.DPAD_UP in pressedDirs
    val down = ButtonId.DPAD_DOWN in pressedDirs
    val left = ButtonId.DPAD_LEFT in pressedDirs
    val right = ButtonId.DPAD_RIGHT in pressedDirs

    drawRoundRect(
        color = armColor(up),
        topLeft = Offset(center.x - thickness / 2f, center.y - arm),
        size = Size(thickness, arm + thickness / 2f),
        cornerRadius = radius
    )
    drawRoundRect(
        color = armColor(down),
        topLeft = Offset(center.x - thickness / 2f, center.y - thickness / 2f),
        size = Size(thickness, arm + thickness / 2f),
        cornerRadius = radius
    )
    drawRoundRect(
        color = armColor(left),
        topLeft = Offset(center.x - arm, center.y - thickness / 2f),
        size = Size(arm + thickness / 2f, thickness),
        cornerRadius = radius
    )
    drawRoundRect(
        color = armColor(right),
        topLeft = Offset(center.x - thickness / 2f, center.y - thickness / 2f),
        size = Size(arm + thickness / 2f, thickness),
        cornerRadius = radius
    )
    drawCircle(
        color = BorderGlow,
        radius = thickness * 0.22f,
        center = center,
        style = Stroke(1.dp.toPx())
    )
}
