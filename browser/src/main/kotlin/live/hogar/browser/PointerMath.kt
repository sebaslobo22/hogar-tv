package live.hogar.browser

import kotlin.math.hypot

/** Matemática pura del puntero virtual del mando (sin Android). */
object PointerMath {
    /** Salto inmediato de un toque corto, en px (a 320 dpi ≈ 9 dp). */
    const val TAP_STEP = 18f
    const val BASE_SPEED = 260f
    const val MAX_SPEED = 1500f
    const val RAMP_MS = 1000L

    /** Velocidad en px/s tras [heldMs] con la flecha sostenida: arranca lento y acelera suave (smoothstep). */
    fun speed(heldMs: Long): Float {
        val t = (heldMs.toFloat() / RAMP_MS).coerceIn(0f, 1f)
        val e = t * t * (3f - 2f * t)
        return BASE_SPEED + (MAX_SPEED - BASE_SPEED) * e
    }

    data class Pos(val x: Float, val y: Float)

    /** Avanza el puntero en la dirección (dirX, dirY) ∈ {-1,0,1}; la diagonal no es más rápida. Queda dentro de la pantalla. */
    fun step(x: Float, y: Float, dirX: Int, dirY: Int, speed: Float, dtSec: Float, w: Float, h: Float): Pos {
        val len = hypot(dirX.toFloat(), dirY.toFloat())
        if (len == 0f) return Pos(x, y)
        val d = speed * dtSec
        val nx = (x + dirX / len * d).coerceIn(0f, (w - 1f).coerceAtLeast(0f))
        val ny = (y + dirY / len * d).coerceIn(0f, (h - 1f).coerceAtLeast(0f))
        return Pos(nx, ny)
    }
}

/** Desplazamiento de la página cuando el puntero está en el borde. */
object EdgeScroll {
    const val BAND = 72f
    const val MAX = 1100f

    /** px/s con signo en un eje: negativo cerca del borde inicial, positivo cerca del final, 0 en medio. */
    fun axis(pos: Float, size: Float, band: Float = BAND, max: Float = MAX): Float {
        if (size <= band * 2f) return 0f
        return when {
            pos < band -> -max * curve((band - pos) / band)
            pos > size - band -> max * curve((pos - (size - band)) / band)
            else -> 0f
        }
    }

    private fun curve(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        return c * c
    }

    /** Suma [delta] a lo acumulado y devuelve los px enteros a desplazar y el resto fraccionario. */
    fun split(acc: Float, delta: Float): Pair<Int, Float> {
        val total = acc + delta
        val whole = total.toInt()
        return whole to (total - whole)
    }

    /** Salto del modo desplazamiento: ~80 % del alto visible. */
    fun pageStep(viewportH: Int): Int = (viewportH * 0.8f).toInt().coerceAtLeast(1)
}
