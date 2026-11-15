package com.phantompad.app.gamepad

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** RTT RFCOMM; no incluye la entrega del estado al juego. */
class LatencyMeter(private val now: () -> Long = System::nanoTime) {
    data class LatencyStats(
        val lastMs: Double? = null,
        val avgMs: Double? = null,
        val minMs: Double? = null,
        val maxMs: Double? = null,
        val samples: Int = 0,
        val lost: Int = 0
    )

    private val pending = mutableMapOf<Int, Long>()
    private val recent = ArrayDeque<Double>()
    private val _stats = MutableStateFlow(LatencyStats())
    val stats: StateFlow<LatencyStats> = _stats

    @Synchronized
    fun onPingSent(seq: Int) {
        expire()
        pending[seq] = now()
    }

    @Synchronized
    fun onPongReceived(seq: Int): Boolean {
        expire()
        val started = pending.remove(seq) ?: return false
        val rtt = (now() - started) / 1e6
        recent.addLast(rtt)
        if (recent.size > 30) recent.removeFirst()
        _stats.value = _stats.value.copy(
            lastMs = rtt, avgMs = recent.average(), minMs = recent.minOrNull(),
            maxMs = recent.maxOrNull(), samples = recent.size
        )
        return true
    }

    @Synchronized
    fun expire(): Int {
        val before = pending.size
        val time = now()
        pending.values.removeAll { time - it >= 3_000_000_000L }
        val lost = before - pending.size
        if (lost > 0) _stats.value = _stats.value.copy(lastMs = null, lost = _stats.value.lost + lost)
        return lost
    }

    @Synchronized
    fun reset() {
        pending.clear()
        recent.clear()
        _stats.value = LatencyStats()
    }
}
