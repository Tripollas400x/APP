package com.phantompad.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phantompad.app.PadViewModel
import com.phantompad.app.bt.BtDeviceUi
import com.phantompad.app.data.TransportModeWire
import com.phantompad.app.transport.TransportState
import com.phantompad.app.ui.theme.BorderGlow
import com.phantompad.app.ui.theme.DeepBlack
import com.phantompad.app.ui.theme.ErrRed
import com.phantompad.app.ui.theme.OkGreen
import com.phantompad.app.ui.theme.SurfaceDark
import com.phantompad.app.ui.theme.SurfaceElevated
import com.phantompad.app.ui.theme.TextPrimary
import com.phantompad.app.ui.theme.TextSecondary
import com.phantompad.app.ui.theme.WarnAmber

@Composable
fun ConnectScreen(
    vm: PadViewModel,
    onEnableBluetooth: () -> Unit,
    onConnected: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val settings by vm.settings.collectAsState()
    val devices by vm.bt.devices.collectAsState()
    val btEnabled by vm.bt.enabled.collectAsState()
    val transportState by vm.transportState.collectAsState()
    val connectedDevice by vm.connectedDevice.collectAsState()
    val log by vm.log.entries.collectAsState()

    LaunchedEffect(transportState) {
        if (transportState == TransportState.CONNECTED) onConnected()
    }
    val (status, statusColor) = when {
        transportState == TransportState.CONNECTING -> "Conectando…" to WarnAmber
        transportState == TransportState.CONNECTED -> (connectedDevice?.name ?: "Conectado") to OkGreen
        !btEnabled -> "Bluetooth apagado" to WarnAmber
        transportState == TransportState.FAILED -> "Fallo de conexión" to ErrRed
        else -> "Listo para conectar" to TextSecondary
    }
    val connecting = transportState == TransportState.CONNECTING

    Column(Modifier.fillMaxSize().background(DeepBlack).padding(horizontal = 19.dp, vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Conectar", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onOpenDiagnostics) { Icon(Icons.Filled.Speed, "Diagnóstico") }
            IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, "Ajustes") }
        }
        Row(Modifier.weight(1f).padding(bottom = 9.dp), horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            val shape = MaterialTheme.shapes.large
            Column(
                Modifier.weight(.86f).fillMaxHeight().background(SurfaceDark, shape).border(1.dp, BorderGlow, shape)
                    .verticalScroll(rememberScrollState()).padding(13.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(8.dp).background(statusColor, CircleShape))
                    Text(status, color = statusColor, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                }
                when {
                    connecting -> TextButton(onClick = vm::disconnect) { Text("Cancelar conexión") }
                    !btEnabled -> FilledTonalButton(onClick = onEnableBluetooth) { Text("Activar Bluetooth") }
                    settings.lastDeviceAddress != null -> TextButton(onClick = vm::quickReconnect) {
                        Text("Reconectar con ${settings.lastDeviceName ?: "PC"}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text("Método", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
                TransportModeWire.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.transportMode == mode,
                        onClick = { vm.updateSettings { it.copy(transportMode = mode) } },
                        label = { Text(if (mode == TransportModeWire.RECEIVER) "Receptor Windows" else "HID directo") }
                    )
                }
                Text(
                    if (settings.transportMode == TransportModeWire.RECEIVER)
                        "Abre el receptor en el PC y pulsa Iniciar."
                    else "Conecta como mando HID genérico si tu teléfono admite el perfil.",
                    color = TextSecondary, style = MaterialTheme.typography.bodySmall
                )
            }
            Column(Modifier.weight(1.28f).fillMaxHeight()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Dispositivos", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.bt.refreshBonded(); vm.bt.startScan() }, enabled = btEnabled && !connecting) {
                        Icon(Icons.Filled.Refresh, null, Modifier.size(17.dp))
                        Text("Buscar", Modifier.padding(start = 5.dp))
                    }
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(devices, key = { it.address }) { device ->
                        DeviceRow(device, enabled = !connecting) { vm.connectTo(device) }
                    }
                    if (devices.isEmpty()) item {
                        Text(
                            "Empareja tu PC en los ajustes de Bluetooth y pulsa Buscar.",
                            color = TextSecondary, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
                log.lastOrNull()?.let { last ->
                    Text(last, maxLines = 1, overflow = TextOverflow.Ellipsis, color = TextSecondary,
                        style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(device: BtDeviceUi, enabled: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick, enabled = enabled,
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            Box(Modifier.size(35.dp).background(SurfaceElevated, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Bluetooth, null, Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(device.name, color = TextPrimary, style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(device.address, color = TextSecondary, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace))
            }
            if (device.bonded) Text("Emparejado", color = OkGreen, style = MaterialTheme.typography.labelSmall)
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(15.dp))
        }
    }
}
