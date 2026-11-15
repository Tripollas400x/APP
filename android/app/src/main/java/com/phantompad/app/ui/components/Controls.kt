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
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.phantompad.app.protocol.ButtonId
import com.phantompad.app.ui.theme.Accent
import com.phantompad.app.ui.theme.BorderGlow
import com.phantompad.app.ui.theme.DeepBlack
import com.phantompad.app.ui.theme.CircRed
import com.phantompad.app.ui.theme.CrossBlue
import com.phantompad.app.ui.theme.SqPink
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
    interactive: Boolean = true,
    content: @Composable BoxScope.(pressed: Boolean) -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val changeState by rememberUpdatedState(onChange)
    val gesture = if (interactive) Modifier.pointerInput(hapticsEnabled) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            pressed = true
            try {
                changeState(true)
                if (hapticsEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                down.consume()
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (change.changedToUp()) break
                    if (change.position.x !in 0f..size.width.toFloat() ||
                        change.position.y !in 0f..size.height.toFloat()
                    ) break
                    change.consume()
                }
            } finally {
                pressed = false
                changeState(false)
            }
        }
    } else Modifier
    Box(modifier.then(gesture)) {
        val scale by animateFloatAsState(
            targetValue = if (pressed) .93f else 1f,
            animationSpec = tween(if (pressed) 70 else 110),
            label = "holdScale"
        )
        Box(Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale }) {
            content(pressed)
        }
    }
}

// ----------------------------------------------------------- botones de acción

enum class FaceStyle(val color: Color, val label: String) {
    TRIANGLE(TriGreen, "Triángulo"),
    CIRCLE(CircRed, "Círculo"),
    CROSS(CrossBlue, "Cruz"),
    SQUARE(SqPink, "Cuadrado")
}

@Composable
fun FaceButton(
    style: FaceStyle,
    side: Dp,
    opacity: Float,
    hapticsEnabled: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    interactive: Boolean = true
) {
    HoldButton(
        onChange = onChange,
        hapticsEnabled = hapticsEnabled,
        interactive = interactive,
        modifier = modifier
            .size(side)
            .semantics { contentDescription = style.label }
            .graphicsLayer { alpha = opacity }
    ) { pressed ->
        Canvas(Modifier.fillMaxSize()) {
            drawFace(style, pressed)
        }
    }
}

internal fun DrawScope.drawFace(style: FaceStyle, pressed: Boolean) {
    val c = center
    val r = size.minDimension * .42f
    val stroke = (size.minDimension * .043f).coerceIn(1.3.dp.toPx(), 3.dp.toPx())
    val trim = if (pressed) style.color else Color(0xFF65656A)
    drawCircle(Color.Black.copy(alpha = .24f), r * 1.07f, c + Offset(0f, r * .11f))
    drawCircle(
        Brush.verticalGradient(listOf(Color(0xFF444447), Color(0xFF121213)), c.y - r, c.y + r),
        r, c
    )
    drawCircle(trim, r, c, style = Stroke(if (pressed) 2.dp.toPx() else 1.dp.toPx()))
    drawCircle(Color.White.copy(alpha = .13f), r * .82f, c, style = Stroke(1.dp.toPx()))
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
            drawLine(style.color, Offset(c.x - d, c.y - d), Offset(c.x + d, c.y + d), stroke, StrokeCap.Round)
            drawLine(style.color, Offset(c.x - d, c.y + d), Offset(c.x + d, c.y - d), stroke, StrokeCap.Round)
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
    modifier: Modifier = Modifier,
    interactive: Boolean = true
) {
    Box(
        modifier = modifier
            .size(side)
            .graphicsLayer { alpha = opacity }
    ) {
        val buttonSize = side * 0.435f
        val spread = side * 0.282f
        FaceButton(
            FaceStyle.TRIANGLE, buttonSize, 1f, hapticsEnabled,
            { setButton(ButtonId.TRIANGLE, it) },
            Modifier.align(Alignment.Center).offset(y = -spread), interactive
        )
        FaceButton(
            FaceStyle.CIRCLE, buttonSize, 1f, hapticsEnabled,
            { setButton(ButtonId.CIRCLE, it) },
            Modifier.align(Alignment.Center).offset(x = spread), interactive
        )
        FaceButton(
            FaceStyle.CROSS, buttonSize, 1f, hapticsEnabled,
            { setButton(ButtonId.CROSS, it) },
            Modifier.align(Alignment.Center).offset(y = spread), interactive
        )
        FaceButton(
            FaceStyle.SQUARE, buttonSize, 1f, hapticsEnabled,
            { setButton(ButtonId.SQUARE, it) },
            Modifier.align(Alignment.Center).offset(x = -spread), interactive
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
    modifier: Modifier = Modifier,
    interactive: Boolean = true
) {
    HoldButton(
        onChange = onChange,
        hapticsEnabled = hapticsEnabled,
        interactive = interactive,
        modifier = modifier
            .size(width, height)
            .graphicsLayer { alpha = opacity }
    ) { pressed ->
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(percent = 28))
                .background(if (pressed) Accent else Color(0xFF2D2D30))
                .border(1.dp, if (pressed) Accent else BorderGlow, RoundedCornerShape(percent = 28)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                color = if (pressed) DeepBlack else TextPrimary
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
    modifier: Modifier = Modifier,
    interactive: Boolean = true
) {
    var value by remember { mutableStateOf(0f) }
    val haptic = LocalHapticFeedback.current
    val changeState by rememberUpdatedState(onChange)
    val gesture = if (interactive) Modifier.pointerInput(hapticsEnabled) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            value = 1f
            try {
                changeState(1f)
                if (hapticsEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                down.consume()
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (change.changedToUp()) break
                    val travel = (change.position.y - down.position.y) / (size.height * .7f)
                    value = (1f - travel).coerceIn(0f, 1f)
                    changeState(value)
                    change.consume()
                }
            } finally {
                value = 0f
                changeState(0f)
            }
        }
    } else Modifier
    Box(modifier.size(width, height).graphicsLayer { alpha = opacity }.then(gesture)) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = CornerRadius(12.dp.toPx())
            drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF444447), Color(0xFF181819))), cornerRadius = radius)
            drawRoundRect(if (value > 0f) Accent else BorderGlow, cornerRadius = radius, style = Stroke(1.dp.toPx()))
            val track = size.width * .72f
            val start = Offset((size.width - track) / 2f, size.height * .79f)
            drawLine(TextSecondary.copy(alpha = .33f), start, start.copy(x = start.x + track), 2.dp.toPx(), StrokeCap.Round)
            if (value > 0f) drawLine(Accent, start, start.copy(x = start.x + track * value), 2.dp.toPx(), StrokeCap.Round)
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.offset(y = (-2).dp),
                color = TextPrimary
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
    modifier: Modifier = Modifier,
    interactive: Boolean = true
) {
    HoldButton(
        onChange = onChange,
        hapticsEnabled = hapticsEnabled,
        interactive = interactive,
        modifier = modifier
            .size(width, height)
            .graphicsLayer { alpha = opacity }
    ) { pressed ->
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(percent = 48))
                .background(if (pressed) Accent else DeepBlack)
                .border(1.dp, if (pressed) Accent else BorderGlow, RoundedCornerShape(percent = 48)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = if (pressed) DeepBlack else TextPrimary
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
    modifier: Modifier = Modifier,
    interactive: Boolean = true
) {
    HoldButton(
        onChange = onContact,
        hapticsEnabled = false,
        interactive = interactive,
        modifier = modifier.semantics { contentDescription = "Panel táctil" }
            .size(width, height)
            .graphicsLayer { alpha = opacity }
    ) { pressed ->
        Canvas(Modifier.fillMaxSize()) {
            val corner = CornerRadius(12.dp.toPx())
            drawRoundRect(
                Brush.verticalGradient(listOf(Color(0xFF29292B), Color(0xFF141415))),
                cornerRadius = corner
            )
            val mark = if (pressed) Accent else TextSecondary.copy(alpha = .32f)
            for (row in 1..4) for (column in 1..13) {
                drawCircle(mark, 0.8.dp.toPx(), Offset(size.width * column / 14f, size.height * row / 5f))
            }
            drawRoundRect(if (pressed) Accent else BorderGlow, cornerRadius = corner, style = Stroke(1.4.dp.toPx()))
        }
    }
}
