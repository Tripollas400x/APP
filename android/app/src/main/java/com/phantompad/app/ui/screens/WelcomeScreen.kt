package com.phantompad.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.phantompad.app.ui.theme.Accent
import com.phantompad.app.ui.theme.CircRed
import com.phantompad.app.ui.theme.CrossBlue
import com.phantompad.app.ui.theme.DeepBlack
import com.phantompad.app.ui.theme.SqPink
import com.phantompad.app.ui.theme.TextPrimary
import com.phantompad.app.ui.theme.TextSecondary
import com.phantompad.app.ui.theme.TriGreen

@Composable
fun WelcomeScreen(onStart: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(DeepBlack),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LogoDot(TriGreen)
                LogoDot(CircRed)
                LogoDot(CrossBlue)
                LogoDot(SqPink)
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "PHANTOM PAD",
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Tu teléfono se convierte en un mando Bluetooth para tu PC.\nSin internet, sin Wi-Fi, sin cables.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(32.dp))
            Button(
                onClick = onStart,
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = TextPrimary)
            ) {
                Text("COMENZAR", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(24.dp))
            Text(
                "v0.1 - Recomendado: PhantomPadReceiver.exe en el PC (mando Xbox 360 / DS4 virtual)",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun LogoDot(color: androidx.compose.ui.graphics.Color) {
    Box(
        Modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.15f))
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(color = color, radius = size.minDimension / 2f - 2.dp.toPx(), center = Offset(size.width / 2f, size.height / 2f), style = Stroke(2.dp.toPx()))
        }
    }
}
