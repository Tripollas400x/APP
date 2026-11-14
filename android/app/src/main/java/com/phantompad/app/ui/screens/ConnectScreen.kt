package com.phantompad.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phantompad.app.bt.BtDeviceUi
import com.phantompad.app.data.TransportModeWire
import com.phantompad.app.transport.TransportState
import com.phantompad.app.ui.theme.Accent
import com.phantompad.app.ui.theme.DeepBlack
import com.phantompad.app.ui.theme.ErrRed
import com.phantompad.app.ui.theme.OkGreen
import com.phantompad.app.ui.theme.SurfaceDark
import com.phantompad.app.ui.theme.TextPrimary
import com.phantompad.app.ui.theme.TextSecondary
import com.phantompad.app.ui.theme.WarnAmber

@Composable
fun ConnectScreen(
    vm: com.phantompad.app.PadViewModel,
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

    Column(
        Modifier
            .fillMaxSize()
            .background(DeepBlack)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Conectar", style = MaterialTheme.typography.titleLarge, color = TextPrimary, modifier = Modifier.weight(1f))
            IconButton(onClick = onOpenDiagnostics) {
                Icon(Icons.Filled.Speed, contentDescription = "Diagnóstico", tint = TextSecondary)
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Ajustes", tint = TextSecondary)
            }
        }

        if (!btEnabled) {
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("Bluetooth apagado", color = WarnAmber, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = onEnableBluetooth) { Text("Activar Bluetooth") }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        // Modo de conexión
        Text("MÉTODO DE CONEXIÓN", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        Spacer(Modifier.height(6.dp))
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
        Spacer(Modifier.height(4.dp))
        Text(
            if (settings.transportMode == TransportModeWire.RECEIVER) {
                "Abre PhantomPadReceiver.exe en el PC y pulsa Iniciar. Máxima compatibilidad con juegos (XInput)."
            } else {
                "Sin receptor: Windows verá un mando HID genérico (DirectInput). Experimental: depende del fabricante."
            },
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )

        Spacer(Modifier.height(12.dp))

        // Reconexión rápida
        if (settings.lastDeviceAddress != null && transportState == TransportState.DISCONNECTED) {
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { vm.quickReconnect() }
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Bluetooth, contentDescription = null, tint = Accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("Reconectar con ${settings.lastDeviceName ?: "PC"}", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                        Text(settings.lastDeviceAddress ?: "", style = MaterialTheme.typography.labelSmall, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("DISPOSITIVOS", style = MaterialTheme.typography.labelSmall, color = TextSecondary, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.bt.refreshBonded(); vm.bt.startScan() }) {
                Icon(Icons.Filled.Refresh, contentDescription = null, tint = Accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Buscar", color = Accent)
            }
        }

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(devices, key = { it.address }) { device ->
                DeviceRow(device) { vm.connectTo(device) }
            }
            if (devices.isEmpty()) {
                item {
                    Text(
                        "Sin dispositivos. Empareja tu PC desde los ajustes de Windows o del teléfono y pulsa Buscar.",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (transportState) {
                TransportState.CONNECTING -> {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Accent)
                    Spacer(Modifier.width(8.dp))
                    Text("Conectando...", color = WarnAmber, style = MaterialTheme.typography.labelSmall)
                }
                TransportState.CONNECTED -> StatusLine(
                    OkGreen,
                    connectedDevice?.name?.let { "Conectado a $it" } ?: "Conectado"
                )
                TransportState.FAILED -> StatusLine(ErrRed, "Fallo: ver Diagnóstico")
                TransportState.DISCONNECTED -> StatusLine(TextSecondary, "Desconectado")
            }
            Spacer(Modifier.weight(1f))
            Text(
                log.lastOrNull()?.takeIf { it.isNotBlank() } ?: "Listo",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(2f)
            )
        }
    }
}

@Composable
private fun StatusLine(color: androidx.compose.ui.graphics.Color, text: String) {
    Box(
        Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color)
    )
    Spacer(Modifier.width(8.dp))
    Text(text, color = color, style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun DeviceRow(device: BtDeviceUi, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Bluetooth,
                contentDescription = null,
                tint = if (device.bonded) Accent else TextSecondary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(device.name, color = TextPrimary, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(device.address, color = TextSecondary, style = MaterialTheme.typography.labelSmall)
            }
            Text(
                if (device.bonded) "Emparejado" else "Nuevo",
                color = if (device.bonded) OkGreen else WarnAmber,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}
