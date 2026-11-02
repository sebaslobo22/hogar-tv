package live.hogar.launcher

import kotlin.math.abs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.ScaleDrawable
import android.graphics.drawable.StateListDrawable
import android.graphics.drawable.VectorDrawable
import android.util.DisplayMetrics
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import live.hogar.design.Hogar
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Icono en capas, a píxeles reales (nunca ampliado), listo para el parallax:
 *  - [back]: capa de fondo 5:3 solo si el fondo del icono es un VECTOR (null = se pinta el degradado
 *    [backTop]→[backBottom], nítido a cualquier tamaño) o el banner original (si es nítido);
 *  - [front]: logo recortado a su caja opaca, de [frontW]×[frontH] de la tarjeta (fracciones) y nunca
 *    mayor que su tamaño real con el zoom de foco; [glyph] = glifo propio dibujado como vector.
 */
class LayeredIcon(
    val back: ImageBitmap?,
    val backTop: Int,
    val backBottom: Int,
    val front: ImageBitmap?,
    val frontW: Float,
    val frontH: Float,
    val glyph: GlyphKind?,
    val isBanner: Boolean,
    val bytes: Int,
)

/**
 * Arte del Top Shelf, SIN raster de la app ampliado: degradado de marca ([bgTop]→[bgBottom], se pinta
 * con un pincel), luz ambiente de baja resolución ([ambient], solo degradados suaves) y el logo
 * nítido ([logo] a ≤ su tamaño real, o [glyph] vectorial) en la caja [box] (fracciones del Top Shelf).
 */
class ShelfArt(
    val bgTop: Int,
    val bgBottom: Int,
    val ambient: ImageBitmap,
    val logo: ImageBitmap?,
    val glyph: GlyphKind?,
    val boxLeft: Float,
    val boxTop: Float,
    val boxW: Float,
    val boxH: Float,
    val bytes: Int,
)

/** Calidad de cada fuente gráfica de una app (para depurar y decidir): icono, capas del icono adaptable y banner. */
class SourceReport(val icon: Source, val iconBackground: Source, val iconForeground: Source, val banner: Source) {
    override fun toString() = "icon=$icon bg=$iconBackground fg=$iconForeground banner=$banner"
}

/** Logo elegido: de dónde sale, su caja opaca dentro del análisis y el tamaño de ese análisis. */
internal class LogoPlan(val cand: LogoCandidate, val rect: OpaqueBounds.Rect, val aw: Int, val ah: Int)

/** Decisión de marca de una app: la comparten la tarjeta y el Top Shelf, así que son coherentes. */
internal class Art(
    val palette: CardPalette,
    val report: SourceReport,
    val glyph: GlyphKind?,
    val logo: LogoPlan?,
    val bgVector: Boolean,
)

/**
 * Acceso a la paleta de marca, las capas de icono y el arte del Top Shelf de cada app. Todo lo
 * pesado corre fuera del hilo principal. Los mapas de bits viven en un LruCache de
 * `maxMemory / 6` medido en bytes reales; las paletas y decisiones (unos pocos bytes) en mapas aparte.
 */
object AppArt {
    private val palettes = ConcurrentHashMap<String, CardPalette>()
    private val arts = ConcurrentHashMap<String, Art>()
    private val noBanner = ConcurrentHashMap.newKeySet<String>()

    private val bitmaps = object : LruCache<String, Any>((Runtime.getRuntime().maxMemory() / 6).toInt().coerceAtLeast(4 shl 20)) {
        override fun sizeOf(key: String, value: Any): Int = when (value) {
            is LayeredIcon -> value.bytes
            is ShelfArt -> value.bytes
            else -> 1
        }
    }

    private fun layersKey(pkg: String, banner: Boolean) = "$pkg:layers:${if (banner) "b" else "i"}"

    fun peekPalette(pkg: String): CardPalette? = palettes[pkg]
    fun peekLayers(pkg: String, banner: Boolean): LayeredIcon? = bitmaps.get(layersKey(pkg, banner)) as? LayeredIcon
    fun peekShelf(pkg: String): ShelfArt? = bitmaps.get("$pkg:shelf") as? ShelfArt
    fun hasNoBanner(pkg: String) = pkg in noBanner

    /** Paleta de la tarjeta: curada si es una marca conocida; si no, de la capa de fondo del icono o su color dominante. */
    fun palette(ctx: Context, app: AppEntry): CardPalette {
        palettes[app.packageName]?.let { return it }
        val curated = BrandPalettes.match(app.packageName, app.label)
        val result = curated ?: run {
            val icon = highResIcon(ctx, app)
            val bg = ((icon as? AdaptiveIconDrawable)?.background as? ColorDrawable)?.color
            if (bg != null) BrandPalettes.fromBackground(bg, app.packageName)
            else BrandPalettes.derive(dominantOfIcon(icon), app.packageName)
        }
        palettes[app.packageName] = result
        return result
    }

    /** ¿Tiene la app banner de TV? (comprobación barata: solo ids de recurso). */
    fun hasBanner(ctx: Context, app: AppEntry): Boolean {
        if (app.packageName in noBanner) return false
        val ok = runCatching {
            val info = ctx.packageManager.getActivityInfo(app.component, 0)
            info.banner != 0 || info.applicationInfo.banner != 0
        }.getOrDefault(false)
        if (!ok) noBanner.add(app.packageName)
        return ok
    }

    /** Sonda de calidad: icono (xxxhdpi, resolviendo capas) y banner, cada uno Vector, Color o Raster(ancho, alto). */
    fun probe(ctx: Context, app: AppEntry): SourceReport = report(highResIcon(ctx, app), bannerDrawable(ctx, app))

    private fun report(icon: Drawable?, banner: Drawable?): SourceReport {
        val ad = icon as? AdaptiveIconDrawable
        return SourceReport(
            icon = SourceQuality.classify(node(icon)),
            iconBackground = SourceQuality.classify(node(ad?.background)),
            iconForeground = SourceQuality.classify(node(ad?.foreground)),
            banner = SourceQuality.classify(node(banner)),
        )
    }

    // ------------------------------------------------------------ capas de la tarjeta

    /**
     * Tarjeta 5:3 de la app, a píxeles reales para el zoom máximo de foco. Fondo = el de la marca
     * (curado; o capa de fondo del icono si es color/vector; o color dominante) y logo = la mejor fuente
     * nítida, recortado y con tamaño tvOS. Con [preferBanner] y un banner que cubre la tarjeta sin
     * ampliarse (vector o raster suficiente) el banner es el fondo; si no, se compone igual.
     */
    fun layers(ctx: Context, app: AppEntry, widthDp: Float, preferBanner: Boolean): LayeredIcon? {
        val key = layersKey(app.packageName, preferBanner)
        (bitmaps.get(key) as? LayeredIcon)?.let { return it }
        val density = ctx.resources.displayMetrics.density
        val cardW = widthDp * density
        val cardH = IconGeometry.heightForWidth(widthDp) * density
        val result = runCatching {
            val art = art(ctx, app, widthDp)
            // Un icono antiguo opaco con degradado propio se vería como un cuadro dentro de la
            // tarjeta: si el banner de la app la cubre nítido, se usa el banner completo.
            val blockLogo = art.logo?.cand?.let { !it.vector && it.bg == null && it.origin == LogoOrigin.Icon } == true
            val banner = if (preferBanner || blockLogo) bannerDrawable(ctx, app) else null
            val bs = art.report.banner
            val backW = IconGeometry.backWidthPx(widthDp, density)
            val sharp = banner != null && when (bs) {
                is Source.Vector -> true
                // 15 % de margen, solo con el zoom de foco: en reposo el banner incluso se reduce.
                is Source.Raster -> BannerFit.sharp(bs.w, bs.h, (backW * 0.85f).toInt())
                else -> false
            }
            if (sharp) bannerLayers(banner!!, bs, backW)
            else composeLayers(ctx, app, art, widthDp, cardW, cardH)
        }.getOrNull() ?: return null
        bitmaps.put(key, result)
        return result
    }

    private fun bannerLayers(banner: Drawable, src: Source, backW: Int): LayeredIcon {
        val nw = (src as? Source.Raster)?.w
        val nh = (src as? Source.Raster)?.h
        val (aw, ah) = aspectOf(banner, 16f to 9f)
        val (w, h) = IconGeometry.coverRaster(backW, IconGeometry.AspectW, IconGeometry.AspectH, nw, nh)
        val bmp = renderCover(banner, aw, ah, w, h)
        return LayeredIcon(bmp.asImageBitmap(), 0, 0, null, 0f, 0f, null, true, bmp.byteCount)
    }

    private fun composeLayers(ctx: Context, app: AppEntry, art: Art, widthDp: Float, cardW: Float, cardH: Float): LayeredIcon {
        val pal = art.palette
        var bytes = 0
        var back: ImageBitmap? = null
        if (art.bgVector) {
            val bg = (highResIcon(ctx, app) as? AdaptiveIconDrawable)?.background
            if (bg != null) {
                val density = ctx.resources.displayMetrics.density
                val (aw, ah) = aspectOf(bg, 1f to 1f)
                val (w, h) = IconGeometry.coverRaster(IconGeometry.backWidthPx(widthDp, density), IconGeometry.AspectW, IconGeometry.AspectH, null, null)
                val bmp = renderCover(bg, aw, ah, w, h)
                bytes += bmp.byteCount
                back = bmp.asImageBitmap()
            }
        }
        val scale = IconGeometry.FocusScale
        if (art.glyph != null) {
            val s = LogoSizing.card(cardW, cardH, 1f, null, null, scale)
            return LayeredIcon(back, pal.top, pal.bottom, null, s.w / cardW, s.h / cardH, art.glyph, false, bytes)
        }
        val plan = art.logo
        if (plan != null) {
            val c = plan.cand
            val s = LogoSizing.card(cardW, cardH, c.aspect, c.nativeW, c.nativeH, scale)
            val (px, py) = LogoSizing.bitmapPx(s.w, s.h, scale, c.nativeW, c.nativeH)
            val bmp = renderLogo(ctx, app, plan, px, py)
            if (bmp != null) {
                bytes += bmp.byteCount
                return LayeredIcon(back, pal.top, pal.bottom, bmp.asImageBitmap(), s.w / cardW, s.h / cardH, null, false, bytes)
            }
        }
        return LayeredIcon(back, pal.top, pal.bottom, null, 0f, 0f, null, false, bytes)
    }

    // ------------------------------------------------------------ Top Shelf

    /**
     * Arte del Top Shelf (ancho × alto en dp de pantalla). Fondo de marca en degradado + luz ambiente
     * de baja resolución + el logo grande a la izquierda, nítido: vector a su tamaño exacto, raster
     * a ≤ su tamaño real (si es pequeño, se ve pequeño en vez de borroso). Jamás una app ampliada.
     */
    fun shelf(ctx: Context, app: AppEntry, widthDp: Float, heightDp: Float, cellWidthDp: Float): ShelfArt? {
        val key = "${app.packageName}:shelf"
        (bitmaps.get(key) as? ShelfArt)?.let { return it }
        val density = ctx.resources.displayMetrics.density
        val w = widthDp * density
        val h = heightDp * density
        val result = runCatching {
            val art = art(ctx, app, cellWidthDp)
            val pal = art.palette
            val left = Hogar.Space.SafeH.value * density
            var bytes = 0
            var logo: ImageBitmap? = null
            var box = LogoSizing.shelf(w, h, left, 1f, null, null)
            if (art.glyph == null && art.logo != null) {
                val c = art.logo.cand
                box = LogoSizing.shelf(w, h, left, c.aspect, c.nativeW, c.nativeH)
                val (px, py) = LogoSizing.bitmapPx(box.w, box.h, 1f, c.nativeW, c.nativeH)
                renderLogo(ctx, app, art.logo, px, py)?.let { bytes += it.byteCount; logo = it.asImageBitmap() }
            }
            val amb = bakeAmbient(pal.top, pal.glow, box, w, h)
            bytes += amb.byteCount
            ShelfArt(
                pal.top, pal.bottom, amb.asImageBitmap(), logo, art.glyph.takeIf { logo == null },
                box.left / w, box.top / h, box.w / w, box.h / h, bytes,
            )
        }.getOrNull() ?: return null
        bitmaps.put(key, result)
        return result
    }

    /**
     * Luz ambiente tvOS en un mapa MUY pequeño (256×64, solo degradados suaves, sin detalle): resplandor tras el
     * logo, viñeta, velo lateral para el texto, velo superior para la barra y fundido inferior.
     */
    private fun bakeAmbient(bgTop: Int, glow: Int, box: LogoSizing.ShelfBox, shelfW: Float, shelfH: Float): Bitmap {
        val w = 256
        val h = max(16, (w * shelfH / shelfW).roundToInt())
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val light = Hsv.luminance(bgTop) > 0.66f
        // Resplandor tras el logo: blanco teñido por la marca.
        val cx = (box.left + box.w / 2f) / shelfW * w
        val cy = (box.top + box.h / 2f) / shelfH * h
        val tint = blend(0xFFFFFFFF.toInt(), glow, 0.25f)
        val hiA = if (light) 0.10f else 0.26f
        p.shader = RadialGradient(cx, cy, w * 0.46f, intArrayOf(withAlpha(tint, hiA), withAlpha(tint, hiA * 0.35f), withAlpha(tint, 0f)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        // Viñeta.
        p.shader = RadialGradient(
            w * 0.5f, h * 0.45f, w * 0.78f,
            intArrayOf(0x00000000, 0x00000000, 0x59000000), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        // Velo lateral (texto) y superior (barra).
        p.shader = LinearGradient(0f, 0f, w * 0.72f, 0f, 0x80000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = LinearGradient(0f, 0f, 0f, h * 0.38f, if (light) 0x70000000 else 0x55000000, 0x00000000, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        // Fundido inferior a transparente (igual que el del degradado de marca).
        p.shader = LinearGradient(0f, h * 0.45f, 0f, h.toFloat(), 0xFF000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        return bmp
    }

    private fun withAlpha(argb: Int, a: Float) = (argb and 0xFFFFFF) or ((a.coerceIn(0f, 1f) * 255).roundToInt() shl 24)
    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = (((a shr s) and 255) * (1 - t) + ((b shr s) and 255) * t).roundToInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    // ------------------------------------------------------------ decisión de marca

    /**
     * Decisión de marca de la app (una vez): paleta final, glifo propio o logo elegido entre el primer
     * plano del icono, el banner y el icono antiguo, según [LogoChoice]. [cellWidthDp] fija la tarjeta
     * para saber cuánto logo hace falta.
     */
    internal fun art(ctx: Context, app: AppEntry, cellWidthDp: Float): Art {
        arts[app.packageName]?.let { return it }
        val density = ctx.resources.displayMetrics.density
        val cardW = cellWidthDp * density
        val cardH = IconGeometry.heightForWidth(cellWidthDp) * density
        val spec = BrandPalettes.spec(app.packageName, app.label)
        var pal = palette(ctx, app)
        val icon = highResIcon(ctx, app)
        val bannerD = bannerDrawable(ctx, app)
        val rep = report(icon, bannerD)
        val ad = icon as? AdaptiveIconDrawable

        var glyph: GlyphKind? = if (spec?.glyphAlways == true) spec.glyph else null
        var plan: LogoPlan? = null
        if (glyph == null) {
            val plans = ArrayList<LogoPlan>(2)
            val fgD: Drawable? = ad?.foreground ?: if (ad == null) icon else null
            val fgSrc = if (ad != null) rep.iconForeground else rep.icon
            val origin = if (ad != null) LogoOrigin.Foreground else LogoOrigin.Icon
            if (fgD != null && (fgSrc is Source.Vector || fgSrc is Source.Raster)) analyse(fgD, fgSrc, origin, false)?.let { plans += it }
            // Con un primer plano vector el banner ni se mira (gana siempre).
            val fgVector = plans.firstOrNull()?.cand?.vector == true
            if (!fgVector && bannerD != null && (rep.banner is Source.Vector || rep.banner is Source.Raster)) {
                analyse(bannerD, rep.banner, LogoOrigin.Banner, true)?.let { plans += it }
            }
            // El fondo propio del logo manda sobre el curado: así no queda un bloque de otro color.
            val pick = LogoChoice.pick(plans.map { it.cand }, cardW, cardH, IconGeometry.FocusScale, spec?.logoOrder ?: LogoOrder.Auto, null)
            plan = plans.firstOrNull { it.cand === pick }
            if (plan == null && spec?.glyph != null) glyph = spec.glyph
            val bg = plan?.cand?.bg
            if (plan != null && bg != null) {
                pal = BrandPalettes.solid(bg, pal)
                palettes[app.packageName] = pal
            }
        }
        val bgVector = spec == null && rep.iconBackground is Source.Vector && plan?.cand?.origin != LogoOrigin.Banner
        val art = Art(pal, rep, glyph, plan, bgVector)
        arts[app.packageName] = art
        Log.i(
            "HogarArt",
            "${app.packageName}: $rep -> " + when {
                glyph != null -> "glifo $glyph"
                plan != null -> "logo ${plan.cand.origin} ${if (plan.cand.vector) "vector" else "${plan.cand.nativeW}x${plan.cand.nativeH}"} " +
                    "aspect=${String.format(java.util.Locale.US, "%.2f", plan.cand.aspect)}${if (LogoSizing.isWide(plan.cand.aspect)) " ancho" else " simbolo"}"
                else -> "solo fondo"
            } + " bg=#${Integer.toHexString(pal.top)}->#${Integer.toHexString(pal.bottom)}${if (spec != null) " (curado)" else ""}",
        )
        return art
    }

    // ------------------------------------------------------------ análisis y dibujo del logo

    /** Tamaño de análisis: raster a su tamaño real (tope 1536); vector/banner a ~512 px de lado largo. */
    private fun analysisSize(d: Drawable, src: Source, banner: Boolean): Pair<Int, Int> {
        if (src is Source.Raster) {
            val k = min(1f, 1536f / max(src.w, src.h))
            return max(1, (src.w * k).roundToInt()) to max(1, (src.h * k).roundToInt())
        }
        val iw = d.intrinsicWidth; val ih = d.intrinsicHeight
        val (aw, ah) = if (iw > 0 && ih > 0) iw.toFloat() to ih.toFloat() else if (banner) 16f to 9f else 1f to 1f
        val k = 512f / max(aw, ah)
        return max(1, (aw * k).roundToInt()) to max(1, (ah * k).roundToInt())
    }

    private fun drawAnalysis(d: Drawable, aw: Int, ah: Int): Bitmap {
        val bmp = Bitmap.createBitmap(aw, ah, Bitmap.Config.ARGB_8888)
        val copy = d.constantState?.newDrawable()?.mutate() ?: d
        copy.setBounds(0, 0, aw, ah)
        copy.draw(Canvas(bmp))
        return bmp
    }

    /** Recorta los márgenes del logo (una vez) y lo describe; null si no hay logo utilizable. */
    private fun analyse(d: Drawable, src: Source, origin: LogoOrigin, banner: Boolean): LogoPlan? = runCatching {
        val (aw, ah) = analysisSize(d, src, banner)
        val bmp = drawAnalysis(d, aw, ah)
        val px = IntArray(aw * ah)
        bmp.getPixels(px, 0, aw, 0, 0, aw, ah)
        bmp.recycle()
        // Logo con fondo propio liso (banner, o icono antiguo opaco): se recorta por ese color y la
        // tarjeta lo adopta, para que el logo se funda en vez de verse como un cuadro.
        val opaque = px[0] ushr 24 >= 250
        val bg = if (banner || opaque) OpaqueBounds.uniformBackground(px, aw, ah) else null
        if (banner && bg == null && px[0] ushr 24 >= 250) return@runCatching null // banner con foto/degradado: no es un logo
        val rect = (if (bg != null) OpaqueBounds.keyed(px, aw, ah, bg) else OpaqueBounds.alpha(px, aw, ah)) ?: return@runCatching null
        if (rect.w < 4 || rect.h < 4) return@runCatching null
        val raster = src is Source.Raster
        LogoPlan(
            LogoCandidate(origin, rect.w.toFloat() / rect.h, if (raster) rect.w else null, if (raster) rect.h else null, bg),
            rect, aw, ah,
        )
    }.getOrNull()

    /** Dibuja el logo recortado en un mapa de [tw]×[th] px exactos: vector a su tamaño; raster solo reducido. */
    private fun renderLogo(ctx: Context, app: AppEntry, plan: LogoPlan, tw: Int, th: Int): Bitmap? = runCatching {
        renderLogoRaw(ctx, app, plan, tw, th)?.let { bmp -> plan.cand.bg?.let { keyOut(bmp, it) } ?: bmp }
    }.getOrNull()

    private fun renderLogoRaw(ctx: Context, app: AppEntry, plan: LogoPlan, tw: Int, th: Int): Bitmap? = runCatching {
        val d = drawableFor(ctx, app, plan.cand.origin) ?: return@runCatching null
        val r = plan.rect
        if (plan.cand.vector) {
            val sx = tw.toFloat() / r.w
            val sy = th.toFloat() / r.h
            val bmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
            val copy = d.constantState?.newDrawable()?.mutate() ?: d
            copy.setBounds(
                (-r.l * sx).roundToInt(), (-r.t * sy).roundToInt(),
                ((plan.aw - r.l) * sx).roundToInt(), ((plan.ah - r.t) * sy).roundToInt(),
            )
            copy.draw(Canvas(bmp))
            bmp
        } else {
            val full = drawAnalysis(d, plan.aw, plan.ah)
            val crop = Bitmap.createBitmap(full, r.l, r.t, r.w, r.h)
            if (crop !== full) full.recycle()
            scaleDown(crop, tw, th)
        }
    }.getOrNull()

    /** Reduce por mitades (calidad) hasta [tw]×[th]; jamás amplía. */
    private fun scaleDown(src: Bitmap, tw: Int, th: Int): Bitmap {
        var cur = src
        while (cur.width / 2 >= tw && cur.height / 2 >= th) {
            val next = Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
            if (cur !== src && cur !== next) cur.recycle()
            cur = next
        }
        if (cur.width != tw || cur.height != th) {
            val next = Bitmap.createScaledBitmap(cur, min(tw, cur.width), min(th, cur.height), true)
            if (cur !== src && cur !== next) cur.recycle()
            cur = next
        }
        return cur
    }

    private fun drawableFor(ctx: Context, app: AppEntry, origin: LogoOrigin): Drawable? = when (origin) {
        LogoOrigin.Banner -> bannerDrawable(ctx, app)
        LogoOrigin.Icon -> highResIcon(ctx, app)
        LogoOrigin.Foreground -> (highResIcon(ctx, app) as? AdaptiveIconDrawable)?.foreground
    }

    /** Árbol de [d] para clasificar la fuente (ver [SourceQuality]). */
    internal fun node(d: Drawable?): DNode? = when (d) {
        null -> null
        is BitmapDrawable -> d.bitmap?.let { DNode.Bmp(it.width, it.height) } ?: DNode.Other(0, 0)
        is ColorDrawable -> DNode.Col(d.color or (0xFF shl 24))
        is VectorDrawable, is AnimatedVectorDrawable, is GradientDrawable -> DNode.Vec
        is InsetDrawable -> DNode.Inset(node(d.drawable))
        is ScaleDrawable -> DNode.Scale(node(d.drawable))
        is LayerDrawable -> DNode.Layers((0 until d.numberOfLayers).map { node(d.getDrawable(it)) })
        is AdaptiveIconDrawable -> DNode.Layers(listOf(node(d.background), node(d.foreground)))
        is StateListDrawable -> node(d.current)
        else -> DNode.Other(d.intrinsicWidth, d.intrinsicHeight)
    }

    /** Proporción intrínseca de [d]; sin ella (degradados, colores) se usa [fallback]. */
    private fun aspectOf(d: Drawable, fallback: Pair<Float, Float>): Pair<Float, Float> {
        val w = d.intrinsicWidth
        val h = d.intrinsicHeight
        return if (w > 0 && h > 0) w.toFloat() to h.toFloat() else fallback
    }

    /** Dibuja [d] (de proporción [aspectW]:[aspectH]) cubriendo un mapa de [w]×[h]. */
    private fun renderCover(d: Drawable, aspectW: Float, aspectH: Float, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val box = IconGeometry.cover(aspectW, aspectH, w.toFloat(), h.toFloat())
        val c = Canvas(bmp)
        val copy = d.constantState?.newDrawable()?.mutate() ?: d
        copy.setBounds(box.left.roundToInt(), box.top.roundToInt(), box.right.roundToInt(), box.bottom.roundToInt())
        copy.draw(c)
        return bmp
    }

    // ------------------------------------------------------------ recursos de la app

    /** Banner de TV al máximo de resolución (xxxhdpi) o, si falla, el que da el sistema. */
    private fun bannerDrawable(ctx: Context, app: AppEntry): Drawable? {
        if (app.packageName in noBanner) return null
        val pm = ctx.packageManager
        val d = runCatching {
            val info = pm.getActivityInfo(app.component, 0)
            val id = info.banner.takeIf { it != 0 } ?: info.applicationInfo.banner
            val hi = if (id != 0) {
                runCatching {
                    pm.getResourcesForApplication(info.applicationInfo).getDrawableForDensity(id, DisplayMetrics.DENSITY_XXXHIGH, null)
                }.getOrNull()
            } else null
            hi ?: info.loadBanner(pm) ?: info.applicationInfo.loadBanner(pm)
        }.getOrNull()
        if (d == null) noBanner.add(app.packageName)
        return d
    }

    /** Icono de la mayor resolución disponible (xxxhdpi) o, si falla, el sin insignia. */
    private fun highResIcon(ctx: Context, app: AppEntry): Drawable? {
        val pm = ctx.packageManager
        return runCatching {
            val info = pm.getActivityInfo(app.component, 0)
            val resId = info.icon.takeIf { it != 0 } ?: info.applicationInfo.icon
            val hi = if (resId != 0) {
                runCatching {
                    pm.getResourcesForApplication(info.applicationInfo)
                        .getDrawableForDensity(resId, DisplayMetrics.DENSITY_XXXHIGH, null)
                }.getOrNull()
            } else null
            (hi ?: info.loadUnbadgedIcon(pm))?.let { it.constantState?.newDrawable()?.mutate() ?: it }
        }.getOrNull()
    }

    private fun dominantOfIcon(d: Drawable?): Int? {
        d ?: return null
        val layers: List<Drawable> =
            if (d is AdaptiveIconDrawable) listOfNotNull(d.background, d.foreground) else listOf(d)
        for (layer in layers) {
            if (layer is ColorDrawable) {
                val c = layer.color or (0xFF shl 24)
                if (DominantColor.extract(intArrayOf(c), 1) != null && Hsv.fromRgb((c shr 16) and 255, (c shr 8) and 255, c and 255)[1] > 0.25f) return c
                continue
            }
            val px = sample(layer, 48) ?: continue
            val c = DominantColor.extract(px)
            if (c != null && Hsv.fromRgb((c shr 16) and 255, (c shr 8) and 255, c and 255)[1] > 0.22f) return c
        }
        // Sin color vivo en ninguna capa: lo mejor que haya (puede ser gris -> paleta neutra).
        return layers.firstNotNullOfOrNull { l -> sample(l, 48)?.let { DominantColor.extract(it) } }
    }

    private fun sample(d: Drawable, n: Int): IntArray? = runCatching {
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        d.mutate().setBounds(0, 0, n, n)
        d.draw(Canvas(bmp))
        IntArray(n * n).also { bmp.getPixels(it, 0, n, 0, 0, n, n) }.also { bmp.recycle() }
    }.getOrNull()

    /**
     * Quita el fondo liso propio de un logo (el rectángulo blanco de un banner, por ejemplo) con un
     * borde suave, para que en el Top Shelf solo quede el logo. En la tarjeta no cambia nada: su fondo
     * ya es ese mismo color.
     */
    private fun keyOut(src: Bitmap, bg: Int): Bitmap {
        val bmp = if (src.isMutable) src else src.copy(Bitmap.Config.ARGB_8888, true).also { if (it !== src) src.recycle() }
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val br = (bg shr 16) and 255; val bgG = (bg shr 8) and 255; val bb = bg and 255
        for (k in px.indices) {
            val c = px[k]
            val d = abs(((c shr 16) and 255) - br) + abs(((c shr 8) and 255) - bgG) + abs((c and 255) - bb)
            val a = ((d - 18) * 255 / 60).coerceIn(0, 255) * (c ushr 24) / 255
            px[k] = (a shl 24) or (c and 0xFFFFFF)
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        return bmp
    }
}
