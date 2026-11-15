package com.phantompad.app.ui.screens

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.phantompad.app.PadViewModel
import com.phantompad.app.data.AppSettings
import com.phantompad.app.data.TransportModeWire
import com.phantompad.app.gamepad.ElementIds
import com.phantompad.app.gamepad.GamepadState
import com.phantompad.app.gamepad.PadLayout
import com.phantompad.app.gamepad.controlBounds
import com.phantompad.app.protocol.ButtonId
import com.phantompad.app.transport.TransportState
import com.phantompad.app.ui.components.BumperButton
import com.phantompad.app.ui.components.ControllerShell
import com.phantompad.app.ui.components.DPad
import com.phantompad.app.ui.components.FaceCluster
import com.phantompad.app.ui.components.Joystick
import com.phantompad.app.ui.components.PillButton
import com.phantompad.app.ui.components.TouchpadArea
import com.phantompad.app.ui.components.TriggerButton
import com.phantompad.app.ui.theme.BorderGlow
import com.phantompad.app.ui.theme.DeepBlack
import com.phantompad.app.ui.theme.ErrRed
import com.phantompad.app.ui.theme.OkGreen
import com.phantompad.app.ui.theme.SurfaceDark
import com.phantompad.app.ui.theme.TextPrimary
import com.phantompad.app.ui.theme.TextSecondary
import com.phantompad.app.ui.theme.WarnAmber

@Composable
fun GamepadScreen(
    vm: PadViewModel,
    onExit: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenEditor: () -> Unit
) {
    val settings by vm.settings.collectAsState()
    val overrides by vm.profileStore.overrides.collectAsState()
    val transportState by vm.transportState.collectAsState()
    val latency by vm.latency.stats.collectAsState()
    val battery by vm.battery.percent.collectAsState()
    val window = (LocalContext.current as? ComponentActivity)?.window
    val layout = remember(settings.activeProfile, overrides) { vm.layoutOf() }

    DisposableEffect(window, vm) {
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val previousBehavior = controller?.systemBarsBehavior
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        vm.resumeGamepad()
        onDispose {
            vm.pauseGamepad()
            controller?.show(WindowInsetsCompat.Type.systemBars())
            if (previousBehavior != null) controller?.systemBarsBehavior = previousBehavior
        }
    }
    DisposableEffect(window, settings.keepAwake) {
        if (settings.keepAwake) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    Column(Modifier.fillMaxSize().background(DeepBlack).windowInsetsPadding(WindowInsets.displayCutout)) {
        Surface(color = SurfaceDark) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 17.dp, end = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val (status, statusColor) = when (transportState) {
                    TransportState.CONNECTED -> "Conectado" to OkGreen
                    TransportState.CONNECTING -> "Conectando" to WarnAmber
                    TransportState.FAILED -> "Error de conexión" to ErrRed
                    TransportState.DISCONNECTED -> "Desconectado" to TextSecondary
                }
                Box(Modifier.size(7.dp).background(statusColor, CircleShape))
                Text(status, color = statusColor, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                Text(
                    settings.activeProfile.title, color = TextPrimary, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.weight(1f))
                if (settings.transportMode == TransportModeWire.RECEIVER) {
                    HudValue(latency.lastMs?.let { "%.0f ms".format(it) } ?: "— ms")
                }
                battery?.let { HudValue("Bat. $it%") }
                IconButton(onClick = onOpenDiagnostics, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Filled.Speed, "Diagnóstico", Modifier.size(19.dp))
                }
                IconButton(onClick = onOpenEditor, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Filled.Edit, "Editor", Modifier.size(19.dp))
                }
                IconButton(onClick = { vm.disconnect(); onExit() }, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Filled.Close, "Desconectar", Modifier.size(20.dp))
                }
            }
        }
        HorizontalDivider(color = BorderGlow)
        PadLayoutView(
            state = vm.state, settings = settings, layout = layout,
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun HudValue(value: String) {
    Text(
        value, color = TextPrimary, style = MaterialTheme.typography.labelMedium, maxLines = 1,
        modifier = Modifier.background(DeepBlack, CircleShape).padding(horizontal = 10.dp, vertical = 5.dp)
    )
}

/** Comparte la geometría entre el mando y la vista previa del editor. */
@Composable
fun PadLayoutView(
    state: GamepadState,
    settings: AppSettings,
    layout: PadLayout,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    elementDecorator: ((String, Modifier) -> Modifier)? = null
) {
    BoxWithConstraints(modifier) {
        if (maxWidth.value <= 0f || maxHeight.value <= 0f) return@BoxWithConstraints
        ControllerShell(Modifier.fillMaxSize())
        for (id in ElementIds.ALL) {
            val bounds = layout.controlBounds(id, maxWidth.value, maxHeight.value)
            val placed = Modifier.offset(bounds.left.dp, bounds.top.dp)
            val position = elementDecorator?.invoke(id, placed) ?: placed
            val opacity = layout.element(id).opacity
            val width = bounds.width.dp
            val height = bounds.height.dp
            val haptics = interactive && settings.vibrationEnabled
            when (id) {
                ElementIds.STICK_L, ElementIds.STICK_R -> {
                    val left = id == ElementIds.STICK_L
                    Joystick(
                        side = width, opacity = opacity, deadzone = settings.deadzone,
                        sensitivity = settings.sensitivity, hapticsEnabled = haptics,
                        onMove = { x, y -> if (left) state.setLeftStick(x, y) else state.setRightStick(x, y) },
                        onPress = { clicked ->
                            if (settings.stickClickEnabled) state.setButton(if (left) ButtonId.L3 else ButtonId.R3, clicked)
                        },
                        modifier = position, interactive = interactive
                    )
                }
                ElementIds.DPAD -> DPad(width, opacity, haptics, state::setButton, position, interactive)
                ElementIds.FACE -> FaceCluster(width, opacity, haptics, state::setButton, position, interactive)
                ElementIds.L2, ElementIds.R2 -> {
                    val left = id == ElementIds.L2
                    TriggerButton(
                        if (left) "L2" else "R2", width, height, opacity, haptics,
                        if (left) state::setTriggerLeft else state::setTriggerRight, position, interactive
                    )
                }
                ElementIds.TOUCHPAD -> TouchpadArea(
                    width, height, opacity,
                    { state.setTouch(it); state.setButton(ButtonId.TOUCHPAD, it) }, position, interactive
                )
                else -> {
                    val button = when (id) {
                        ElementIds.L1 -> ButtonId.L1
                        ElementIds.R1 -> ButtonId.R1
                        ElementIds.L3 -> ButtonId.L3
                        ElementIds.R3 -> ButtonId.R3
                        ElementIds.SHARE -> ButtonId.SHARE
                        ElementIds.OPTIONS -> ButtonId.OPTIONS
                        else -> ButtonId.PS
                    }
                    val onChange = { pressed: Boolean -> state.setButton(button, pressed) }
                    if (button == ButtonId.L1 || button == ButtonId.R1) {
                        BumperButton(button.name, width, height, opacity, haptics, onChange, position, interactive)
                    } else {
                        PillButton(button.name, width, height, opacity, haptics, onChange, position, interactive)
                    }
                }
            }
        }
    }
}
