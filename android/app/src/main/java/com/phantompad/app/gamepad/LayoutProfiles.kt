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

    val ALL = listOf(STICK_L, STICK_R, DPAD, FACE, L1, R1, L2, R2, SHARE, OPTIONS, PS, TOUCHPAD)
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
data class PadLayout(val elements: Map<String, PadElement> = emptyMap()) {
    fun element(id: String): PadElement = elements[id] ?: PadElement(0.5f, 0.5f)
}

/**
 * Distribuciones predefinidas. Se guardan como overrides en DataStore cuando
 * el usuario edita; sin override se usan estos valores.
 */
object LayoutProfiles {

    private fun el(x: Float, y: Float, scale: Float = 1f, opacity: Float = 1f) =
        PadElement(x, y, scale, opacity)

    fun default(profile: ProfileId): PadLayout = when (profile) {
        ProfileId.FPS_PRO -> fpsPro()
        ProfileId.RACING -> racing()
        else -> classic()
    }

    /** Distribución tradicional tipo PlayStation. */
    private fun classic(): PadLayout = PadLayout(
        mapOf(
            ElementIds.STICK_L to el(0.14f, 0.62f),
            ElementIds.STICK_R to el(0.86f, 0.62f),
            ElementIds.DPAD to el(0.31f, 0.64f, 0.85f),
            ElementIds.FACE to el(0.69f, 0.64f, 0.85f),
            ElementIds.L1 to el(0.13f, 0.16f, 0.9f),
            ElementIds.R1 to el(0.87f, 0.16f, 0.9f),
            ElementIds.L2 to el(0.05f, 0.07f, 0.9f),
            ElementIds.R2 to el(0.95f, 0.07f, 0.9f),
            ElementIds.SHARE to el(0.38f, 0.24f, 0.8f),
            ElementIds.OPTIONS to el(0.62f, 0.24f, 0.8f),
            ElementIds.PS to el(0.50f, 0.30f, 0.7f),
            ElementIds.TOUCHPAD to el(0.50f, 0.14f, 0.9f)
        )
    )

    /** Shooters: sticks altos y separados, acción abajo. */
    private fun fpsPro(): PadLayout = PadLayout(
        mapOf(
            ElementIds.STICK_L to el(0.12f, 0.46f, 1.05f),
            ElementIds.STICK_R to el(0.88f, 0.46f, 1.05f),
            ElementIds.DPAD to el(0.33f, 0.80f, 0.75f),
            ElementIds.FACE to el(0.67f, 0.80f, 0.75f),
            ElementIds.L1 to el(0.14f, 0.14f, 0.9f),
            ElementIds.R1 to el(0.86f, 0.14f, 0.9f),
            ElementIds.L2 to el(0.05f, 0.05f, 1.0f),
            ElementIds.R2 to el(0.95f, 0.05f, 1.0f),
            ElementIds.SHARE to el(0.42f, 0.20f, 0.7f),
            ElementIds.OPTIONS to el(0.58f, 0.20f, 0.7f),
            ElementIds.PS to el(0.50f, 0.12f, 0.6f),
            ElementIds.TOUCHPAD to el(0.50f, 0.30f, 0.8f)
        )
    )

    /** Conducción: sticks interiores abajo, gatillos grandes. */
    private fun racing(): PadLayout = PadLayout(
        mapOf(
            ElementIds.STICK_L to el(0.32f, 0.80f, 0.95f),
            ElementIds.STICK_R to el(0.68f, 0.80f, 0.95f),
            ElementIds.DPAD to el(0.10f, 0.58f, 1.0f),
            ElementIds.FACE to el(0.90f, 0.58f, 1.0f),
            ElementIds.L1 to el(0.16f, 0.20f, 0.85f),
            ElementIds.R1 to el(0.84f, 0.20f, 0.85f),
            ElementIds.L2 to el(0.06f, 0.09f, 1.3f),
            ElementIds.R2 to el(0.94f, 0.09f, 1.3f),
            ElementIds.SHARE to el(0.40f, 0.18f, 0.7f),
            ElementIds.OPTIONS to el(0.60f, 0.18f, 0.7f),
            ElementIds.PS to el(0.50f, 0.22f, 0.6f),
            ElementIds.TOUCHPAD to el(0.50f, 0.12f, 0.8f)
        )
    )
}
