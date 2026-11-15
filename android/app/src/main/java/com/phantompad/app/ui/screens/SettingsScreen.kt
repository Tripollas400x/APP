package com.phantompad.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import com.phantompad.app.ui.theme.BorderGlow
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: PadViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .background(DeepBlack)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver", tint = TextPrimary)
            }
            Text("Ajustes", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                SectionCard("Perfil activo") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        "Personaliza la posición, el tamaño y la opacidad desde el editor del mando.",
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

            }
            Column(Modifier.weight(1f)) {
                SectionCard("Método de conexión") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

                SectionCard("Preferencias de juego") {
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
                    SwitchRow("L3/R3 al pulsar el stick", settings.stickClickEnabled) { checked ->
                        vm.updateSettings { s -> s.copy(stickClickEnabled = checked) }
                    }
                    SwitchRow("Modo ahorro energético (60 Hz máx.)", settings.energySaver) { checked ->
                        vm.updateSettings { s -> s.copy(energySaver = checked) }
                    }
                }

            }
        }
        Text(
            "Phantom Pad v0.3.0",
            style = MaterialTheme.typography.labelSmall, color = TextSecondary,
            modifier = Modifier.padding(vertical = 8.dp)
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = BorderStroke(1.dp, BorderGlow),
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
