package com.phantompad.app.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.roundToInt

/**
 * Giroscopio opcional. Los valores viajan en el frame INPUT (rad/s convertidos
 * a grados/s con 2 decimales) aunque el receptor v0.1 aún no los consume:
 * quedan preparados para el modo DS4 con movimiento.
 */
class GyroController(context: Context) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    val available: Boolean get() = sensor != null

    private val buffer = ShortArray(6)
    private val bufferLock = Any()

    @Volatile
    private var registered = false

    fun setEnabled(enabled: Boolean) {
        if (sensor == null) return
        if (enabled && !registered) {
            sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
            registered = true
        } else if (!enabled && registered) {
            sensorManager?.unregisterListener(this)
            registered = false
        }
    }

    fun latestCopy(): ShortArray = synchronized(bufferLock) { buffer.copyOf() }

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        synchronized(bufferLock) {
            buffer[0] = toDeg100(v[0])
            buffer[1] = toDeg100(v[1])
            buffer[2] = toDeg100(v[2])
            buffer[3] = 0
            buffer[4] = 0
            buffer[5] = 0
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun toDeg100(radPerSec: Float): Short =
        (radPerSec * RAD_TO_DEG * 100).roundToInt().coerceIn(-32768, 32767).toShort()

    companion object {
        private const val RAD_TO_DEG = 57.29577951f
    }
}
