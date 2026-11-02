package live.hogar.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BrandArtTest {
    private val scale = IconGeometry.FocusScale
    private val cardW = 340f
    private val cardH = 204f

    // ---- clasificación de la fuente
    @Test fun classifiesPlainSources() {
        assertEquals(Source.Vector, SourceQuality.classify(DNode.Vec))
        assertEquals(Source.Color(0xFF112233.toInt()), SourceQuality.classify(DNode.Col(0xFF112233.toInt())))
        assertEquals(Source.Raster(320, 180), SourceQuality.classify(DNode.Bmp(320, 180)))
        assertEquals(Source.None, SourceQuality.classify(null))
        assertEquals(Source.None, SourceQuality.classify(DNode.Bmp(0, 0)))
    }

    @Test fun unwrapsInsetScaleAndLayers() {
        assertEquals(Source.Raster(432, 432), SourceQuality.classify(DNode.Inset(DNode.Bmp(432, 432))))
        assertEquals(Source.Vector, SourceQuality.classify(DNode.Scale(DNode.Inset(DNode.Vec))))
        // Capa vectorial + capa raster = raster (lo prudente); toma el mayor.
        val mix = DNode.Layers(listOf(DNode.Vec, DNode.Bmp(100, 80), DNode.Inset(DNode.Bmp(200, 50))))
        assertEquals(Source.Raster(200, 80), SourceQuality.classify(mix))
        assertEquals(Source.Vector, SourceQuality.classify(DNode.Layers(listOf(DNode.Col(0xFF000000.toInt()), DNode.Vec))))
        assertEquals(Source.Color(0xFF000000.toInt()), SourceQuality.classify(DNode.Layers(listOf(null, DNode.Col(0xFF000000.toInt())))))
        assertEquals(Source.None, SourceQuality.classify(DNode.Layers(emptyList())))
    }

    @Test fun unknownDrawableIsRasterOnlyIfItHasSize() {
        assertEquals(Source.Raster(64, 64), SourceQuality.classify(DNode.Other(64, 64)))
        assertEquals(Source.Vector, SourceQuality.classify(DNode.Other(-1, -1)))
    }

    // ---- recorte de márgenes
    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test fun trimsTransparentMargins() {
        val w = 10; val h = 8
        val px = IntArray(w * h)
        for (y in 2..4) for (x in 3..7) px[y * w + x] = argb(255, 200, 0, 0)
        val r = OpaqueBounds.alpha(px, w, h)!!
        assertEquals(OpaqueBounds.Rect(3, 2, 8, 5), r)
        assertEquals(5, r.w); assertEquals(3, r.h)
    }

    @Test fun trimIgnoresFaintAndEmpty() {
        val px = IntArray(16) { argb(10, 255, 255, 255) }
        assertNull(OpaqueBounds.alpha(px, 4, 4))
        assertNull(OpaqueBounds.alpha(IntArray(0), 0, 0))
    }

    @Test fun keyedTrimIgnoresTheSolidBackground() {
        val w = 12; val h = 6
        val white = argb(255, 255, 255, 255)
        val px = IntArray(w * h) { white }
        for (x in 2..9) px[3 * w + x] = argb(255, 229, 9, 20) // wordmark rojo sobre blanco
        assertEquals(white, OpaqueBounds.uniformBackground(px, w, h))
        val r = OpaqueBounds.keyed(px, w, h, white)!!
        assertEquals(OpaqueBounds.Rect(2, 3, 10, 4), r)
        assertTrue(r.w.toFloat() / r.h > LogoSizing.WideAspect)
    }

    @Test fun noUniformBackgroundForPhotoOrTransparent() {
        val w = 8; val h = 8
        val px = IntArray(w * h) { argb(255, it * 3, 20, 40) } // degradado
        assertNull(OpaqueBounds.uniformBackground(px, w, h))
        assertNull(OpaqueBounds.uniformBackground(IntArray(w * h), w, h)) // transparente
    }

    // ---- ancho vs cuadrado
    @Test fun wideLogoUsesSeventyPercentOfCardWidth() {
        val s = LogoSizing.card(cardW, cardH, 4f, null, null, scale)
        assertEquals(cardW * 0.70f, s.w, 0.01f)
        assertEquals(s.w / 4f, s.h, 0.01f)
    }

    @Test fun squareLogoUsesFiftyFivePercentOfCardHeight() {
        val s = LogoSizing.card(cardW, cardH, 1f, null, null, scale)
        assertEquals(cardH * 0.55f, s.h, 0.01f)
        assertEquals(s.h, s.w, 0.01f)
    }

    @Test fun wideThresholdIsTwoToOne() {
        assertFalse(LogoSizing.isWide(2f))
        assertTrue(LogoSizing.isWide(2.01f))
        // Justo bajo 2:1 sigue siendo "símbolo": 55 % del alto.
        val s = LogoSizing.card(cardW, cardH, 1.9f, null, null, scale)
        assertEquals(cardH * 0.55f, s.h, 0.01f)
    }

    // ---- nunca ampliar
    @Test fun rasterCardLogoNeverExceedsNativeAtMaxFocus() {
        // Wordmark raster de 200 px de ancho: pide 238 px en reposo, pero 200/1,14 = 175 como máximo.
        val s = LogoSizing.card(cardW, cardH, 4f, 200, 50, scale)
        assertTrue(s.w * scale <= 200.01f)
        assertTrue(s.h * scale <= 50.01f)
        val (bw, bh) = LogoSizing.bitmapPx(s.w, s.h, scale, 200, 50)
        assertTrue(bw <= 200 && bh <= 50)
    }

    @Test fun largeRasterIsOnlyDownscaled() {
        val s = LogoSizing.card(cardW, cardH, 1f, 1000, 1000, scale)
        assertEquals(cardH * 0.55f, s.h, 0.01f) // no se reduce de más
        val (bw, _) = LogoSizing.bitmapPx(s.w, s.h, scale, 1000, 1000)
        assertTrue(bw < 1000)
    }

    @Test fun vectorLogoIsRenderedAtExactPixels() {
        val s = LogoSizing.card(cardW, cardH, 1f, null, null, scale)
        val (bw, bh) = LogoSizing.bitmapPx(s.w, s.h, scale, null, null)
        assertEquals(kotlin.math.ceil(s.w * scale).toInt(), bw)
        assertEquals(kotlin.math.ceil(s.h * scale).toInt(), bh)
    }

    @Test fun shelfLogoNeverUpscalesRaster() {
        val b = LogoSizing.shelf(1920f, 486f, 96f, 1f, 80, 80)
        assertTrue(b.w <= 80.01f && b.h <= 80.01f)
        val (px, py) = LogoSizing.bitmapPx(b.w, b.h, 1f, 80, 80)
        assertTrue(px <= 80 && py <= 80)
    }

    @Test fun shelfVectorLogoIsLargeAndInsideTheShelf() {
        val sq = LogoSizing.shelf(1920f, 486f, 96f, 1f, null, null)
        assertEquals(486f * 0.42f, sq.h, 0.01f)
        val wide = LogoSizing.shelf(1920f, 486f, 96f, 5f, null, null)
        assertTrue(wide.w <= 1920f * 0.34f + 0.01f)
        assertTrue(wide.top >= 0f && wide.top + wide.h <= 486f * 0.65f)
        assertEquals(96f, wide.left, 0f)
    }

    @Test fun bannerCoverageNeedsRealPixels() {
        assertTrue(BannerFit.sharp(null, null, 412))
        assertTrue(BannerFit.sharp(1280, 720, 412))
        assertFalse(BannerFit.sharp(320, 180, 412))
        assertFalse(BannerFit.sharp(500, 200, 412)) // ancho sobra pero el alto no cubre 5:3
    }

    // ---- elección del logo
    private fun vec(o: LogoOrigin, a: Float = 1f, bg: Int? = null) = LogoCandidate(o, a, null, null, bg)
    private fun ras(o: LogoOrigin, w: Int, h: Int, bg: Int? = null) = LogoCandidate(o, w.toFloat() / h, w, h, bg)

    @Test fun foregroundVectorWinsOverBanner() {
        val fg = vec(LogoOrigin.Foreground)
        val banner = ras(LogoOrigin.Banner, 1280, 320, 0xFFFFFFFF.toInt())
        assertSame(fg, LogoChoice.pick(listOf(banner, fg), cardW, cardH, scale))
    }

    @Test fun sharpBannerBeatsSmallRasterForeground() {
        val fg = ras(LogoOrigin.Foreground, 120, 120)
        val banner = ras(LogoOrigin.Banner, 900, 225, 0xFF101010.toInt())
        assertSame(banner, LogoChoice.pick(listOf(fg, banner), cardW, cardH, scale))
    }

    @Test fun smallBannerLosesToLargerRasterForeground() {
        val fg = ras(LogoOrigin.Foreground, 300, 300)
        val banner = ras(LogoOrigin.Banner, 200, 50, 0xFF101010.toInt())
        assertSame(fg, LogoChoice.pick(listOf(banner, fg), cardW, cardH, scale))
    }

    @Test fun curatedBackgroundRejectsBannerOfAnotherColor() {
        val fg = ras(LogoOrigin.Foreground, 100, 100)
        val banner = ras(LogoOrigin.Banner, 1000, 250, 0xFFFFFFFF.toInt()) // blanco
        assertSame(fg, LogoChoice.pick(listOf(fg, banner), cardW, cardH, scale, fixedBg = 0xFF000000.toInt()))
        // Sobre un fondo curado blanco, el mismo banner sí vale.
        assertSame(banner, LogoChoice.pick(listOf(fg, banner), cardW, cardH, scale, fixedBg = 0xFFFFFFFF.toInt()))
    }

    @Test fun foregroundFirstPrefersSufficientForegroundRaster() {
        val fg = ras(LogoOrigin.Foreground, 400, 400)
        val banner = ras(LogoOrigin.Banner, 900, 225, 0xFFFFFFFF.toInt())
        assertSame(fg, LogoChoice.pick(listOf(fg, banner), cardW, cardH, scale, LogoOrder.ForegroundFirst))
    }

    @Test fun noCandidatesNoLogo() {
        assertNull(LogoChoice.pick(emptyList(), cardW, cardH, scale))
    }

    // ---- marcas curadas
    @Test fun curatedBrandsOfTheTv() {
        fun bg(pkg: String) = BrandPalettes.spec(pkg, "x")!!.palette
        assertEquals(0xFF000000.toInt(), bg("com.netflix.ninja").top)
        assertEquals(LogoOrder.ForegroundFirst, BrandPalettes.spec("com.netflix.ninja", "Netflix")!!.logoOrder)
        assertEquals(0xFFFFFFFF.toInt(), bg("com.google.android.youtube.tv").top)
        assertEquals(0xFF0B1B4F.toInt(), bg("com.disney.disneyplus").top)
        assertEquals(0xFF1A3C8F.toInt(), bg("com.disney.disneyplus").bottom)
        assertEquals(0xFF5822B4.toInt(), bg("com.hbo.hbonow").top)
        assertEquals(0xFF0C0420.toInt(), bg("com.wbd.stream").bottom)
        assertEquals(0xFF000000.toInt(), bg("com.spotify.tv.android").top)
        assertEquals(0xFF00C853.toInt(), bg("com.iqiyi.i18n.tv").top)
        assertEquals(0xFFFFE600.toInt(), bg("com.mercadolibre.android.mplay_tv").top)
        assertEquals(0xFF9146FF.toInt(), bg("tv.twitch.android.app").top)
        assertEquals(0xFF000000.toInt(), bg("com.tiktok.tv").top)
        assertEquals(0xFF76B900.toInt(), bg("com.nvidia.geforcenow").top)
        assertEquals(0xFF171A21.toInt(), bg("com.valvesoftware.steamlink").top)
        assertEquals(0xFFFFFFFF.toInt(), bg("com.android.vending").top)
        assertEquals(0xFFFA2D48.toInt(), bg("com.apple.android.music").top)
        assertEquals(0xFFFC3C44.toInt(), bg("com.apple.android.music").bottom)
        assertEquals(0xFF000000.toInt(), bg("com.apple.atve.androidtv.appletv").top)
    }

    @Test fun youtubeMusicIsNotYoutube() {
        val m = BrandPalettes.spec("com.google.android.youtube.tvmusic", "YouTube Music")!!
        assertEquals(0xFF000000.toInt(), m.palette.top)
        val y = BrandPalettes.spec("com.google.android.youtube.tv", "YouTube")!!
        assertEquals(0xFFFFFFFF.toInt(), y.palette.top)
    }

    @Test fun systemAppsGetOwnGlyphs() {
        assertEquals(GlyphKind.Gear, BrandPalettes.spec("com.android.tv.settings", "Settings")!!.glyph)
        assertTrue(BrandPalettes.spec("com.android.tv.settings", "Settings")!!.glyphAlways)
        assertEquals(GlyphKind.Tv, BrandPalettes.spec("com.smartdevice.livetv", "Live TV")!!.glyph)
        assertEquals(GlyphKind.Bluetooth, BrandPalettes.spec("com.smartdevice.duplebluetooth", "x")!!.glyph)
        assertEquals(GlyphKind.Folder, BrandPalettes.spec("com.tianci.localmedia", "x")!!.glyph)
        // Apple Music usa el logo de su propio icono; la nota solo es respaldo.
        val am = BrandPalettes.spec("com.apple.android.music", "Apple Music")!!
        assertEquals(GlyphKind.Note, am.glyph)
        assertFalse(am.glyphAlways)
    }

    @Test fun unknownAppsAreNotCurated() {
        assertNull(BrandPalettes.spec("com.cisneros.venevision.app", "Venevisión"))
        assertNotNull(BrandPalettes.spec("com.vendor.unknown", "Settings"))
    }

    @Test fun solidAndBackgroundPalettes() {
        val base = BrandPalettes.derive(null, "a.b")
        val s = BrandPalettes.solid(0xFFFFFFFF.toInt(), base)
        assertEquals(0xFFFFFFFF.toInt(), s.top); assertEquals(s.top, s.bottom)
        val f = BrandPalettes.fromBackground(0xFF1A3C8F.toInt(), "a.b")
        assertTrue(Hsv.luminance(f.top) >= Hsv.luminance(f.bottom))
        assertEquals(0xFFFFFFFF.toInt(), f.ink)
    }
}
