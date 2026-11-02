package live.hogar.nowplaying

import kotlin.math.max
import kotlin.math.min

/** Rectángulo en las unidades del llamador (dp en las pruebas, px en pantalla). */
internal class NpRect(val l: Float, val t: Float, val r: Float, val b: Float) {
    val w get() = max(0f, r - l)
    val h get() = max(0f, b - t)
    val empty get() = w <= 0.5f || h <= 0.5f
    /** Con tolerancia de medio píxel por redondeos. */
    fun intersects(o: NpRect): Boolean =
        !empty && !o.empty && l < o.r - 0.5f && o.l < r - 0.5f && t < o.b - 0.5f && o.t < b - 0.5f
}

internal class NpLayoutIn(
    val width: Float,
    val height: Float,
    val safeH: Float,
    val safeV: Float,
    val titleH: Float,
    /** Alto medido de los controles (constante en todos los modos). */
    val ctrlH: Float,
    val toastH: Float,
    val artMax: Float,
    val centerCtrlW: Float,
    val gap: Float,
    val colGap: Float,
    val startPad: Float,
)

internal class NpFrame(
    val art: NpRect,
    val title: NpRect,
    /** Columna derecha (letra o cola); solo cuenta si [sideAlpha] > 0. */
    val side: NpRect,
    /** 0..1: la columna derecha solo aparece cuando la carátula ya le dejó el sitio. */
    val sideClear: Float,
    val ctrl: NpRect,
    val toast: NpRect,
    /** Parte alta de la pila inferior (controles + aviso). */
    val stackTop: Float,
)

/**
 * Geometría pura de "Reproduciendo ahora". [p] = 0 centrado, 1 columna lateral; [chromeP] = visibilidad
 * de los controles (se deslizan hacia abajo al ocultarse y liberan su sitio); [toastP] = aviso de luz.
 * Garantía por construcción: carátula + título, columna derecha, controles y aviso no se cruzan en
 * ningún valor intermedio. La carátula es cuadrada, tan grande como cabe (hasta [NpLayoutIn.artMax]).
 */
internal object NpLayout {
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun c01(v: Float) = v.coerceIn(0f, 1f)

    private fun vFit(i: NpLayoutIn, stackTop: Float) = max(0f, stackTop - 2 * i.gap - i.titleH - i.safeV)

    fun sideArt(i: NpLayoutIn) = min(i.artMax, vFit(i, i.height - i.safeV))
    fun sideColumnX(i: NpLayoutIn) = i.startPad + sideArt(i) + i.colGap
    fun controlsWidth(i: NpLayoutIn, p: Float): Float {
        val wC = min(i.centerCtrlW, i.width - 2 * i.safeH)
        return lerp(wC, i.width - i.safeH - sideColumnX(i), c01(p))
    }

    fun frame(i: NpLayoutIn, pIn: Float, chromeIn: Float, toastIn: Float): NpFrame {
        val p = c01(pIn); val chromeP = c01(chromeIn); val toastP = c01(toastIn)
        val bottomEdge = i.height - i.safeV
        val ctrlY = bottomEdge - i.ctrlH * chromeP
        val eT = (i.toastH + i.gap) * toastP
        val z = ctrlY - eT
        val stackH = bottomEdge - z

        val sSide = sideArt(i)
        val colX = sideColumnX(i)
        val sCenter = min(i.artMax, vFit(i, z))
        val sUnc = lerp(sCenter, sSide, p)
        val xUnc = lerp((i.width - sUnc) / 2f, i.startPad, p)

        val wC = min(i.centerCtrlW, i.width - 2 * i.safeH)
        val ctrlW = controlsWidth(i, p)
        val ctrlX = lerp((i.width - wC) / 2f, colX, p)

        // Si la carátula aún no queda a un lado de los controles, cede en vertical (continuo).
        val blend = max(1f, i.colGap - i.gap)
        val sepH = ctrlX - i.gap - (xUnc + sUnc)
        val t = c01((blend - sepH) / blend)
        val s = lerp(sUnc, min(sUnc, vFit(i, z)), t)

        val art = NpRect(xUnc, i.safeV, xUnc + s, i.safeV + s)
        val title = NpRect(xUnc, art.b + i.gap, xUnc + s, art.b + i.gap + i.titleH)
        val sideBottom = max(i.safeV, z - i.gap * c01(stackH / 24f))
        val side = NpRect(colX, i.safeV, i.width - i.safeH, sideBottom)
        val clear = c01((colX - i.gap - art.r) / 36f)
        val ctrl = NpRect(ctrlX, ctrlY, ctrlX + ctrlW, ctrlY + i.ctrlH)
        val toast = NpRect(ctrlX, z, ctrlX + ctrlW, z + i.toastH * toastP)
        return NpFrame(art, title, side, clear, ctrl, toast, z)
    }
}

/** Desplazamiento del rótulo largo: pausa, avance lento, pausa y reinicio. */
internal object MarqueeMath {
    const val PauseS = 2f
    const val SpeedDpPerS = 30f

    /** @return px a desplazar a la izquierda en el instante [tSec] para un desborde de [overflowPx]. */
    fun offset(tSec: Float, overflowPx: Float, speedPxPerS: Float): Float {
        if (overflowPx <= 0f || speedPxPerS <= 0f) return 0f
        val scroll = overflowPx / speedPxPerS
        val cycle = PauseS + scroll + PauseS
        val t = tSec % cycle
        return when {
            t < PauseS -> 0f
            t < PauseS + scroll -> (t - PauseS) * speedPxPerS
            else -> overflowPx
        }
    }
}
