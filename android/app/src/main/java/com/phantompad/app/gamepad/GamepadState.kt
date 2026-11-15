package com.phantompad.app.gamepad

import com.phantompad.app.protocol.ButtonId
import com.phantompad.app.protocol.InputSnapshot

/**
 * Estado mutable de la entrada, escrito por la UI (varios dedos a la vez) y
 * leído por el hilo de envío. Todas las escrituras son atómicas respecto a
 * [snapshot] para que un frame nunca salga a medias.
 */
class GamepadState {

    private val lock = Any()

    private var buttons = 0
    private var lx = 0f
    private var ly = 0f
    private var rx = 0f
    private var ry = 0f
    private var l2 = 0f
    private var r2 = 0f
    private var touch = false
    private var gyro: ShortArray? = null

    fun setButton(id: ButtonId, pressed: Boolean) = synchronized(lock) {
        buttons = if (pressed) buttons or id.bit else buttons and id.bit.inv()
        Unit
    }

    fun isPressed(id: ButtonId): Boolean = synchronized(lock) { buttons and id.bit != 0 }

    fun setLeftStick(x: Float, y: Float) = synchronized(lock) {
        lx = x; ly = y
    }

    fun setRightStick(x: Float, y: Float) = synchronized(lock) {
        rx = x; ry = y
    }

    fun setTriggerLeft(value: Float) = synchronized(lock) {
        l2 = value.coerceIn(0f, 1f)
    }

    fun setTriggerRight(value: Float) = synchronized(lock) {
        r2 = value.coerceIn(0f, 1f)
    }

    fun setTouch(value: Boolean) = synchronized(lock) {
        touch = value
    }

    fun setGyro(values: ShortArray?) = synchronized(lock) {
        gyro = values
    }

    /** Pone a cero botones y ejes. Lo llaman la UI al salir y el watchdog. */
    fun reset() = synchronized(lock) {
        buttons = 0
        lx = 0f; ly = 0f; rx = 0f; ry = 0f
        l2 = 0f; r2 = 0f
        touch = false
    }

    fun clearButtons() = synchronized(lock) {
        buttons = 0
    }

    fun snapshot(): InputSnapshot = synchronized(lock) {
        InputSnapshot(
            buttons = buttons,
            lx = lx, ly = ly,
            rx = rx, ry = ry,
            l2 = l2, r2 = r2,
            touch = touch,
            gyro = gyro?.copyOf()
        )
    }
}
