package live.hogar.launcher

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Matemática pura (sin Android) del icono estilo tvOS: proporción 5:3, esquina continua,
 * ajuste de capas, zona segura del icono adaptable y desplazamientos de parallax.
 */
object IconGeometry {
    const val AspectW = 5f
    const val AspectH = 3f

    /** Radio de esquina = 6 % del ancho (tvOS). */
    const val CornerFraction = 0.06f

    /** Zoom del icono enfocado (tvOS: 1,12–1,15). */
    const val FocusScale = 1.14f

    /** La capa de fondo se dibuja un poco más grande para que el parallax no deje bordes. */
    const val BackOverscan = 1.06f

    /** Icono adaptable: lienzo de 108 dp, zona segura de 66 dp. */
    const val AdaptiveCanvas = 108f
    const val AdaptiveSafe = 66f

    /** La zona segura del primer plano ocupa ~70 % de la altura del icono. */
    const val SafeFractionOfHeight = 0.70f

    /** Icono antiguo (sin capas): su arte ocupa este tanto de la altura, sobre un fondo de color. */
    const val LegacyFrontFraction = 0.80f

    fun heightForWidth(width: Float) = width * AspectH / AspectW
    fun cornerRadius(width: Float) = width * CornerFraction

    /** Lado del cuadrado de 108 dp del primer plano para que su zona segura mida [safeFraction] de [height]. */
    fun foregroundSide(height: Float, safeFraction: Float = SafeFractionOfHeight): Float =
        height * safeFraction * AdaptiveCanvas / AdaptiveSafe

    /** Rectángulo (en unidades del destino) donde dibujar algo. */
    data class Box(val left: Float, val top: Float, val width: Float, val height: Float) {
        val right get() = left + width
        val bottom get() = top + height
    }

    /**
     * Rectángulo donde dibujar una fuente de proporción [srcW]:[srcH] para CUBRIR [dstW]×[dstH]
     * (recortando lo que sobre), centrada. Solo importa la proporción de la fuente.
     */
    fun cover(srcW: Float, srcH: Float, dstW: Float, dstH: Float): Box {
        val s = max(dstW / srcW, dstH / srcH)
        val w = srcW * s
        val h = srcH * s
        return Box((dstW - w) / 2f, (dstH - h) / 2f, w, h)
    }

    /** Cuadrado de lado [side] centrado en [dstW]×[dstH] (puede salirse: se recorta con la forma). */
    fun centered(side: Float, dstW: Float, dstH: Float) = Box((dstW - side) / 2f, (dstH - side) / 2f, side, side)

    /**
     * Tamaño a rasterizar para cubrir un destino de proporción [aspectW]:[aspectH] y [targetW] px de
     * ancho. Si la fuente es un mapa de bits de [srcW]×[srcH] y cubrirlo exigiera ampliarlo, el
     * destino se reduce (nunca se amplía un mapa de bits). Fuente sin límite (vector/color) = destino.
     */
    fun coverRaster(targetW: Int, aspectW: Float, aspectH: Float, srcW: Int?, srcH: Int?): Pair<Int, Int> {
        var w = max(1, targetW)
        var h = max(1, (w * aspectH / aspectW).roundToInt())
        if (srcW != null && srcH != null && srcW > 0 && srcH > 0) {
            val s = max(w.toFloat() / srcW, h.toFloat() / srcH)
            if (s > 1f) {
                w = max(1, (w / s).toInt())
                h = max(1, (w * aspectH / aspectW).roundToInt())
            }
        }
        return w to h
    }

    /** Píxeles de lado del primer plano para el zoom máximo de foco. */
    fun frontSidePx(widthDp: Float, density: Float): Int =
        max(1, ceil(foregroundSide(heightForWidth(widthDp)) * density * FocusScale).toInt())

    /** Ancho en píxeles de la capa de fondo para el zoom máximo de foco (con su sobreancho). */
    fun backWidthPx(widthDp: Float, density: Float): Int =
        max(1, ceil(widthDp * density * FocusScale * BackOverscan).toInt())
}

/** Parallax por capas: la de fondo se mueve poco, la de frente mucho. */
object Parallax {
    const val FrontFactor = 1f
    const val BackFactor = 0.35f

    /** Desplazamiento máximo del primer plano, dp. */
    const val MaxDp = 5f

    /** [norm] (-1..1) es la inclinación hacia la flecha pulsada. */
    fun shiftDp(norm: Float, layerFactor: Float): Float = norm.coerceIn(-1f, 1f) * MaxDp * layerFactor

    /** ¿El sobreancho de la capa de fondo tapa su propio desplazamiento máximo? */
    fun overscanCovers(widthDp: Float): Boolean {
        val h = IconGeometry.heightForWidth(widthDp)
        val marginX = widthDp * (IconGeometry.BackOverscan - 1f) / 2f
        val marginY = h * (IconGeometry.BackOverscan - 1f) / 2f
        val shift = MaxDp * BackFactor
        return shift <= marginX && shift <= marginY
    }
}

/** Rejilla de iconos (5 por fila) y su desplazamiento vertical. */
object IconGrid {
    const val Columns = 5
    const val GapFraction = 0.04f

    /** Alto reservado bajo el icono para el nombre. */
    const val LabelSpaceDp = 34f
    const val RowGapDp = 8f

    class Metrics(val cellWidth: Float, val iconHeight: Float, val gap: Float, val rowHeight: Float, val rowPitch: Float)

    /** [usableWidthDp] = ancho de pantalla menos los márgenes seguros. Hueco = 4 % de ese ancho. */
    fun metrics(usableWidthDp: Float, columns: Int = Columns): Metrics {
        val gap = usableWidthDp * GapFraction
        val w = (usableWidthDp - gap * (columns - 1)) / columns
        val h = IconGeometry.heightForWidth(w)
        val rowH = h + LabelSpaceDp
        return Metrics(w, h, gap, rowH, rowH + RowGapDp)
    }

    /** Favoritas primero y luego el resto, en una sola lista plana. */
    fun flat(pinned: List<String>, rest: List<String>): List<String> = pinned + rest

    fun <T> rows(items: List<T>, perRow: Int = Columns): List<List<T>> = items.chunked(perRow)

    fun rowOf(index: Int, perRow: Int = Columns): Int = if (index < 0) -1 else index / perRow

    /**
     * Desplazamiento de la rejilla (px, 0 = inicio) con el que la fila [row] queda donde debe:
     * la fila 0 en su sitio de reposo (bajo el Top Shelf) y las demás ancladas a [pivot] desde arriba.
     */
    fun targetScroll(row: Int, padTop: Float, pitch: Float, pivot: Float): Float =
        if (row <= 0) 0f else max(0f, padTop + row * pitch - pivot)

    /** Desplazamiento actual a partir del primer elemento visible (las filas miden todas lo mismo). */
    fun currentScroll(firstVisibleIndex: Int, firstVisibleOffset: Int, pitch: Float, perRow: Int = Columns): Float =
        rowOf(firstVisibleIndex, perRow) * pitch + firstVisibleOffset

    /** 0 = Top Shelf entero, 1 = recogido del todo. */
    fun shelfCollapse(scroll: Float, shelfPx: Float): Float =
        if (shelfPx <= 0f) 1f else min(1f, max(0f, scroll / shelfPx))
}
