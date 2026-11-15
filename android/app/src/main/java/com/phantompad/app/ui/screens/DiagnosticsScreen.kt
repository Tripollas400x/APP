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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phantompad.app.PadViewModel
import com.phantompad.app.protocol.ButtonId
import com.phantompad.app.transport.TransportState
import com.phantompad.app.ui.theme.Accent
import com.phantompad.app.ui.theme.DeepBlack
import com.phantompad.app.ui.theme.SurfaceDark
import com.phantompad.app.ui.theme.TextPrimary
import com.phantompad.app.ui.theme.TextSecondary
import kotlinx.coroutines.delay

private fun formatMs(v: Double?): String = if (v == null) "n/d" else "%.1f ms".format(v)

/**
 * Diagnóstico: estado del transporte, medición de latencia real (RTT),
 * botones presionados, valores de ejes y registro de eventos.
 */
@Composable
fun DiagnosticsScreen(vm: PadViewModel, onBack: () -> Unit) {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(150)
            tick++
        }
    }

    val hubStats = vm.hub.stats.collectAsState().value
    val latencyStats = vm.latency.stats.collectAsState().value
    val transportState = vm.transportState.collectAsState().value
    val device = vm.connectedDevice.collectAsState().value
    val battery = vm.battery.percent.collectAsState().value
    val log = vm.log.entries.collectAsState().value
    val settings = vm.settings.collectAsState().value
    val snapshot = remember(tick) { vm.state.snapshot() }

    Column(
        Modifier
            .fillMaxSize()
            .background(DeepBlack)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver", tint = TextPrimary)
            }
            Text("Diagnóstico", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
        }

        LazyColumn(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                InfoCard(title = "Conexión") {
                    InfoRow("Transporte", if (settings.transportMode == com.phantompad.app.data.TransportModeWire.RECEIVER) "RFCOMM/SPP a receptor Windows" else "HID directo")
                    InfoRow("Dispositivo", device?.name ?: settings.lastDeviceName ?: "n/d")
                    InfoRow("Estado", when (transportState) {
                        TransportState.CONNECTED -> "Conectado"
                        TransportState.CONNECTING -> "Conectando"
                        TransportState.FAILED -> "Fallo"
                        TransportState.DISCONNECTED -> "Desconectado"
                    })
                    InfoRow("Batería teléfono", battery?.let { "$it%" } ?: "n/d")
                    InfoRow("Ahorro energético", if (settings.energySaver) "activo" else "no")
                }
            }
            item {
                InfoCard(title = "Latencia (RTT real)") {
                    InfoRow("Último", formatMs(latencyStats.lastMs))
                    InfoRow("Promedio", formatMs(latencyStats.avgMs))
                    InfoRow("Mínimo", formatMs(latencyStats.minMs))
                    InfoRow("Máximo", formatMs(latencyStats.maxMs))
                    InfoRow("Muestras", latencyStats.samples.toString())
                }
            }
            item {
                InfoCard(title = "Envío") {
                    InfoRow("Frecuencia objetivo", "${hubStats.targetRateHz} Hz")
                    InfoRow("Frames enviados", hubStats.framesSent.toString())
                }
            }
            item {
                InfoCard(title = "Entrada en vivo") {
                    val held = ButtonId.entries.filter { snapshot.buttons and it.bit != 0 }
                    InfoRow("Botones", if (held.isEmpty()) "ninguno" else held.joinToString { it.name })
                    InfoRow("LX / LY", "%.2f / %.2f".format(snapshot.lx, snapshot.ly))
                    InfoRow("RX / RY", "%.2f / %.2f".format(snapshot.rx, snapshot.ry))
                    InfoRow("L2 / R2", "%.2f / %.2f".format(snapshot.l2, snapshot.r2))
                    InfoRow("Touchpad", if (snapshot.touch) "contacto" else "-")
                }
            }
            item {
                InfoCard(title = "Registro") {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        log.takeLast(40).forEach { line ->
                            Text(
                                line,
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Accent)
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary, modifier = Modifier.width(150.dp))
        Text(value, style = MaterialTheme.typography.labelSmall, color = TextPrimary, modifier = Modifier.weight(1f))
    }
}
