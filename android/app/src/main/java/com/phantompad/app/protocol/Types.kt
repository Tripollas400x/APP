package com.phantompad.app.protocol

/**
 * Botones del mando. El bit de cada botón forma parte del protocolo binario
 * (docs/PROTOCOL.md) y NO debe reordenarse sin subir la versión del protocolo.
 */
enum class ButtonId(val bit: Int) {
    TRIANGLE(1 shl 0),
    CIRCLE(1 shl 1),
    CROSS(1 shl 2),
    SQUARE(1 shl 3),
    DPAD_UP(1 shl 4),
    DPAD_DOWN(1 shl 5),
    DPAD_LEFT(1 shl 6),
    DPAD_RIGHT(1 shl 7),
    L1(1 shl 8),
    R1(1 shl 9),
    L3(1 shl 10),
    R3(1 shl 11),
    OPTIONS(1 shl 12),
    SHARE(1 shl 13),
    PS(1 shl 14),
    TOUCHPAD(1 shl 15)
}

/**
 * Estado completo de entrada en un instante. Producido por [GamepadState]
 * y consumido por los transportes para codificar frames o reportes HID.
 */
class InputSnapshot(
    val buttons: Int = 0,
    val lx: Float = 0f,
    val ly: Float = 0f,
    val rx: Float = 0f,
    val ry: Float = 0f,
    val l2: Float = 0f,
    val r2: Float = 0f,
    val touch: Boolean = false,
    val gyro: ShortArray? = null
) {
    /** Devuelve una copia con el giroscopio sustituido (usado por InputHub). */
    fun withGyro(values: ShortArray?): InputSnapshot =
        InputSnapshot(buttons, lx, ly, rx, ry, l2, r2, touch, values)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is InputSnapshot) return false
        return buttons == other.buttons &&
            lx == other.lx && ly == other.ly &&
            rx == other.rx && ry == other.ry &&
            l2 == other.l2 && r2 == other.r2 &&
            touch == other.touch &&
            (gyro?.contentEquals(other.gyro) ?: (other.gyro == null))
    }

    override fun hashCode(): Int {
        var r = buttons
        r = r * 31 + lx.hashCode()
        r = r * 31 + ly.hashCode()
        r = r * 31 + rx.hashCode()
        r = r * 31 + ry.hashCode()
        r = r * 31 + l2.hashCode()
        r = r * 31 + r2.hashCode()
        r = r * 31 + touch.hashCode()
        if (gyro != null) for (v in gyro) r = r * 31 + v
        return r
    }
}
