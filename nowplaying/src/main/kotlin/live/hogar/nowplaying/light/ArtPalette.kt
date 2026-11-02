package live.hogar.nowplaying.light

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sqrt

/** Resultado: colores ARGB por prominencia (el primero es el más importante). [monochrome] = el arte no tiene color vivo. */
class ArtPaletteResult(val colors: List<Int>, val monochrome: Boolean)

/**
 * Paleta para la LÁMPARA a partir de la carátula tal cual (no del fondo animado, que está oscurecido).
 * Pasos: submuestreo a ~96 px → OKLab → se descartan casi negros, casi blancos y grises → k-means
 * ponderado → se funden clústeres con ΔE(OKLab) < [MERGE_DE] (0,10 ≈ ΔE 12 en la escala 0-100 de CIE)
 * → se ordena por prominencia = población × croma. Si no hay color vivo (≥ 5 % de los píxeles) el arte
 * es monocromo: se devuelven 1–3 grises (luma del arte) que [LampMath] convierte en blancos cálidos.
 * Puro salvo [fromBitmap]; probado en la JVM con IntArray.
 */
object ArtPalette {
    const val ANALYSIS_PX = 96
    const val MERGE_DE = 0.10f
    private const val MIN_L = 0.20f
    private const val MAX_L = 0.95f
    private const val MIN_CHROMA = 0.045f
    private const val MIN_VIVID_SHARE = 0.05f
    private const val K = 8
    private const val ITER = 10

    fun fromBitmap(bmp: Bitmap, max: Int = 6): ArtPaletteResult {
        val scale = ANALYSIS_PX.toFloat() / maxOf(bmp.width, bmp.height)
        val small = if (scale < 1f) Bitmap.createScaledBitmap(bmp, maxOf(1, (bmp.width * scale).toInt()), maxOf(1, (bmp.height * scale).toInt()), true) else bmp
        val px = IntArray(small.width * small.height)
        small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
        val out = extract(px, small.width, small.height, max)
        if (small !== bmp) small.recycle()
        return out
    }

    /** [px] ARGB de [w]×[h]. [max] = 4..6 colores como mucho (menos si el arte tiene menos). Nunca vacío. */
    fun extract(px: IntArray, w: Int, h: Int, max: Int = 6): ArtPaletteResult {
        val limit = max.coerceIn(1, 6)
        val step = maxOf(1, maxOf(w, h) / ANALYSIS_PX)
        val l = ArrayList<Float>(); val a = ArrayList<Float>(); val b = ArrayList<Float>()
        val grey = ArrayList<Int>()
        var total = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val p = px[y * w + x]
                if ((p ushr 24) >= 128) {
                    total++
                    val lab = oklab(p)
                    val c = hypot(lab[1], lab[2])
                    if (lab[0] in MIN_L..MAX_L && c >= MIN_CHROMA) { l += lab[0]; a += lab[1]; b += lab[2] } else grey += p
                }
                x += step
            }
            y += step
        }
        if (total == 0) return ArtPaletteResult(listOf(0xFFFFB46B.toInt()), true)
        if (l.size < total * MIN_VIVID_SHARE || l.size < 8) return ArtPaletteResult(monochrome(px, w, h, step), true)

        val n = l.size
        // Semillas: la más cromática y luego la más lejana a las ya elegidas (determinista).
        val k = minOf(K, n)
        val cl = FloatArray(k); val ca = FloatArray(k); val cb = FloatArray(k)
        var first = 0; var bestC = -1f
        for (i in 0 until n) { val c = hypot(a[i], b[i]); if (c > bestC) { bestC = c; first = i } }
        cl[0] = l[first]; ca[0] = a[first]; cb[0] = b[first]
        for (s in 1 until k) {
            var far = 0; var farD = -1f
            for (i in 0 until n) {
                var dmin = Float.MAX_VALUE
                for (j in 0 until s) dmin = minOf(dmin, dist(l[i], a[i], b[i], cl[j], ca[j], cb[j]))
                if (dmin > farD) { farD = dmin; far = i }
            }
            cl[s] = l[far]; ca[s] = a[far]; cb[s] = b[far]
        }
        val assign = IntArray(n)
        repeat(ITER) {
            for (i in 0 until n) {
                var best = 0; var bd = Float.MAX_VALUE
                for (j in 0 until k) { val d = dist(l[i], a[i], b[i], cl[j], ca[j], cb[j]); if (d < bd) { bd = d; best = j } }
                assign[i] = best
            }
            val sl = FloatArray(k); val sa = FloatArray(k); val sb = FloatArray(k); val cnt = IntArray(k)
            for (i in 0 until n) { val j = assign[i]; sl[j] += l[i]; sa[j] += a[i]; sb[j] += b[i]; cnt[j]++ }
            for (j in 0 until k) if (cnt[j] > 0) { cl[j] = sl[j] / cnt[j]; ca[j] = sa[j] / cnt[j]; cb[j] = sb[j] / cnt[j] }
        }
        val pop = IntArray(k)
        for (i in 0 until n) pop[assign[i]]++

        class C(var l: Float, var a: Float, var b: Float, var pop: Float)
        val clusters = ArrayList<C>()
        for (j in 0 until k) if (pop[j] > 0) clusters += C(cl[j], ca[j], cb[j], pop[j].toFloat())
        // Fusión de parecidos (el más poblado absorbe al cercano; el centro se pondera).
        var merged = true
        while (merged) {
            merged = false
            clusters.sortByDescending { it.pop }
            loop@ for (i in clusters.indices) for (j in i + 1 until clusters.size) {
                val p = clusters[i]; val q = clusters[j]
                if (dist(p.l, p.a, p.b, q.l, q.a, q.b) < MERGE_DE) {
                    val t = p.pop + q.pop
                    p.l = (p.l * p.pop + q.l * q.pop) / t; p.a = (p.a * p.pop + q.a * q.pop) / t; p.b = (p.b * p.pop + q.b * q.pop) / t
                    p.pop = t
                    clusters.removeAt(j); merged = true
                    break@loop
                }
            }
        }
        fun saliency(c: C) = c.pop * (hypot(c.a, c.b) + 0.02f)
        clusters.sortByDescending { saliency(it) }
        val top = saliency(clusters[0])
        val kept = clusters.filter { saliency(it) >= top * 0.06f && it.pop >= n * 0.01f }.take(limit).ifEmpty { listOf(clusters[0]) }
        return ArtPaletteResult(kept.map { toArgb(it.l, it.a, it.b) }, false)
    }

    /** Arte sin color: hasta 3 grises (percentiles de luma 80/50/20 sin duplicados) con luma mínima 0,30. */
    private fun monochrome(px: IntArray, w: Int, h: Int, step: Int): List<Int> {
        val lumas = ArrayList<Float>()
        var y = 0
        while (y < h) { var x = 0; while (x < w) { val p = px[y * w + x]; if ((p ushr 24) >= 128) lumas += oklab(p)[0]; x += step }; y += step }
        lumas.sort()
        fun pct(q: Float) = lumas[((lumas.size - 1) * q).toInt()]
        val out = ArrayList<Float>()
        for (q in floatArrayOf(0.8f, 0.5f, 0.2f)) {
            val v = pct(q).coerceIn(0.30f, 0.95f)
            if (out.none { abs(it - v) < 0.12f }) out += v
        }
        return out.map { toArgb(it, 0f, 0f) }
    }

    private fun dist(l1: Float, a1: Float, b1: Float, l2: Float, a2: Float, b2: Float): Float {
        val dl = l1 - l2; val da = a1 - a2; val db = b1 - b2
        return sqrt(dl * dl + da * da + db * db)
    }

    private fun lin(c: Int): Float { val v = c / 255f; return if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f) }
    private fun gam(v: Float): Float { val x = v.coerceIn(0f, 1f); return if (x <= 0.0031308f) x * 12.92f else 1.055f * x.pow(1f / 2.4f) - 0.055f }

    /** sRGB ARGB → [L, a, b] de OKLab (Björn Ottosson). */
    internal fun oklab(argb: Int): FloatArray {
        val r = lin((argb shr 16) and 0xFF); val g = lin((argb shr 8) and 0xFF); val bl = lin(argb and 0xFF)
        val lm = cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * bl)
        val mm = cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * bl)
        val sm = cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * bl)
        return floatArrayOf(
            0.2104542553f * lm + 0.7936177850f * mm - 0.0040720468f * sm,
            1.9779984951f * lm - 2.4285922050f * mm + 0.4505937099f * sm,
            0.0259040371f * lm + 0.7827717662f * mm - 0.8086757660f * sm,
        )
    }

    internal fun toArgb(l: Float, a: Float, b: Float): Int {
        val lp = l + 0.3963377774f * a + 0.2158037573f * b
        val mp = l - 0.1055613458f * a - 0.0638541728f * b
        val sp = l - 0.0894841775f * a - 1.2914855480f * b
        val l3 = lp * lp * lp; val m3 = mp * mp * mp; val s3 = sp * sp * sp
        val r = 4.0767416621f * l3 - 3.3077115913f * m3 + 0.2309699292f * s3
        val g = -1.2684380046f * l3 + 2.6097574011f * m3 - 0.3413193965f * s3
        val bb = -0.0041960863f * l3 - 0.7034186147f * m3 + 1.7076147010f * s3
        fun c8(v: Float) = (gam(v) * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (c8(r) shl 16) or (c8(g) shl 8) or c8(bb)
    }
}
