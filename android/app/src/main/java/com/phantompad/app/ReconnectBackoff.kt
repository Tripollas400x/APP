package com.phantompad.app

internal class ReconnectBackoff {
    var attempts = 0
        private set
    private var delayMs = 1000L

    fun nextDelayMs(): Long {
        val next = delayMs
        delayMs = (delayMs * 2).coerceAtMost(8000)
        attempts++
        return next
    }

    fun reset() {
        attempts = 0
        delayMs = 1000
    }
}
