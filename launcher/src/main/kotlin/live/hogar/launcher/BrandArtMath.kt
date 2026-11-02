package live.hogar.launcher

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

// Lógica PURA (sin Android) del arte de marca: calidad de la fuente, recorte del logo, tamaños sin
// ampliar y elección del logo. Se prueba en la JVM; el adaptador de Drawable vive en AppArt.kt.

// ---------------------------------------------------------------- calidad de la fuente

/** Calidad de una fuente gráfica: lo escalable se dibuja a cualquier tamaño; un raster, nunca por encima de su tamaño. */
sealed interface Source {
    /** VectorDrawable, AnimatedVectorDrawable, GradientDrawable: nítido a cualquier tamaño. */
    object Vector : Source
    /** Color liso. */
    data class Color(val argb: Int) : Source
    /** Mapa de bits de [w]×[h] píxeles reales. */
    data class Raster(val w: Int, val h: Int) : Source
    /** No hay fuente. */
    object None : Source

    val scalable get() = this is Vector || this is Color
}

/** Árbol mínimo de un Drawable (lo que importa para clasificar), construible sin Android. */
sealed interface DNode {
    object Vec : DNode
    data class Col(val argb: Int) : DNode
    data class Bmp(val w: Int, val h: Int) : DNode
    data class Inset(val child: DNode?) : DNode
    data class Scale(val child: DNode?) : DNode
    data class Layers(val children: List<DNode?>) : DNode
    /** Drawable desconocido: si tiene tamaño intrínseco se trata como raster (lo prudente). */
    data class Other(val w: Int, val h: Int) : DNode
}

object SourceQuality {
    fun classify(n: DNode?): Source = when (n) {
        null -> Source.None
        DNode.Vec -> Source.Vector
        is DNode.Col -> Source.Color(n.argb)
        is DNode.Bmp -> if (n.w > 0 && n.h > 0) Source.Raster(n.w, n.h) else Source.None
        is DNode.Inset -> classify(n.child)
        is DNode.Scale -> classify(n.child)
        is DNode.Other -> if (n.w > 0 && n.h > 0) Source.Raster(n.w, n.h) else Source.Vector
        is DNode.Layers -> {
            val parts = n.children.map { classify(it) }.filter { it != Source.None }
            val rasters = parts.filterIsInstance<Source.Raster>()
            when {
                parts.isEmpty() -> Source.None
                rasters.isNotEmpty() -> Source.Raster(rasters.maxOf { it.w }, rasters.maxOf { it.h })
                parts.any { it is Source.Vector } -> Source.Vector
                else -> parts.first()
            }
        }
    }
}

// ---------------------------------------------------------------- recorte de márgenes

/** Recorte de márgenes de un mapa de píxeles ARGB. Rect = izquierda, arriba, derecha, abajo (derecha/abajo exclusivos). */
object OpaqueBounds {
    class Rect(val l: Int, val t: Int, val r: Int, val b: Int) {
        val w get() = r - l
        val h get() = b - t
        override fun equals(other: Any?) = other is Rect && other.l == l && other.t == t && other.r == r && other.b == b
        override fun hashCode() = ((l * 31 + t) * 31 + r) * 31 + b
        override fun toString() = "Rect($l,$t,$r,$b)"
    }

    /** Caja de los píxeles con alfa ≥ [alphaMin]; null si no hay ninguno. */
    fun alpha(px: IntArray, w: Int, h: Int, alphaMin: Int = 24): Rect? =
        scan(px, w, h) { (it ushr 24) >= alphaMin }

    /** Caja de los píxeles opacos que difieren del fondo [bg] en más de [tol] por canal. */
    fun keyed(px: IntArray, w: Int, h: Int, bg: Int, tol: Int = 28): Rect? =
        scan(px, w, h) { (it ushr 24) >= 24 && differs(it, bg, tol) }

    private inline fun scan(px: IntArray, w: Int, h: Int, hit: (Int) -> Boolean): Rect? {
        if (w <= 0 || h <= 0 || px.size < w * h) return null
        var l = w; var t = h; var r = -1; var b = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if (hit(px[row + x])) {
                    if (x < l) l = x
                    if (x > r) r = x
                    if (y < t) t = y
                    if (y > b) b = y
                }
            }
        }
        return if (r < 0) null else Rect(l, t, r + 1, b + 1)
    }

    fun differs(a: Int, b: Int, tol: Int): Boolean =
        abs(((a shr 16) and 255) - ((b shr 16) and 255)) > tol ||
            abs(((a shr 8) and 255) - ((b shr 8) and 255)) > tol ||
            abs((a and 255) - (b and 255)) > tol

    /**
     * Si el mapa tiene un fondo liso (esquinas y medios de borde iguales y opacos) devuelve ese color,
     * típico de un banner con su propio fondo; si no, null.
     */
    fun uniformBackground(px: IntArray, w: Int, h: Int, tol: Int = 20): Int? {
        if (w < 2 || h < 2 || px.size < w * h) return null
        val pts = intArrayOf(
            px[0], px[w - 1], px[(h - 1) * w], px[h * w - 1],
            px[w / 2], px[(h - 1) * w + w / 2], px[(h / 2) * w], px[(h / 2) * w + w - 1],
        )
        if (pts.any { (it ushr 24) < 250 }) return null
        val ref = pts[0]
        return if (pts.all { !differs(it, ref, tol) }) ref or (0xFF shl 24) else null
    }
}

// ---------------------------------------------------------------- tamaños sin ampliar

object LogoSizing {
    /** Un logo es ancho (wordmark) si su caja opaca mide más de 2:1. */
    const val WideAspect = 2f
    const val WideWidthFraction = 0.70f
    const val SquareHeightFraction = 0.55f
    private const val MaxHeightFraction = 0.62f
    private const val MaxWidthFraction = 0.70f

    fun isWide(aspect: Float) = aspect > WideAspect

    class Size(val w: Float, val h: Float)

    /**
     * Tamaño en reposo (px de la tarjeta) del logo de proporción [aspect] (ancho/alto), como en tvOS:
     * wordmark ≈ 70 % del ancho; símbolo ≈ 55 % del alto. Con [nativeW]×[nativeH] (raster) y el zoom
     * máximo [maxScale], jamás supera lo que permite el tamaño real: `rest × maxScale ≤ nativo`.
     */
    fun card(cardW: Float, cardH: Float, aspect: Float, nativeW: Int?, nativeH: Int?, maxScale: Float): Size {
        val a = aspect.coerceIn(0.2f, 12f)
        var w: Float
        var h: Float
        if (isWide(a)) {
            w = cardW * WideWidthFraction; h = w / a
            if (h > cardH * MaxHeightFraction) { h = cardH * MaxHeightFraction; w = h * a }
        } else {
            h = cardH * SquareHeightFraction; w = h * a
            if (w > cardW * MaxWidthFraction) { w = cardW * MaxWidthFraction; h = w / a }
        }
        return noUpscale(w, h, nativeW, nativeH, maxScale)
    }

    /** Reduce (nunca amplía) [w]×[h] para que `× maxScale` quepa en el tamaño nativo. Vector (null) = igual. */
    fun noUpscale(w: Float, h: Float, nativeW: Int?, nativeH: Int?, maxScale: Float): Size {
        if (nativeW == null || nativeH == null || nativeW <= 0 || nativeH <= 0) return Size(w, h)
        val k = min(1f, min(nativeW / (w * maxScale), nativeH / (h * maxScale)))
        return Size(w * k, h * k)
    }

    /** Píxeles del mapa de bits para dibujar [restW]×[restH] con zoom [maxScale]; un raster nunca pasa de su tamaño. */
    fun bitmapPx(restW: Float, restH: Float, maxScale: Float, nativeW: Int?, nativeH: Int?): Pair<Int, Int> {
        var w = max(1, ceil(restW * maxScale - 0.001f).toInt())
        var h = max(1, ceil(restH * maxScale - 0.001f).toInt())
        if (nativeW != null && nativeH != null && nativeW > 0 && nativeH > 0) {
            w = min(w, nativeW); h = min(h, nativeH)
        }
        return w to h
    }

    /** Caja del logo en el Top Shelf (px): a la izquierda, centrado en la franja alta. Sin zoom, sin ampliar raster. */
    class ShelfBox(val left: Float, val top: Float, val w: Float, val h: Float)

    fun shelf(shelfW: Float, shelfH: Float, leftPx: Float, aspect: Float, nativeW: Int?, nativeH: Int?): ShelfBox {
        val a = aspect.coerceIn(0.2f, 12f)
        val maxH = shelfH * 0.42f
        val maxW = shelfW * 0.34f
        var h = maxH; var w = h * a
        if (w > maxW) { w = maxW; h = w / a }
        val s = noUpscale(w, h, nativeW, nativeH, 1f)
        val top = shelfH * 0.20f + (maxH - s.h) / 2f
        return ShelfBox(leftPx, top, s.w, s.h)
    }
}

// ---------------------------------------------------------------- elección del logo

enum class LogoOrigin { Foreground, Banner, Icon }

/**
 * Un logo candidato ya recortado: [aspect] de su caja opaca; [nativeW]×[nativeH] = píxeles reales de
 * esa caja (null = vector). [bg] = fondo liso propio (banner), si lo tiene.
 */
class LogoCandidate(
    val origin: LogoOrigin,
    val aspect: Float,
    val nativeW: Int?,
    val nativeH: Int?,
    val bg: Int? = null,
) {
    val vector get() = nativeW == null
}

enum class LogoOrder { Auto, ForegroundFirst }

object LogoChoice {
    /** Ancho (px) que necesita el candidato para la tarjeta con zoom máximo. */
    fun targetW(c: LogoCandidate, cardW: Float, cardH: Float, maxScale: Float): Float =
        LogoSizing.card(cardW, cardH, c.aspect, null, null, maxScale).w * maxScale

    /**
     * Regla: 1) primer plano vector; 2) banner (vector o raster con ancho ≥ el necesario, fondo liso
     * y, si la marca fija su fondo, de un color compatible); 3) el raster más grande. Con
     * [LogoOrder.ForegroundFirst] un primer plano raster suficiente va antes que el banner.
     * [fixedBg] = fondo curado de la marca (null = libre).
     */
    fun pick(
        cands: List<LogoCandidate>, cardW: Float, cardH: Float, maxScale: Float,
        order: LogoOrder = LogoOrder.Auto, fixedBg: Int? = null,
    ): LogoCandidate? {
        if (cands.isEmpty()) return null
        // 10 % de margen: a distancia de sofá una ampliación de ese tamaño no se distingue.
        fun enough(c: LogoCandidate) = c.vector || (c.nativeW ?: 0) >= targetW(c, cardW, cardH, maxScale) * 0.9f
        fun bannerOk(c: LogoCandidate): Boolean {
            if (c.origin != LogoOrigin.Banner || !enough(c)) return false
            val bg = c.bg ?: return true
            return fixedBg == null || !OpaqueBounds.differs(bg, fixedBg, 40)
        }
        val fg = cands.filter { it.origin != LogoOrigin.Banner }
        fg.firstOrNull { it.vector }?.let { return it }
        if (order == LogoOrder.ForegroundFirst) fg.firstOrNull { enough(it) }?.let { return it }
        cands.firstOrNull { bannerOk(it) }?.let { return it }
        // El raster más grande (el banner solo si su fondo es compatible con el fijo).
        return cands.filter { it.origin != LogoOrigin.Banner || fixedBg == null || it.bg == null || !OpaqueBounds.differs(it.bg, fixedBg, 40) }
            .maxByOrNull { it.nativeW ?: Int.MAX_VALUE }
    }
}

/** ¿Un banner raster cubre una tarjeta de [targetW] px de ancho y proporción [aw]:[ah] sin ampliarse? Vector (null) siempre. */
object BannerFit {
    fun sharp(srcW: Int?, srcH: Int?, targetW: Int, aw: Float = IconGeometry.AspectW, ah: Float = IconGeometry.AspectH): Boolean {
        if (srcW == null || srcH == null) return true
        val targetH = ceil(targetW * ah / aw).toInt()
        return srcW >= targetW && srcH >= targetH
    }
}
