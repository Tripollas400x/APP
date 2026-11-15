package com.phantompad.app.gamepad

import com.phantompad.app.data.AppSettings
import com.phantompad.app.sensors.GyroController
import com.phantompad.app.transport.Transport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    fun start(transport: Transport) {
        stop()
        job = scope.launch(Dispatchers.Default) {
            runLoop(transport)
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun runLoop(transport: Transport) {
        var seq = 0
        var nextDeadline = System.nanoTime()
        try {
            while (kotlin.coroutines.coroutineContext.isActive) {
                val s = settings.value
                val targetRate = if (s.energySaver) minOf(s.sendRateHz, 60) else s.sendRateHz
                val rate = targetRate.coerceIn(30, 240)
                val intervalNs = 1_000_000_000L / rate

                var snapshot = state.snapshot()
                if (s.gyroEnabled && gyro.available) {
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
                val remainingMs = (nextDeadline - now) / 1_000_000
                if (remainingMs > 0) {
                    delay(remainingMs)
                } else {
                    // No llegamos a la frecuencia objetivo: reiniciar el planificador.
                    nextDeadline = now
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            transport.onSendFailure(e)
        } finally {
            transport.safeReleaseAll()
        }
    }

    private suspend fun Transport.safeReleaseAll() {
        try {
            releaseAll()
        } catch (_: Exception) {
            // liberar nunca debe propagar errores al cancelarse
        }
    }
}
