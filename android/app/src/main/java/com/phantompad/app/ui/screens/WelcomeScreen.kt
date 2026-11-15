package com.phantompad.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.phantompad.app.data.AppSettings
import com.phantompad.app.data.ProfileId
import com.phantompad.app.gamepad.GamepadState
import com.phantompad.app.gamepad.LayoutProfiles
import com.phantompad.app.ui.components.FaceStyle
import com.phantompad.app.ui.components.drawFace
import com.phantompad.app.ui.theme.BorderGlow
import com.phantompad.app.ui.theme.DeepBlack
import com.phantompad.app.ui.theme.SurfaceDark
import com.phantompad.app.ui.theme.TextSecondary

@Composable
fun WelcomeScreen(onStart: () -> Unit) {
    val previewState = remember { GamepadState() }
    val previewSettings = remember { AppSettings() }
    val layout = remember { LayoutProfiles.default(ProfileId.PS_CLASSIC) }

    Row(
        Modifier.fillMaxSize().background(DeepBlack).padding(horizontal = 28.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(.82f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                FaceStyle.entries.forEach { style ->
                    Canvas(Modifier.size(22.dp)) { drawFace(style, false) }
                }
            }
            Text("Phantom\nPad", style = MaterialTheme.typography.displaySmall)
            Text("Usa tu teléfono como mando Bluetooth para jugar en Windows.",
                style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            Button(onClick = onStart) {
                Text("Conectar al PC")
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.padding(start = 8.dp).size(18.dp))
            }
        }
        val previewShape = RoundedCornerShape(28.dp)
        Box(
            Modifier.weight(1.3f).fillMaxWidth().aspectRatio(2.08f)
                .background(SurfaceDark, previewShape)
                .border(1.dp, BorderGlow, previewShape)
                .padding(13.dp)
        ) {
            PadLayoutView(previewState, previewSettings, layout, Modifier.fillMaxSize(), interactive = false)
        }
    }
}
