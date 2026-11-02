package live.hogar.nowplaying

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Campo de color tipo "mesh gradient": [BLOBS] focos de color que se mueven por trayectorias
 * Lissajous lentas (periodos 18–40 s) y se mezclan con pesos suaves (metaballs normalizadas).
 * Se dibuja en un bitmap diminuto ([w]×[h]) que luego se estira con filtro bilineal. Cero
 * asignaciones por cuadro; sin Android (se prueba en la JVM).
 */
class MeshField(val w: Int, val h: Int) {
    private val aspect = w.toFloat() / h
    private val cr = FloatArray(BLOBS); private val cg = FloatArray(BLOBS); private val cb = FloatArray(BLOBS)
    private val tr = FloatArray(BLOBS); private val tg = FloatArray(BLOBS); private val tb = FloatArray(BLOBS)
    private var bgR = 8f; private var bgG = 8f; private var bgB = 12f
    private var tbgR = 8f; private var tbgG = 8f; private var tbgB = 12f
    private var hasColors = false
    private val vig = FloatArray(w * h)
    private val dx2 = FloatArray(BLOBS * w)
    private val dy2 = FloatArray(BLOBS * h)
    private val invR2 = FloatArray(BLOBS)

    init {
        for (y in 0 until h) for (x in 0 until w) {
            val rx = (x + 0.5f) / w * 2f - 1f
            val ry = (y + 0.5f) / h * 2f - 1f
            val rn = sqrt((rx * rx + ry * ry) / 2f).coerceIn(0f, 1f)
            vig[y * w + x] = 0.80f * (0.60f + 0.40f * (1f - rn * rn))
        }
    }

    /** Colores objetivo (4–6; si hay menos de [BLOBS] se reparten cíclicamente). La 1.ª vez se aplican de golpe. */
    fun setTarget(colors: List<Int>) {
        if (colors.isEmpty()) return
        for (i in 0 until BLOBS) {
            val c = colors[i % colors.size]
            tr[i] = ((c shr 16) and 0xFF).toFloat(); tg[i] = ((c shr 8) and 0xFF).toFloat(); tb[i] = (c and 0xFF).toFloat()
        }
        val d = colors.minByOrNull { MeshPalette.luma(it) }!!
        tbgR = ((d shr 16) and 0xFF) * 0.30f; tbgG = ((d shr 8) and 0xFF) * 0.30f; tbgB = (d and 0xFF) * 0.30f
        if (!hasColors) {
            hasColors = true
            tr.copyInto(cr); tg.copyInto(cg); tb.copyInto(cb)
            bgR = tbgR; bgG = tbgG; bgB = tbgB
        }
    }

    /** Acerca los colores al objetivo (fundido de ~1,2 s al cambiar de canción). */
    fun step(dtSec: Float) {
        val k = 1f - exp(-dtSec.coerceIn(0f, 0.25f) / 0.45f)
        for (i in 0 until BLOBS) {
            cr[i] += (tr[i] - cr[i]) * k; cg[i] += (tg[i] - cg[i]) * k; cb[i] += (tb[i] - cb[i]) * k
        }
        bgR += (tbgR - bgR) * k; bgG += (tbgG - bgG) * k; bgB += (tbgB - bgB) * k
    }

    /**
     * Pinta en [out] (ARGB opaco, [w]*[h]). [energy] 0..1 (suavizada) y [swell] (golpe de pulso,
     * ~0..0,08) agrandan y aclaran los focos hasta ~12 %.
     */
    fun render(out: IntArray, timeSec: Float, energy: Float, swell: Float) {
        val scale = 1f + 0.07f * energy + swell
        val gain = 1f + 0.10f * energy + 0.4f * swell
        val twoPi = 2f * PI.toFloat()
        for (b in 0 until BLOBS) {
            val cx = aspect * (0.5f + AX * sin(twoPi * timeSec / PX[b] + PHX[b]))
            val cy = 0.5f + AY * sin(twoPi * timeSec / PY[b] + PHY[b])
            val r = RADIUS[b] * scale
            invR2[b] = 1f / (r * r)
            val ox = b * w
            for (x in 0 until w) { val d = (x + 0.5f) / h - cx; dx2[ox + x] = d * d }
            val oy = b * h
            for (y in 0 until h) { val d = (y + 0.5f) / h - cy; dy2[oy + y] = d * d }
        }
        var i = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sw = BG_WEIGHT
                var sr = BG_WEIGHT * bgR; var sg = BG_WEIGHT * bgG; var sb = BG_WEIGHT * bgB
                for (b in 0 until BLOBS) {
                    val q = 1f + (dx2[b * w + x] + dy2[b * h + y]) * invR2[b]
                    val wt = 1f / (q * q)
                    sw += wt; sr += wt * cr[b]; sg += wt * cg[b]; sb += wt * cb[b]
                }
                val m = gain * vig[i] / sw
                val r = (sr * m).toInt().coerceAtMost(255)
                val g = (sg * m).toInt().coerceAtMost(255)
                val bl = (sb * m).toInt().coerceAtMost(255)
                out[i++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
            }
        }
    }

    companion object {
        const val BLOBS = 6
        private const val AX = 0.40f
        private const val AY = 0.36f
        private const val BG_WEIGHT = 0.10f
        // Periodos entre 18 y 40 s, distintos en cada eje y foco (trayectorias Lissajous que no se repiten pronto).
        private val PX = floatArrayOf(23f, 31f, 19f, 37f, 27f, 33f)
        private val PY = floatArrayOf(29f, 21f, 38f, 18f, 34f, 25f)
        private val PHX = floatArrayOf(0.0f, 1.7f, 3.1f, 4.4f, 5.6f, 2.3f)
        private val PHY = floatArrayOf(0.9f, 2.6f, 0.2f, 3.8f, 1.3f, 4.9f)
        private val RADIUS = floatArrayOf(0.52f, 0.46f, 0.56f, 0.48f, 0.54f, 0.44f)
    }
}
