package com.phantompad.app.gamepad

import com.phantompad.app.data.ProfileId
import kotlinx.serialization.Serializable

/** Identificadores de los bloques de control que el editor puede mover. */
object ElementIds {
    const val STICK_L = "stickL"
    const val STICK_R = "stickR"
    const val DPAD = "dpad"
    const val FACE = "face"
    const val L1 = "l1"
    const val R1 = "r1"
    const val L2 = "l2"
    const val R2 = "r2"
    const val SHARE = "share"
    const val OPTIONS = "options"
    const val PS = "ps"
    const val TOUCHPAD = "touchpad"
    const val L3 = "l3"
    const val R3 = "r3"

    val ALL = listOf(STICK_L, STICK_R, DPAD, FACE, L1, R1, L2, R2, SHARE, OPTIONS, PS, TOUCHPAD, L3, R3)
}

/** Posición (fracciones de pantalla), escala y opacidad de un control. */
@Serializable
data class PadElement(
    val x: Float,
    val y: Float,
    val scale: Float = 1f,
    val opacity: Float = 1f
)

@Serializable
data class PadLayout(
    val elements: Map<String, PadElement> = emptyMap(),
    val geometryVersion: Int = 1
) {
    fun element(id: String): PadElement = elements[id] ?: when (id) {
        ElementIds.L3 -> PadElement(.435f, .905f, opacity = .9f)
        ElementIds.R3 -> PadElement(.565f, .905f, opacity = .9f)
        else -> PadElement(.5f, .5f)
    }
}

data class ControlBounds(val left: Float, val top: Float, val width: Float, val height: Float)

private data class ControlSize(val width: Float, val height: Float)

/** Los perfiles antiguos conservan las medidas con las que se guardaron. */
fun PadLayout.controlBounds(id: String, width: Float, height: Float): ControlBounds {
    val el = element(id)
    val modern = geometryVersion >= 2
    // En vistas estrechas (editor y pantallas 16:9), escalar antes de tocar otro control.
    val unit = if (modern) minOf(height, width / 2.08f) else minOf(width, height)
    val size = when (id) {
        ElementIds.STICK_L, ElementIds.STICK_R -> if (modern) ControlSize(.32f, .32f) else ControlSize(.26f, .26f)
        ElementIds.DPAD -> if (modern) ControlSize(.37f, .37f) else ControlSize(.30f, .30f)
        ElementIds.FACE -> if (modern) ControlSize(.41f, .41f) else ControlSize(.34f, .34f)
        ElementIds.L1, ElementIds.R1 -> if (modern) ControlSize(.32f, .12f) else ControlSize(.15f, .085f)
        ElementIds.L2, ElementIds.R2 -> if (modern) ControlSize(.30f, .15f) else ControlSize(.085f, .15f)
        ElementIds.TOUCHPAD -> if (modern) ControlSize(minOf(width / unit * .24f, .59f), .22f)
            else ControlSize(width / unit * .20f, .12f)
        ElementIds.L3, ElementIds.R3 -> if (modern) ControlSize(.17f, .095f) else ControlSize(.095f, .0475f)
        ElementIds.PS -> if (modern) ControlSize(.115f, .115f) else ControlSize(.11f, .055f)
        else -> if (modern) ControlSize(.235f, .096f) else ControlSize(.11f, .055f)
    }
    val w = (size.width * unit * el.scale).coerceIn(0f, width)
    val h = (size.height * unit * el.scale).coerceIn(0f, height)
    val yOffset = if (!modern && (id == ElementIds.L1 || id == ElementIds.R1)) .02f * unit else 0f
    return ControlBounds(
        (width * el.x - w / 2).coerceIn(0f, width - w),
        (height * el.y - h / 2 - yOffset).coerceIn(0f, height - h),
        w,
        h
    )
}

object LayoutProfiles {
    private fun el(x: Float, y: Float, scale: Float = 1f) = PadElement(x, y, scale)

    fun default(profile: ProfileId): PadLayout {
        val classic = mapOf(
            ElementIds.STICK_L to el(.345f, .79f),
            ElementIds.STICK_R to el(.655f, .79f),
            ElementIds.DPAD to el(.165f, .52f),
            ElementIds.FACE to el(.835f, .52f),
            ElementIds.L1 to el(.155f, .253f),
            ElementIds.R1 to el(.845f, .253f),
            ElementIds.L2 to el(.13f, .10f),
            ElementIds.R2 to el(.87f, .10f),
            ElementIds.SHARE to el(.355f, .15f),
            ElementIds.OPTIONS to el(.645f, .15f),
            ElementIds.PS to el(.50f, .63f),
            ElementIds.TOUCHPAD to el(.50f, .35f),
            ElementIds.L3 to el(.155f, .87f),
            ElementIds.R3 to el(.845f, .87f)
        )
        val changes = when (profile) {
            ProfileId.FPS_PRO -> mapOf(
                ElementIds.STICK_L to el(.147f, .50f, 1.08f),
                ElementIds.STICK_R to el(.853f, .50f, 1.08f),
                ElementIds.DPAD to el(.335f, .80f, .90f),
                ElementIds.FACE to el(.665f, .80f, .92f),
                ElementIds.PS to el(.50f, .64f),
                ElementIds.L3 to el(.147f, .79f),
                ElementIds.R3 to el(.853f, .79f)
            )
            ProfileId.RACING -> mapOf(
                ElementIds.L2 to el(.13f, .113f, 1.34f),
                ElementIds.R2 to el(.87f, .113f, 1.34f),
                ElementIds.L1 to el(.155f, .285f),
                ElementIds.R1 to el(.845f, .285f),
                ElementIds.STICK_L to el(.345f, .79f, 1.13f),
                ElementIds.STICK_R to el(.655f, .79f),
                ElementIds.DPAD to el(.145f, .55f, .93f),
                ElementIds.FACE to el(.855f, .55f, .92f)
            )
            else -> emptyMap()
        }
        return PadLayout(classic + changes, geometryVersion = 2)
    }
}
