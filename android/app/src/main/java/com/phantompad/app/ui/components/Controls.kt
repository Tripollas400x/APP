package com.phantompad.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.phantompad.app.protocol.ButtonId
import com.phantompad.app.ui.theme.Accent
import com.phantompad.app.ui.theme.BorderGlow
import com.phantompad.app.ui.theme.CircRed
import com.phantompad.app.ui.theme.CrossBlue
import com.phantompad.app.ui.theme.SqPink
import com.phantompad.app.ui.theme.SurfaceElevated
import com.phantompad.app.ui.theme.TextPrimary
import com.phantompad.app.ui.theme.TextSecondary
import com.phantompad.app.ui.theme.TriGreen

/**
 * Botón "mantener pulsado" con multitáctil real (sigue su propio dedo),
 * animación de pulsación y liberación al deslizar fuera del área.
 */
@Composable
fun HoldButton(
    onChange: (Boolean) -> Unit,
    hapticsEnabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.(pressed: Boolean) -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    Box(
        modifier = modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    onChange(true)
                    if (hapticsEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.changedToUp()) break
                        val inside = change.position.x in 0f..size.width.toFloat() &&
                            change.position.y in 0f..size.height.toFloat()
                        if (!inside) break
                        change.consume()
                    }
                    pressed = false
                    onChange(false)
                }
            }
    ) {
        val scale by animateFloatAsState(
            targetValue = if (pressed) 0.9f else 1f,
            label = "holdScale"
        )
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { scaleX = scale; scaleY = scale }
        ) {
            content(pressed)
        }
    }
}

// ----------------------------------------------------------- botones de acción

enum class FaceStyle(val color: Color) {
    TRIANGLE(TriGreen),
    CIRCLE(CircRed),
    CROSS(CrossBlue),
    SQUARE(SqPink)
}

@Composable
fun FaceButton(
    style: FaceStyle,
    side: Dp,
    opacity: Float,
    hapticsEnabled: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    HoldButton(
        onChange = onChange,
        hapticsEnabled = hapticsEnabled,
        modifier = modifier
            .size(side)
            .graphicsLayer { alpha = opacity }
    ) { pressed ->
        Canvas(Modifier.fillMaxSize()) {
            drawFace(style, pressed)
        }
    }
}

private fun DrawScope.drawFace(style: FaceStyle, pressed: Boolean) {
    val c = center
    val r = size.minDimension * 0.32f
    val stroke = 3.dp.toPx()
    if (pressed) {
        drawCircle(
            color = style.color.copy(alpha = 0.18f),
            radius = r * 1.65f,
            center = c
        )
    }
    drawCircle(
        color = Color.White.copy(alpha = if (pressed) 0.10f else 0.06f),
        radius = r * 1.35f,
        center = c
    )
    drawCircle(
        color = style.color.copy(alpha = if (pressed) 1f else 0.85f),
        radius = r * 1.35f,
        center = c,
        style = Stroke(stroke)
    )
    when (style) {
        FaceStyle.TRIANGLE -> {
            val path = Path()
            val h = r * 0.62f
            path.moveTo(c.x, c.y - h)
            path.lineTo(c.x + h * 0.9f, c.y + h * 0.62f)
            path.lineTo(c.x - h * 0.9f, c.y + h * 0.62f)
            path.close()
            drawPath(path, style.color, style = Stroke(stroke))
        }
        FaceStyle.CIRCLE -> {
            drawCircle(style.color, radius = r * 0.52f, center = c, style = Stroke(stroke))
        }
        FaceStyle.CROSS -> {
            val d = r * 0.42f
            drawLine(style.color, Offset(c.x - d, c.y - d), Offset(c.x + d, c.y + d), stroke)
            drawLine(style.color, Offset(c.x - d, c.y + d), Offset(c.x + d, c.y - d), stroke)
        }
        FaceStyle.SQUARE -> {
            val h = r * 0.46f
            drawRect(
                color = style.color,
                topLeft = Offset(c.x - h, c.y - h),
                size = androidx.compose.ui.geometry.Size(h * 2f, h * 2f),
                style = Stroke(stroke)
            )
        }
    }
}

/** Grupo de 4 botones en diamante: triángulo arriba, círculo derecha, cruz abajo, cuadrado izquierda. */
@Composable
fun FaceCluster(
    side: Dp,
    opacity: Float,
    hapticsEnabled: Boolean,
    setButton: (ButtonId, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(side)
            .graphicsLayer { alpha = opacity }
    ) {
        val buttonSize = side * 0.40f
        val spread = side * 0.28f
        FaceButton(
            FaceStyle.TRIANGLE, buttonSize, 1f, hapticsEnabled,
            { setButton(ButtonId.TRIANGLE, it) },
            Modifier.align(Alignment.Center).offset(y = -spread)
        )
        FaceButton(
            FaceStyle.CIRCLE, buttonSize, 1f, hapticsEnabled,
            { setButton(ButtonId.CIRCLE, it) },
            Modifier.align(Alignment.Center).offset(x = spread)
        )
        FaceButton(
            FaceStyle.CROSS, buttonSize, 1f, hapticsEnabled,
            { setButton(ButtonId.CROSS, it) },
            Modifier.align(Alignment.Center).offset(y = spread)
        )
        FaceButton(
            FaceStyle.SQUARE, buttonSize, 1f, hapticsEnabled,
            { setButton(ButtonId.SQUARE, it) },
            Modifier.align(Alignment.Center).offset(x = -spread)
        )
    }
}

// ------------------------------------------------------------------- bumpers

@Composable
fun BumperButton(
    label: String,
    width: Dp,
    height: Dp,
    opacity: Float,
    hapticsEnabled: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    HoldButton(
        onChange = onChange,
        hapticsEnabled = hapticsEnabled,
        modifier = modifier
            .size(width, height)
            .graphicsLayer { alpha = opacity }
    ) { pressed ->
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(percent = 50))
                .background(if (pressed) Accent.copy(alpha = 0.30f) else SurfaceElevated.copy(alpha = 0.85f))
                .border(1.dp, if (pressed) Accent else BorderGlow, RoundedCornerShape(percent = 50)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                color = if (pressed) TextPrimary else TextSecondary
            )
        }
    }
}

// ------------------------------------------------------- gatillos analógicos

/**
 * Gatillo analógico: al tocar vale 1.0; arrastrar hacia abajo lo reduce
 * de forma continua (simulación de recorrido por gesto, como pide la spec).
 */
@Composable
fun TriggerButton(
    label: String,
    width: Dp,
    height: Dp,
    opacity: Float,
    hapticsEnabled: Boolean,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var value by remember { mutableStateOf(0f) }
    val haptic = LocalHapticFeedback.current
    Box(
        modifier = modifier
            .size(width, height)
            .graphicsLayer { alpha = opacity }
            .pointerInput(hapticsEnabled) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    value = 1f
                    onChange(1f)
                    if (hapticsEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    down.consume()
                    val startY = down.position.y
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.changedToUp()) break
                        val travel = (change.position.y - startY) / (size.height * 0.7f)
                        val v = (1f - travel).coerceIn(0f, 1f)
                        value = v
                        onChange(v)
                        change.consume()
                    }
                    value = 0f
                    onChange(0f)
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRoundRect(
                color = SurfaceElevated.copy(alpha = 0.85f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx())
            )
            val fillH = size.height * value
            if (fillH > 1f) {
                drawRoundRect(
                    color = Accent.copy(alpha = 0.45f),
                    topLeft = Offset(0f, size.height - fillH),
                    size = androidx.compose.ui.geometry.Size(size.width, fillH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx())
                )
            }
            drawRoundRect(
                color = if (value > 0f) Accent else BorderGlow,
                style = Stroke(1.dp.toPx()),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx())
            )
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                color = if (value > 0f) TextPrimary else TextSecondary
            )
        }
    }
}

// ------------------------------------------------------- píldoras centrales

@Composable
fun PillButton(
    label: String,
    width: Dp,
    height: Dp,
    opacity: Float,
    hapticsEnabled: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    HoldButton(
        onChange = onChange,
        hapticsEnabled = hapticsEnabled,
        modifier = modifier
            .size(width, height)
            .graphicsLayer { alpha = opacity }
    ) { pressed ->
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(percent = 50))
                .background(if (pressed) Accent.copy(alpha = 0.35f) else SurfaceElevated.copy(alpha = 0.9f))
                .border(1.dp, if (pressed) Accent else BorderGlow, RoundedCornerShape(percent = 50)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = if (pressed) TextPrimary else TextSecondary
            )
        }
    }
}

// ------------------------------------------------------------- panel táctil

/** Panel táctil central: click = botón TOUCHPAD, contacto = flag touch del protocolo. */
@Composable
fun TouchpadArea(
    width: Dp,
    height: Dp,
    opacity: Float,
    onContact: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    HoldButton(
        onChange = onContact,
        hapticsEnabled = false,
        modifier = modifier
            .size(width, height)
            .graphicsLayer { alpha = opacity }
    ) { pressed ->
        Canvas(Modifier.fillMaxSize()) {
            drawRoundRect(
                color = SurfaceElevated.copy(alpha = if (pressed) 0.55f else 0.35f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx())
            )
            drawRoundRect(
                color = if (pressed) Accent else BorderGlow.copy(alpha = 0.7f),
                style = Stroke(1.dp.toPx()),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx())
            )
        }
    }
}
