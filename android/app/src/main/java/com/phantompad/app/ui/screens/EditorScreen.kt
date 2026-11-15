package com.phantompad.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.phantompad.app.PadViewModel
import com.phantompad.app.gamepad.ElementIds
import com.phantompad.app.gamepad.GamepadState
import com.phantompad.app.gamepad.LayoutProfiles
import com.phantompad.app.gamepad.PadElement
import com.phantompad.app.gamepad.PadLayout
import com.phantompad.app.gamepad.controlBounds
import com.phantompad.app.ui.theme.Accent
import com.phantompad.app.ui.theme.BorderGlow
import com.phantompad.app.ui.theme.DeepBlack
import com.phantompad.app.ui.theme.SurfaceDark
import com.phantompad.app.ui.theme.TextSecondary
import kotlin.math.roundToInt

@Composable
fun EditorScreen(vm: PadViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsState()
    val overrides by vm.profileStore.overrides.collectAsState()
    val profile = settings.activeProfile
    val savedLayout = remember(profile, overrides) { vm.layoutOf() }
    var draft by remember(profile) { mutableStateOf<PadLayout?>(null) }
    var selected by remember(profile) { mutableStateOf<String?>(null) }
    val layout = draft ?: savedLayout
    val previewState = remember { GamepadState() }

    Column(Modifier.fillMaxSize().background(DeepBlack).padding(horizontal = 12.dp, vertical = 5.dp)) {
        Row(
            Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") }
            Column(Modifier.weight(1f)) {
                Text("Editor de controles", style = MaterialTheme.typography.titleLarge)
                Text(profile.title, color = TextSecondary, style = MaterialTheme.typography.labelMedium)
            }
            TextButton(
                onClick = {
                    draft = LayoutProfiles.default(profile)
                    vm.resetLayout()
                }
            ) { Text("Restablecer") }
            Button(enabled = layout != savedLayout, onClick = { vm.saveLayout(layout) }) { Text("Guardar") }
        }

        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BoxWithConstraints(
                Modifier.weight(1f).fillMaxHeight()
                    .background(SurfaceDark, MaterialTheme.shapes.large)
                    .border(1.dp, BorderGlow, MaterialTheme.shapes.large).padding(5.dp)
            ) {
                val width = maxWidth.value
                val height = maxHeight.value
                val pixelWidth = constraints.maxWidth.toFloat()
                val pixelHeight = constraints.maxHeight.toFloat()
                PadLayoutView(previewState, settings, layout, Modifier.fillMaxSize(), interactive = false) { id, base ->
                    base.then(
                        if (id == selected) Modifier.border(2.dp, Accent, RoundedCornerShape(10.dp))
                        else Modifier
                    ).pointerInput(id) {
                        detectTapGestures { selected = id }
                    }.pointerInput(id, width, height, pixelWidth, pixelHeight) {
                        detectDragGestures(onDragStart = { selected = id }) { change, amount ->
                            change.consume()
                            if (pixelWidth <= 0f || pixelHeight <= 0f) return@detectDragGestures
                            val current = draft ?: savedLayout
                            val element = current.element(id)
                            val bounds = current.controlBounds(id, width, height)
                            val xMargin = bounds.width / width / 2f
                            val yMargin = bounds.height / height / 2f
                            draft = current.withElement(id, element.copy(
                                x = (element.x + amount.x / pixelWidth).coerceIn(xMargin, 1f - xMargin),
                                y = (element.y + amount.y / pixelHeight).coerceIn(yMargin, 1f - yMargin)
                            ))
                        }
                    }
                }
            }
            val shape = MaterialTheme.shapes.large
            Column(
                Modifier.weight(.38f).fillMaxHeight().background(SurfaceDark, shape).border(1.dp, BorderGlow, shape)
                    .verticalScroll(rememberScrollState()).padding(13.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val id = selected
                Text(id?.let(::controlName) ?: "Selecciona un control", style = MaterialTheme.typography.titleMedium)
                if (id == null) {
                    Text("Toca para seleccionar o arrastra para mover.", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                } else {
                    val element = layout.element(id)
                    EditorSlider("Tamaño", element.scale, .5f..1.6f) {
                        draft = layout.withElement(id, element.copy(scale = it))
                    }
                    EditorSlider("Opacidad", element.opacity, .3f..1f) {
                        draft = layout.withElement(id, element.copy(opacity = it))
                    }
                }
                Text(
                    if (layout != savedLayout) "Cambios por guardar" else "Distribución guardada",
                    color = TextSecondary, style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun EditorSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onValue: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text("${(value * 100f).roundToInt()}%", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
    }
    Slider(value = value.coerceIn(range), valueRange = range, onValueChange = { onValue((it * 20f).roundToInt() / 20f) })
}

private fun PadLayout.withElement(id: String, element: PadElement): PadLayout =
    copy(elements = elements + (id to element))

private fun controlName(id: String) = when (id) {
    ElementIds.STICK_L -> "Joystick izquierdo"
    ElementIds.STICK_R -> "Joystick derecho"
    ElementIds.DPAD -> "Cruceta"
    ElementIds.FACE -> "Botones de acción"
    ElementIds.TOUCHPAD -> "Panel táctil"
    else -> id.uppercase()
}
