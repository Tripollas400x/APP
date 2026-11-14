package com.phantompad.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Speed
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.offset
import androidx.activity.ComponentActivity
import android.view.WindowManager
import com.phantompad.app.PadViewModel
import com.phantompad.app.data.AppSettings
import com.phantompad.app.data.ProfileId
import com.phantompad.app.gamepad.GamepadState
import com.phantompad.app.gamepad.LayoutProfiles
import com.phantompad.app.gamepad.PadElement
import com.phantompad.app.gamepad.PadLayout
import com.phantompad.app.protocol.ButtonId
import com.phantompad.app.ui.components.BumperButton
import com.phantompad.app.ui.components.DPad
import com.phantompad.app.ui.components.FaceCluster
import com.phantompad.app.ui.components.Joystick
import com.phantompad.app.ui.components.PillButton
import com.phantompad.app.ui.components.TouchpadArea
import com.phantompad.app.ui.components.TriggerButton
import com.phantompad.app.ui.theme.Accent
import com.phantompad.app.ui.theme.DeepBlack
import com.phantompad.app.ui.theme.ErrRed
import com.phantompad.app.ui.theme.OkGreen
import com.phantompad.app.ui.theme.TextPrimary
import com.phantompad.app.ui.theme.TextSecondary
import com.phantompad.app.ui.theme.WarnAmber
import com.phantompad.app.transport.TransportState

/**
 * Modo mando: pantalla completa horizontal, pantalla siempre encendida y
 * liberación del estado al salir. El layout se lee del perfil activo.
 */
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

    val activity = LocalContext.current as? ComponentActivity
    DisposableEffect(settings.keepAwake) {
        val window = activity?.window
        if (settings.keepAwake) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        vm.resumeGamepad()
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            vm.pauseGamepad()
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(DeepBlack)
    ) {
        val layout = remember(settings.activeProfile, overrides) { vm.layoutOf() }
        PadLayoutView(
            state = vm.state,
            settings = settings,
            layout = layout
        )

        // Barra de estado superior
        Surface(
            color = DeepBlack.copy(alpha = 0.72f),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                StatusDot(
                    when (transportState) {
                        TransportState.CONNECTED -> OkGreen
                        TransportState.CONNECTING -> WarnAmber
                        TransportState.FAILED -> ErrRed
                        TransportState.DISCONNECTED -> TextSecondary
                    }
                )
                Text(
                    settings.activeProfile.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextPrimary
                )
                if (settings.transportMode == com.phantompad.app.data.TransportModeWire.RECEIVER) {
                    latency.avgMs?.let {
                        Text("%.0f ms".format(it), style = MaterialTheme.typography.labelSmall, color = Accent)
                    }
                }
                battery?.let {
                    Text("BAT $it%", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                }
                IconButton(onClick = onOpenDiagnostics, modifier = Modifier.size(26.dp)) {
                    Icon(Icons.Filled.Speed, contentDescription = "Diagnóstico", tint = TextSecondary)
                }
                IconButton(onClick = onOpenEditor, modifier = Modifier.size(26.dp)) {
                    Icon(Icons.Filled.Edit, contentDescription = "Editor", tint = TextSecondary)
                }
                IconButton(
                    onClick = { vm.disconnect(); onExit() },
                    modifier = Modifier.size(26.dp)
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Desconectar", tint = TextSecondary)
                }
            }
        }
    }
}

@Composable
private fun StatusDot(color: Color) {
    Box(
        Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color)
    )
}

/**
 * Render del layout del mando. Reutilizado por el editor (con decorador de
 * modificadores para arrastrar/seleccionar).
 */
@Composable
fun PadLayoutView(
    state: GamepadState,
    settings: AppSettings,
    layout: PadLayout,
    modifier: Modifier = Modifier,
    hapticsEnabled: Boolean = true,
    elementDecorator: ((String, Modifier) -> Modifier)? = null
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight
        val minWH = minOf(w, h)

        fun place(el: PadElement, width: Dp, height: Dp): Modifier = Modifier.offset(
            x = w * el.x - width / 2,
            y = h * el.y - height / 2
        )

        fun elementFor(id: String): PadElement =
            layout.elements[id] ?: PadElement(0.5f, 0.5f)

        fun decorate(id: String, base: Modifier): Modifier =
            elementDecorator?.invoke(id, base) ?: base

        @Composable
        fun stick(id: String, set: (Float, Float) -> Unit, press: ButtonId) {
            val el = elementFor(id)
            val side = minWH * 0.26f * el.scale
            Joystick(
                side = side,
                opacity = el.opacity,
                deadzone = settings.deadzone,
                sensitivity = settings.sensitivity,
                hapticsEnabled = hapticsEnabled,
                onMove = set,
                onPress = { state.setButton(press, it) },
                modifier = decorate(id, place(el, side, side))
            )
        }

        stick(ElementIdsLocal.STICK_L, { x, y -> state.setLeftStick(x, y) }, ButtonId.L3)
        stick(ElementIdsLocal.STICK_R, { x, y -> state.setRightStick(x, y) }, ButtonId.R3)

        // Cruceta
        val dpadEl = elementFor(ElementIdsLocal.DPAD)
        val dpadSide = minWH * 0.30f * dpadEl.scale
        DPad(
            side = dpadSide,
            opacity = dpadEl.opacity,
            hapticsEnabled = hapticsEnabled,
            onDirection = { id, pressed -> state.setButton(id, pressed) },
            modifier = decorate(ElementIdsLocal.DPAD, place(dpadEl, dpadSide, dpadSide))
        )

        // Botones de acción
        val faceEl = elementFor(ElementIdsLocal.FACE)
        val faceSide = minWH * 0.34f * faceEl.scale
        FaceCluster(
            side = faceSide,
            opacity = faceEl.opacity,
            hapticsEnabled = hapticsEnabled,
            setButton = { id, pressed -> state.setButton(id, pressed) },
            modifier = decorate(ElementIdsLocal.FACE, place(faceEl, faceSide, faceSide))
        )

        // Bumpers y gatillos
        @Composable
        fun bumper(id: String, label: String, buttonId: ButtonId) {
            val el = elementFor(id)
            val width = minWH * 0.15f * el.scale
            val height = minWH * 0.085f * el.scale
            BumperButton(
                label = label,
                width = width,
                height = height,
                opacity = el.opacity,
                hapticsEnabled = hapticsEnabled,
                onChange = { state.setButton(buttonId, it) },
                modifier = decorate(id, place(el, width, height).offset(y = -minWH * 0.02f))
            )
        }
        bumper(ElementIdsLocal.L1, "L1", ButtonId.L1)
        bumper(ElementIdsLocal.R1, "R1", ButtonId.R1)

        @Composable
        fun trigger(id: String, label: String, set: (Float) -> Unit) {
            val el = elementFor(id)
            val width = minWH * 0.085f * el.scale
            val height = minWH * 0.15f * el.scale
            TriggerButton(
                label = label,
                width = width,
                height = height,
                opacity = el.opacity,
                hapticsEnabled = hapticsEnabled,
                onChange = set,
                modifier = decorate(id, place(el, width, height))
            )
        }
        trigger(ElementIdsLocal.L2, "L2", { state.setTriggerLeft(it) })
        trigger(ElementIdsLocal.R2, "R2", { state.setTriggerRight(it) })

        // Píldoras centrales
        @Composable
        fun pill(id: String, label: String, buttonId: ButtonId) {
            val el = elementFor(id)
            val width = minWH * 0.11f * el.scale
            val height = minWH * 0.055f * el.scale
            PillButton(
                label = label,
                width = width,
                height = height,
                opacity = el.opacity,
                hapticsEnabled = hapticsEnabled,
                onChange = { state.setButton(buttonId, it) },
                modifier = decorate(id, place(el, width, height))
            )
        }
        pill(ElementIdsLocal.SHARE, "SHARE", ButtonId.SHARE)
        pill(ElementIdsLocal.OPTIONS, "OPTIONS", ButtonId.OPTIONS)
        pill(ElementIdsLocal.PS, "PS", ButtonId.PS)

        // Panel táctil
        val touchEl = elementFor(ElementIdsLocal.TOUCHPAD)
        val touchWidth = w * 0.20f * touchEl.scale
        val touchHeight = minWH * 0.12f * touchEl.scale
        TouchpadArea(
            width = touchWidth,
            height = touchHeight,
            opacity = touchEl.opacity,
            onContact = {
                state.setTouch(it)
                state.setButton(ButtonId.TOUCHPAD, it)
            },
            modifier = decorate(ElementIdsLocal.TOUCHPAD, place(touchEl, touchWidth, touchHeight))
        )
    }
}

/** Alias interno para no importar ElementIds dos veces con el mismo nombre. */
private object ElementIdsLocal {
    const val STICK_L = com.phantompad.app.gamepad.ElementIds.STICK_L
    const val STICK_R = com.phantompad.app.gamepad.ElementIds.STICK_R
    const val DPAD = com.phantompad.app.gamepad.ElementIds.DPAD
    const val FACE = com.phantompad.app.gamepad.ElementIds.FACE
    const val L1 = com.phantompad.app.gamepad.ElementIds.L1
    const val R1 = com.phantompad.app.gamepad.ElementIds.R1
    const val L2 = com.phantompad.app.gamepad.ElementIds.L2
    const val R2 = com.phantompad.app.gamepad.ElementIds.R2
    const val SHARE = com.phantompad.app.gamepad.ElementIds.SHARE
    const val OPTIONS = com.phantompad.app.gamepad.ElementIds.OPTIONS
    const val PS = com.phantompad.app.gamepad.ElementIds.PS
    const val TOUCHPAD = com.phantompad.app.gamepad.ElementIds.TOUCHPAD
}
