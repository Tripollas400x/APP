package com.phantompad.app.gamepad

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Mide el RTT real del transporte: el teléfono manda PING con secuencia y
 * el receptor responde PONG con la misma secuencia. Guarda las últimas
 * muestras y expone promedio, mínimo y máximo.
 */
class LatencyMeter {

    data class LatencyStats(
        val lastMs: Double? = null,
        val avgMs: Double? = null,
        val minMs: Double? = null,
        val maxMs: Double? = null,
        val samples: Int = 0
    )

    private val pending = ConcurrentHashMap<Int, Long>()
    private val recent = ArrayDeque<Double>()
    private val lock = Any()

    private val _stats = MutableStateFlow(LatencyStats())
    val stats: StateFlow<LatencyStats> = _stats

    fun onPingSent(seq: Int) {
        pending[seq] = System.nanoTime()
    }

    fun onPongReceived(seq: Int): Boolean {
        val started = pending.remove(seq) ?: return false
        val rtt = (System.nanoTime() - started) / 1e6
        synchronized(lock) {
            recent.addLast(rtt)
            while (recent.size > MAX_SAMPLES) recent.removeFirst()
            val list = recent.toList()
            _stats.value = LatencyStats(
                lastMs = rtt,
                avgMs = list.average(),
                minMs = list.minOrNull(),
                maxMs = list.maxOrNull(),
                samples = list.size
            )
        }
        return true
    }

    /** Secuencias que llevan más de [ageMs] sin respuesta (conexión degradada). */
    fun hasStalePending(ageMs: Long): Boolean {
        val cutoff = System.nanoTime() - ageMs * 1_000_000
        return pending.values.any { it < cutoff }
    }

    fun reset() {
        pending.clear()
        synchronized(lock) {
            recent.clear()
            _stats.value = LatencyStats()
        }
    }

    companion object {
        private const val MAX_SAMPLES = 30
    }
}
