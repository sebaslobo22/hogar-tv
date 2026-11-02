package live.hogar.nowplaying

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Recorte cuadrado centrado de una imagen [w]×[h]. */
data class SquareCrop(val x: Int, val y: Int, val side: Int)

object ArtworkMath {
    fun squareCrop(w: Int, h: Int): SquareCrop {
        val side = min(w, h).coerceAtLeast(1)
        return SquareCrop((w - side) / 2, (h - side) / 2, side)
    }

    /**
     * Lado (px) con que se dibuja la carátula: el objetivo de pantalla [targetPx], pero NUNCA más
     * que los píxeles reales del recorte cuadrado de la imagen (jamás se amplía).
     */
    fun displaySide(nativeW: Int, nativeH: Int, targetPx: Int): Int =
        min(squareCrop(nativeW, nativeH).side, max(targetPx, 1))

    /** Factor de submuestreo (potencia de 2) para decodificar sin pasar de [maxSide] px por lado. */
    fun sampleSize(w: Int, h: Int, maxSide: Int): Int {
        var s = 1
        while (max(w, h) / (s * 2) >= maxSide) s *= 2
        return s
    }
}

/** Colores dominantes de una carátula (puro: recibe píxeles ARGB). */
object Palette {
    private const val FALLBACK = 0xFF3B4C9B.toInt()

    /** Hasta [count] colores vivos y distintos, del más dominante al menos. Nunca vacío. */
    fun dominant(px: IntArray, count: Int = 4): List<Int> {
        if (px.isEmpty()) return listOf(FALLBACK)
        // Histograma de 4 bits por canal.
        val hist = HashMap<Int, IntArray>() // clave -> [n, sumR, sumG, sumB]
        for (p in px) {
            if ((p ushr 24) < 128) continue
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            val key = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
            val e = hist.getOrPut(key) { IntArray(4) }
            e[0]++; e[1] += r; e[2] += g; e[3] += b
        }
        if (hist.isEmpty()) return listOf(FALLBACK)
        data class Cand(val argb: Int, val score: Float)
        val cands = hist.values.map { e ->
            val r = e[1] / e[0]; val g = e[2] / e[0]; val b = e[3] / e[0]
            val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
            val sat = if (mx == 0) 0f else (mx - mn).toFloat() / mx
            val lum = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
            // Favorece lo colorido y evita negros/blancos puros.
            val tone = if (lum < 0.08f || lum > 0.96f) 0.3f else 1f
            Cand(0xFF000000.toInt() or (r shl 16) or (g shl 8) or b, e[0] * (0.25f + sat) * tone)
        }.sortedByDescending { it.score }
        val out = ArrayList<Int>()
        for (c in cands) {
            if (out.none { distance(it, c.argb) < 70 }) out += c.argb
            if (out.size == count) break
        }
        return out.ifEmpty { listOf(FALLBACK) }
    }

    fun distance(a: Int, b: Int): Int =
        abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) +
            abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) +
            abs((a and 0xFF) - (b and 0xFF))
}

/** Paleta del fondo animado: 4–6 colores vivos y oscuros, ordenados para contraste. Puro (JVM). */
object MeshPalette {
    private const val MAX_LUMA = 0.66f

    fun luma(argb: Int): Float =
        (0.299f * ((argb shr 16) and 0xFF) + 0.587f * ((argb shr 8) and 0xFF) + 0.114f * (argb and 0xFF)) / 255f

    /**
     * Ordena para que colores vecinos en la lista contrasten: el más oscuro primero, luego el más
     * claro, el segundo más oscuro, el segundo más claro... (los focos del fondo se reparten en ese
     * orden alrededor de la pantalla).
     */
    fun orderForContrast(colors: List<Int>): List<Int> {
        val sorted = colors.sortedBy { luma(it) }
        val out = ArrayList<Int>(sorted.size)
        var lo = 0; var hi = sorted.size - 1
        while (lo <= hi) {
            out += sorted[lo++]
            if (lo <= hi) out += sorted[hi--]
        }
        return out
    }

    /** [count] (4..6) colores a partir de los píxeles de la carátula. Nunca vacío. */
    fun build(px: IntArray, count: Int = 5): List<Int> {
        val n = count.coerceIn(4, 6)
        val base = Palette.dominant(px, n + 1).map { tame(it) }.toMutableList()
        val vivid = base.maxByOrNull { saturation(it) } ?: base[0]
        val extras = listOf(
            scale(base[0], 0.34f), scale(vivid, 0.55f), mix(base[0], 0xFFFFFFFF.toInt(), 0.22f).let { tame(it) },
            scale(vivid, 0.80f), mix(vivid, 0xFF000000.toInt(), 0.65f),
        )
        for (e in extras) {
            if (base.size >= n) break
            if (base.none { Palette.distance(it, e) < 40 }) base += e
        }
        var i = 0
        while (base.size < n) base += scale(base[i++ % base.size], 0.42f + 0.07f * i) // último recurso: tonos oscuros
        if (base.none { luma(it) < 0.22f }) base[base.size - 1] = scale(base[0], 0.30f)
        return orderForContrast(base.take(n))
    }

    private fun saturation(c: Int): Float {
        val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
        return if (mx == 0) 0f else (mx - mn).toFloat() / mx
    }

    /** Un poco más vivo y nunca tan claro que el texto blanco pierda contraste. */
    private fun tame(c: Int): Int {
        val l = luma(c) * 255f
        val r0 = (c shr 16) and 0xFF; val g0 = (c shr 8) and 0xFF; val b0 = c and 0xFF
        fun boost(v: Int) = (l + (v - l) * 1.15f).toInt().coerceIn(0, 255)
        val boosted = (0xFF shl 24) or (boost(r0) shl 16) or (boost(g0) shl 8) or boost(b0)
        val lb = luma(boosted)
        return if (lb > MAX_LUMA) scale(boosted, MAX_LUMA / lb) else boosted
    }

    private fun scale(c: Int, f: Float): Int {
        fun ch(s: Int) = (((c shr s) and 0xFF) * f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = ((((a shr s) and 0xFF) * (1 - t)) + (((b shr s) and 0xFF) * t)).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
