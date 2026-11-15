package com.phantompad.app.gamepad

import com.phantompad.app.data.AppSettings
import com.phantompad.app.sensors.GyroController
import com.phantompad.app.protocol.InputSnapshot
import com.phantompad.app.transport.Transport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Bucle de envío: codifica el estado del mando y lo manda a la frecuencia
 * configurada (30-240 Hz). Aísla la UI del coste de red y aplica:
 *  - frecuencia reducida en modo ahorro energético,
 *  - giroscopio opcional acoplado al frame,
 *  - liberación del mando al parar (anti-botones-atascados).
 */
class InputHub(
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val state: GamepadState,
    private val settings: StateFlow<AppSettings>,
    private val gyro: GyroController
) {

    data class HubStats(
        val framesSent: Long = 0,
        val targetRateHz: Int = 0,
        val lastSendMs: Long = 0
    )

    private var job: Job? = null

    private val _stats = MutableStateFlow(HubStats())
    val stats: StateFlow<HubStats> = _stats

    val isRunning: Boolean get() = job?.isActive == true

    @Volatile var controlsActive = false

    fun start(transport: Transport) {
        if (isRunning) return
        job = scope.launch(Dispatchers.Default) {
            runLoop(transport)
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    suspend fun stopAndJoin() {
        job?.cancelAndJoin()
        job = null
    }

    private suspend fun runLoop(transport: Transport) {
        var nextDeadline = System.nanoTime()
        try {
            while (kotlin.coroutines.coroutineContext.isActive) {
                val s = settings.value
                val active = controlsActive
                val targetRate = when {
                    !active -> 30
                    s.energySaver -> minOf(s.sendRateHz, 60)
                    else -> s.sendRateHz
                }
                val rate = targetRate.coerceIn(30, 240)
                val intervalNs = 1_000_000_000L / rate

                var snapshot = if (active) state.snapshot() else InputSnapshot()
                if (active && s.gyroEnabled && gyro.available) {
                    snapshot = snapshot.withGyro(gyro.latestCopy())
                }

                transport.sendInput(snapshot)
                _stats.value = HubStats(
                    framesSent = _stats.value.framesSent + 1,
                    targetRateHz = rate,
                    lastSendMs = System.currentTimeMillis()
                )

                nextDeadline += intervalNs
                val now = System.nanoTime()
                // Saltar ranuras atrasadas en lugar de generar una ráfaga de informes.
                if (nextDeadline <= now) nextDeadline = now + intervalNs
                delay((nextDeadline - now + 999_999) / 1_000_000)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            transport.onSendFailure(e)
        }
    }
}
