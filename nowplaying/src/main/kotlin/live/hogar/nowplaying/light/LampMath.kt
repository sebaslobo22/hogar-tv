package live.hogar.nowplaying.light

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Color listo para la lámpara. [r],[g],[b] 0..255 ya corregidos para el LED; [kelvin] > 0 = blanco
 * cálido (2700–4000 K; entonces rgb es solo una aproximación para mezclar); [level] 0..1 = brillo
 * propio del color (lo oscuro baja el brillo en vez de mandar RGB casi negro).
 */
class LampColor(val r: Float, val g: Float, val b: Float, val kelvin: Float, val level: Float) {
    val isWhite get() = kelvin > 0f
    fun r8() = r.roundToInt().coerceIn(0, 255)
    fun g8() = g.roundToInt().coerceIn(0, 255)
    fun b8() = b.roundToInt().coerceIn(0, 255)
    fun rgb24() = (r8() shl 16) or (g8() shl 8) or b8()
}

object LampMath {
    private const val GREY_SAT = 0.14f
    private const val LED_GAMMA = 2.2f
    const val MIN_LEVEL = 0.2f
    const val WARM_MIN_K = 2700f
    const val WARM_MAX_K = 4000f

    fun saturate(s: Float): Float = (s * 1.35f + 0.15f).coerceIn(0.6f, 1f)

    /** sRGB de la carátula → color de LED (gamma ~2,2, saturación y valor al máximo, brillo aparte). */
    fun fromArgb(argb: Int): LampColor {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
        val v = mx
        val s = if (mx <= 0f) 0f else (mx - mn) / mx
        val level = (MIN_LEVEL + (1f - MIN_LEVEL) * v).coerceIn(MIN_LEVEL, 1f)
        if (s < GREY_SAT) {
            val k = WARM_MIN_K + (WARM_MAX_K - WARM_MIN_K) * v
            val rgb = kelvinRgb(k)
            return LampColor(rgb[0], rgb[1], rgb[2], k, level)
        }
        val h = hue(r, g, b, mx, mn)
        val s2 = saturate(s)
        val c = s2 // V = 1
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = 1f - c
        val (rr, gg, bb) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun led(ch: Float) = 255f * (ch + m).coerceIn(0f, 1f).pow(LED_GAMMA)
        return LampColor(led(rr), led(gg), led(bb), 0f, level)
    }

    private fun hue(r: Float, g: Float, b: Float, mx: Float, mn: Float): Float {
        val d = mx - mn
        if (d <= 0f) return 0f
        val h = when (mx) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        return if (h < 0f) h + 360f else h
    }

    /** Aproximación lineal 2700 K ≈ (255,169,87) … 4000 K ≈ (255,209,163). */
    fun kelvinRgb(k: Float): FloatArray {
        val t = ((k - WARM_MIN_K) / (WARM_MAX_K - WARM_MIN_K)).coerceIn(0f, 1f)
        return floatArrayOf(255f, 169f + 40f * t, 87f + 76f * t)
    }
}

/** Fundidos y ciclo de paleta. */
object Fade {
    fun smooth(t: Float): Float { val x = t.coerceIn(0f, 1f); return x * x * (3f - 2f * x) }

    fun lerp(a: LampColor, b: LampColor, t: Float): LampColor {
        val k = t.coerceIn(0f, 1f)
        fun m(x: Float, y: Float) = x + (y - x) * k
        val bothWhite = a.isWhite && b.isWhite
        return LampColor(m(a.r, b.r), m(a.g, b.g), m(a.b, b.b), if (bothWhite) m(a.kelvin, b.kelvin) else 0f, m(a.level, b.level))
    }

    /** Color del ciclo en [timeSec]: cada color se funde con el siguiente durante [periodSec]. */
    fun paletteAt(colors: List<LampColor>, timeSec: Float, periodSec: Float): LampColor {
        val n = colors.size
        if (n == 1) return colors[0]
        val pos = max(timeSec, 0f) / periodSec
        val i = pos.toInt()
        return lerp(colors[i % n], colors[(i + 1) % n], smooth(pos - i))
    }
}

/** Deja pasar como mucho [hz] envíos por segundo (los demás se descartan: el siguiente lleva lo último). */
class RateLimiter(hz: Float) {
    private val minMs = (1000f / hz).toLong()
    private var last = Long.MIN_VALUE / 2
    fun tryAcquire(nowMs: Long): Boolean {
        if (nowMs - last < minMs) return false
        last = nowMs
        return true
    }
}

/** Limita cuánto puede cambiar el brillo (0..1) por segundo: 3,5/s = 35 % cada 100 ms (sin estroboscopio). */
class SlewLimiter(private val maxPerSec: Float = 3.5f) {
    var value = Float.NaN
        private set

    fun step(target: Float, dtSec: Float): Float {
        if (value.isNaN()) { value = target; return value }
        val d = maxPerSec * dtSec.coerceAtLeast(0f)
        value = if (target > value) min(target, value + d) else max(target, value - d)
        return value
    }
}

/** Brillo objetivo (0..1) según el modo. */
object Brightness {
    const val PALETTE = 0.65f

    /** Base 60 % + energía hasta +30 % + golpe rápido hasta +15 %. */
    fun rhythm(energy: Float, beat01: Float): Float =
        (0.60f + 0.30f * energy.coerceIn(0f, 1f) + 0.15f * beat01.coerceIn(0f, 1f)).coerceAtMost(1f)

    fun percent(frac: Float): Int = (frac * 100f).roundToInt().coerceIn(1, 100)
}
