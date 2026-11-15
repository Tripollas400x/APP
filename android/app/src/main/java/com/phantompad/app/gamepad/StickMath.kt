package com.phantompad.app.gamepad

import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Matemáticas de los sticks analógicos, aisladas para poder testearlas.
 */
object StickMath {

    /**
     * Zona muerta radial sobre un vector (x, y) ya normalizado a [-1, 1].
     * Por debajo del umbral devuelve (0, 0); por encima reescala linealmente
     * para que la respuesta sea continua en el borde de la zona muerta.
     */
    fun radialDeadzone(x: Float, y: Float, deadzone: Float): Pair<Float, Float> {
        val dz = deadzone.coerceIn(0f, 0.9f)
        val mag = sqrt(x * x + y * y)
        if (mag <= dz || mag <= 1e-6f) return 0f to 0f
        val scale = ((mag - dz) / (1f - dz)).coerceIn(0f, 1f) / mag
        return (x * scale) to (y * scale)
    }

    /**
     * Zona muerta unidimensional (usada en tests y disponible para gatillos).
     */
    fun applyDeadzone(value: Float, deadzone: Float): Float {
        val dz = deadzone.coerceIn(0f, 0.9f)
        val mag = abs(value)
        if (mag <= dz) return 0f
        return ((mag - dz) / (1f - dz)).coerceIn(0f, 1f) * sign(value)
    }

    /**
     * Curva de respuesta. v0.1 es lineal; el punto queda aislado para añadir
     * curvas exponenciales configurables sin tocar el resto del pipeline.
     */
    fun responseCurve(value: Float, sensitivity: Float): Float =
        (value * sensitivity).coerceIn(-1f, 1f)
}
