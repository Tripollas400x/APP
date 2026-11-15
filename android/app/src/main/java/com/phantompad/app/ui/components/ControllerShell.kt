package com.phantompad.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke

/** Carcasa del mando dibujada dentro de la misma zona que sus controles. */
@Composable
fun ControllerShell(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val shell = Path().apply {
            moveTo(w * .12f, h * .23f)
            cubicTo(w * .23f, h * .20f, w * .31f, h * .30f, w * .37f, h * .30f)
            lineTo(w * .63f, h * .30f)
            cubicTo(w * .69f, h * .30f, w * .77f, h * .20f, w * .88f, h * .23f)
            cubicTo(w * .96f, h * .25f, w * .98f, h * .45f, w * .99f, h * .75f)
            cubicTo(w, h * .95f, w * .94f, h * .99f, w * .89f, h * .96f)
            cubicTo(w * .83f, h * .93f, w * .80f, h * .86f, w * .74f, h * .86f)
            lineTo(w * .26f, h * .86f)
            cubicTo(w * .20f, h * .86f, w * .17f, h * .93f, w * .11f, h * .96f)
            cubicTo(w * .06f, h * .99f, 0f, h * .95f, w * .01f, h * .75f)
            cubicTo(w * .02f, h * .45f, w * .04f, h * .25f, w * .12f, h * .23f)
            close()
        }
        drawPath(shell, Brush.verticalGradient(
            0f to Color(0xFFFCFCFC),
            .68f to Color(0xFFCBCBCE),
            1f to Color(0xFF818187)
        ))
        drawPath(shell, Color.White.copy(alpha = .38f), style = Stroke(1f))

        val core = Path().apply {
            moveTo(w * .31f, h * .27f)
            cubicTo(w * .40f, h * .26f, w * .60f, h * .26f, w * .69f, h * .27f)
            cubicTo(w * .76f, h * .36f, w * .78f, h * .66f, w * .76f, h * .83f)
            cubicTo(w * .74f, h * .99f, w * .26f, h * .99f, w * .24f, h * .83f)
            cubicTo(w * .22f, h * .66f, w * .24f, h * .36f, w * .31f, h * .27f)
            close()
        }
        drawPath(core, Brush.verticalGradient(listOf(Color(0xFF282829), Color(0xFF0C0C0D))))
        drawPath(core, Color.White.copy(alpha = .12f), style = Stroke(1.5f))
        drawRoundRect(
            Color(0xFF9CA8BA), Offset(w * .465f, h * .475f), Size(w * .07f, maxOf(2f, h * .012f)),
            CornerRadius(h * .006f)
        )
    }
}
