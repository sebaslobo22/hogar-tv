package live.hogar.nowplaying

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/**
 * Detector de pulsos: compara la energía con su media móvil (~0,9 s). Devuelve la fuerza del pulso
 * (0 = no hay) y respeta un periodo refractario para no disparar dos veces por el mismo golpe.
 */
class BeatDetector(
    private val refractoryMs: Float = 280f,
    private val ratio: Float = 1.28f,
    private val minRise: Float = 0.05f,
    private val floor: Float = 0.18f,
) {
    private var avg = 0f
    private var prev = 0f
    private var primed = false
    private var sinceBeatMs = 1e6f

    fun push(e: Float, dtMs: Float): Float {
        if (!primed) { primed = true; avg = e; prev = e; return 0f }
        sinceBeatMs += dtMs
        var beat = 0f
        if (sinceBeatMs >= refractoryMs && e > floor && e > avg * ratio && e - prev > minRise) {
            beat = ((e - avg) / max(avg, 0.1f)).coerceIn(0.35f, 1f)
            sinceBeatMs = 0f
        }
        val k = 1f - exp(-(dtMs / 1000f) / 0.9f)
        avg += (e - avg) * k
        prev = e
        return beat
    }
}

/**
 * Energía suavizada (0..1) a partir del nivel crudo del audio, con control automático de ganancia
 * (el volumen del TV varía) y pulsos. [silentSec] cuenta cuánto lleva el audio en silencio EXACTO
 * (señal de que Android no deja capturar esa app: se usa la respiración de reserva).
 */
class EnergyTracker {
    private val beats = BeatDetector()
    private var peak = 0.05f
    var energy = 0f
        private set
    var silentSec = 0f
        private set

    /** @return fuerza del pulso (0 si no hubo). */
    fun push(raw: Float, dtSec: Float): Float {
        val dt = dtSec.coerceIn(0.001f, 0.25f)
        peak = max(max(peak * exp(-dt / 10f), raw), 0.03f)
        silentSec = if (raw <= 0.0005f) silentSec + dt else 0f
        val r = (raw / peak).coerceIn(0f, 1f)
        val n = r * r
        val tau = if (n > energy) 0.04f else 0.25f
        energy += (n - energy) * (1f - exp(-dt / tau))
        return beats.push(energy, dt * 1000f)
    }
}

/** Respiración suave de reserva (sin audio): 0..~0,35 en ciclos de 7 s. */
object Breath {
    fun at(timeSec: Float): Float = 0.175f + 0.175f * sin(2f * PI.toFloat() * timeSec / 7f)
}

/**
 * Resorte amortiguado de un grado de libertad que vuelve a 0 (el decaimiento `.snappy` de SwiftUI:
 * response 0,5 s, amortiguación 0,85). [kick] le da un golpe de velocidad.
 */
class SpringValue(response: Float, private val damping: Float) {
    private val w = (2.0 * PI / response).toFloat()
    var x = 0f
        private set
    private var v = 0f

    fun kick(dv: Float) { v += dv }

    fun step(dtSec: Float) {
        var left = dtSec.coerceIn(0f, 0.25f)
        while (left > 0f) {
            val h = if (left > 1f / 120f) 1f / 120f else left
            v += (-w * w * x - 2f * damping * w * v) * h
            x += v * h
            left -= h
        }
    }
}
