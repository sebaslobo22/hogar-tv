package live.hogar.design

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import kotlin.math.PI

/**
 * Matemática pura (sin Android) para convertir los resortes de SwiftUI a Compose.
 *
 * SwiftUI describe un resorte con `response` (periodo del oscilador sin amortiguar, en
 * segundos; Apple lo llama "duration") y `dampingFraction` (1 = sin rebote). Compose lo
 * describe con `stiffness` (k, con masa 1) y `dampingRatio`.
 * Con masa 1: omega = sqrt(k) y response = 2*pi/omega, de donde `k = (2*pi/response)^2`.
 * `dampingRatio` es igual a `dampingFraction`.
 */
object SpringMath {
    /** Rigidez de Compose para un `response` de SwiftUI. */
    fun stiffness(response: Float): Float {
        require(response > 0f) { "response debe ser > 0" }
        val w = (2.0 * PI / response).toFloat()
        return w * w
    }

    /**
     * `bounce` de SwiftUI (-1..1) a fracción de amortiguación. Para bounce >= 0 es `1 - bounce`
     * (`.snappy` base = 0,15 -> 0,85; `.bouncy` base = 0,3 -> 0,7).
     */
    fun dampingFromBounce(bounce: Float): Float =
        if (bounce >= 0f) (1f - bounce).coerceAtLeast(0.05f) else 1f / (1f + bounce)
}

/** Un resorte con los parámetros de SwiftUI. */
data class HogarSpring(val response: Float, val dampingFraction: Float) {
    val stiffness: Float get() = SpringMath.stiffness(response)

    /** Resorte de Compose equivalente. */
    fun <T> spec(): SpringSpec<T> = spring(dampingRatio = dampingFraction, stiffness = stiffness)

    companion object {
        /** SwiftUI `.spring(duration:bounce:)`. */
        fun fromBounce(duration: Float, bounce: Float) = HogarSpring(duration, SpringMath.dampingFromBounce(bounce))
    }
}
