package com.phantompad.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.phantompad.app.PadViewModel
import com.phantompad.app.gamepad.ElementIds
import com.phantompad.app.gamepad.LayoutProfiles
import com.phantompad.app.gamepad.PadElement
import com.phantompad.app.gamepad.PadLayout
import com.phantompad.app.ui.theme.Accent
import com.phantompad.app.ui.theme.DeepBlack
import com.phantompad.app.ui.theme.SurfaceDark
import com.phantompad.app.ui.theme.TextPrimary
import com.phantompad.app.ui.theme.TextSecondary
import kotlin.math.roundToInt

/**
 * Editor básico de distribución: arrastrar para mover, tocar para
 * seleccionar, sliders de escala y opacidad. Guarda por perfil.
 */
@Composable
fun EditorScreen(vm: PadViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsState()
    val overrides by vm.profileStore.overrides.collectAsState()
    val profile = settings.activeProfile

    var layout by remember(profile, overrides) { mutableStateOf(vm.layoutOf()) }
    var selected by remember { mutableStateOf<String?>(null) }
    val selectedElement = selected?.let { layout.elements[it] }

    Column(
        Modifier
            .fillMaxSize()
            .background(DeepBlack)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver", tint = TextPrimary)
            }
            Text("Editor - ${profile.title}", style = MaterialTheme.typography.titleLarge, color = TextPrimary, modifier = Modifier.weight(1f))
            IconButton(onClick = {
                vm.resetLayout()
                layout = LayoutProfiles.default(profile)
            }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Restaurar", tint = TextSecondary)
            }
            IconButton(onClick = {
                vm.saveLayout(layout)
            }) {
                Icon(Icons.Filled.Check, contentDescription = "Guardar", tint = Accent)
            }
        }

        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            val wPx = constraints.maxWidth.toFloat()
            val hPx = constraints.maxHeight.toFloat()

            PadLayoutView(
                state = vm.state,
                settings = settings,
                layout = layout,
                hapticsEnabled = false,
                elementDecorator = { id, base ->
                    val decorated = base
                        .then(
                            if (id == selected) {
                                Modifier.border(1.dp, Accent, RoundedCornerShape(12.dp))
                            } else {
                                Modifier
                            }
                        )
                        .pointerInput(id) {
                            detectTapGestures { selected = id }
                        }
                        .pointerInput(id, wPx, hPx) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val current = layout.elements[id] ?: PadElement(0.5f, 0.5f)
                                val nx = (current.x + dragAmount.x / wPx).coerceIn(0.04f, 0.96f)
                                val ny = (current.y + dragAmount.y / hPx).coerceIn(0.04f, 0.96f)
                                layout = layout.copy(
                                    elements = layout.elements + (id to current.copy(x = nx, y = ny))
                                )
                            }
                        }
                    decorated
                }
            )
        }

        // Panel inferior: escala y opacidad del elemento seleccionado
        Surface(color = SurfaceDark, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                if (selected == null || selectedElement == null) {
                    Text(
                        "Toca un control y arrástralo para moverlo. Guarda con el icono de la marca.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                } else {
                    Text(
                        "Control: $selected",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Escala", style = MaterialTheme.typography.labelSmall, color = TextSecondary, modifier = Modifier.weight(0.3f))
                        Slider(
                            value = selectedElement.scale,
                            onValueChange = { v ->
                                layout = updateElement(layout, selected!!, selectedElement.copy(scale = (v * 20).roundToInt() / 20f))
                            },
                            valueRange = 0.5f..1.6f,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Opacidad", style = MaterialTheme.typography.labelSmall, color = TextSecondary, modifier = Modifier.weight(0.3f))
                        Slider(
                            value = selectedElement.opacity,
                            onValueChange = { v ->
                                layout = updateElement(layout, selected!!, selectedElement.copy(opacity = (v * 20).roundToInt() / 20f))
                            },
                            valueRange = 0.3f..1f,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "Controles disponibles: ${ElementIds.ALL.joinToString()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    maxLines = 1
                )
            }
        }
    }
}

private fun updateElement(layout: PadLayout, id: String, element: PadElement): PadLayout =
    layout.copy(elements = layout.elements + (id to element))
