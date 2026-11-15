package com.phantompad.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.phantompad.app.PadViewModel
import com.phantompad.app.data.ProfileId
import com.phantompad.app.data.TransportModeWire
import com.phantompad.app.ui.theme.Accent
import com.phantompad.app.ui.theme.DeepBlack
import com.phantompad.app.ui.theme.SurfaceDark
import com.phantompad.app.ui.theme.TextPrimary
import com.phantompad.app.ui.theme.TextSecondary

@Composable
fun SettingsScreen(vm: PadViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .background(DeepBlack)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver", tint = TextPrimary)
            }
            Text("Ajustes", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
        }

        SectionCard("Perfil activo") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProfileId.entries.forEach { profile ->
                    FilterChip(
                        selected = settings.activeProfile == profile,
                        onClick = { vm.updateSettings { it.copy(activeProfile = profile) } },
                        label = { Text(profile.title) }
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Los perfiles se guardan localmente. Edítalos desde el modo mando con el icono de lápiz.",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }

        SectionCard("Rendimiento") {
            LabeledSlider(
                label = "Frecuencia de envío",
                value = settings.sendRateHz.toFloat(),
                range = 30f..240f,
                display = "${settings.sendRateHz} Hz",
                onChange = { v -> vm.updateSettings { s -> s.copy(sendRateHz = v.roundToInt()) } }
            )
            LabeledSlider(
                label = "Zona muerta de sticks",
                value = settings.deadzone,
                range = 0f..0.30f,
                display = "%.0f%%".format(settings.deadzone * 100),
                onChange = { v -> vm.updateSettings { s -> s.copy(deadzone = v) } }
            )
            LabeledSlider(
                label = "Sensibilidad",
                value = settings.sensitivity,
                range = 0.6f..1.6f,
                display = "%.2fx".format(settings.sensitivity),
                onChange = { v -> vm.updateSettings { s -> s.copy(sensitivity = v) } }
            )
        }

        SectionCard("Método de conexión") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.transportMode == TransportModeWire.RECEIVER,
                    onClick = { vm.updateSettings { it.copy(transportMode = TransportModeWire.RECEIVER) } },
                    label = { Text("Receptor Windows") }
                )
                FilterChip(
                    selected = settings.transportMode == TransportModeWire.HID,
                    onClick = { vm.updateSettings { it.copy(transportMode = TransportModeWire.HID) } },
                    label = { Text("HID directo") }
                )
            }
        }

        SectionCard("Extras") {
            SwitchRow("Giroscopio (envío preparado)", settings.gyroEnabled) { checked ->
                vm.updateSettings { s -> s.copy(gyroEnabled = checked) }
            }
            SwitchRow("Vibración y feedback", settings.vibrationEnabled) { checked ->
                vm.updateSettings { s -> s.copy(vibrationEnabled = checked) }
            }
            SwitchRow("Mantener pantalla encendida", settings.keepAwake) { checked ->
                vm.updateSettings { s -> s.copy(keepAwake = checked) }
            }
            SwitchRow("Reconexión automática", settings.autoReconnect) { checked ->
                vm.updateSettings { s -> s.copy(autoReconnect = checked) }
            }
            SwitchRow("Modo ahorro energético (60 Hz máx.)", settings.energySaver) { checked ->
                vm.updateSettings { s -> s.copy(energySaver = checked) }
            }
        }

        Text(
            "Phantom Pad v0.1 - la latencia mostrada es el RTT real medido con PING/PONG.",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Accent)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    onChange: (Float) -> Unit
) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = TextPrimary, modifier = Modifier.weight(1f))
            Text(display, style = MaterialTheme.typography.labelSmall, color = Accent)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TextPrimary, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
