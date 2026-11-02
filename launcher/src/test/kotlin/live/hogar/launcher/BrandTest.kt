package live.hogar.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrandTest {
    private fun argb(r: Int, g: Int, b: Int, a: Int = 255) = (a shl 24) or (r shl 16) or (g shl 8) or b
    private fun r(c: Int) = (c shr 16) and 255
    private fun g(c: Int) = (c shr 8) and 255
    private fun b(c: Int) = c and 255

    // ---- saludo y fecha
    @Test fun greetingByHour() {
        assertEquals("Buenas noches", Greeting.forHour(0))
        assertEquals("Buenas noches", Greeting.forHour(4))
        assertEquals("Buenos días", Greeting.forHour(5))
        assertEquals("Buenos días", Greeting.forHour(11))
        assertEquals("Buenas tardes", Greeting.forHour(12))
        assertEquals("Buenas tardes", Greeting.forHour(19))
        assertEquals("Buenas noches", Greeting.forHour(20))
        assertEquals("Buenas noches", Greeting.forHour(23))
    }

    @Test fun longSpanishDate() {
        assertEquals("Domingo, 1 de noviembre", SpanishDate.long(1, 1, 10))
        assertEquals("Miércoles, 25 de diciembre", SpanishDate.long(4, 25, 11))
    }

    // ---- rasterizado
    @Test fun pixelSizeIsDensityTimesDpTimesFocusScale() {
        assertEquals(388, RasterSize.px(176f, 2f, 1.10f)) // 176*2*1.1 = 387.2 -> 388
        assertEquals(75, RasterSize.px(34f, 2f, 1.10f)) // 74.8 -> 75
    }

    @Test fun neverUpscalesSmallSources() {
        assertEquals(320 to 180, RasterSize.fit(388, 218, 320, 180))
        assertEquals(100 to 100, RasterSize.fit(388, 218, 100, 100))
    }

    @Test fun downscalesLargeSourcesKeepingAspect() {
        assertEquals(388 to 218, RasterSize.fit(388, 218, 1280, 720))
        val (w, h) = RasterSize.fit(100, 100, 400, 200)
        assertEquals(100, w); assertEquals(50, h)
    }

    @Test fun unknownSourceUsesTarget() {
        assertEquals(75 to 75, RasterSize.fit(75, 75, null, null))
    }

    @Test fun wordmarkShrinksWithLength() {
        assertEquals(26f, Wordmark.sizeSp(5), 0f)
        assertTrue(Wordmark.sizeSp(16) < Wordmark.sizeSp(10))
        assertEquals(15f, Wordmark.sizeSp(60), 0f)
    }

    // ---- k-means
    @Test fun noPixelsNoColor() {
        assertNull(DominantColor.extract(IntArray(0)))
        assertNull(DominantColor.extract(IntArray(50) { argb(255, 0, 0, a = 0) }))
    }

    @Test fun picksTheBrandColorOverBackground() {
        // 70 % blanco, 30 % rojo Netflix: el blanco no es "marca".
        val px = IntArray(1000) { if (it < 700) argb(255, 255, 255) else argb(229, 9, 20) }
        val c = DominantColor.extract(px)!!
        assertTrue(r(c) > 200 && g(c) < 40 && b(c) < 50)
    }

    @Test fun ignoresTransparentPixels() {
        val px = IntArray(1000) { if (it < 900) argb(0, 0, 0, a = 0) else argb(0, 168, 225) }
        val c = DominantColor.extract(px)!!
        assertTrue(b(c) > 200 && r(c) < 30)
    }

    @Test fun prefersSaturatedOverNearBlack() {
        val px = IntArray(1000) { if (it < 600) argb(5, 5, 5) else argb(30, 185, 84) }
        val c = DominantColor.extract(px)!!
        assertTrue(g(c) > 150)
    }

    @Test fun separatesTwoBlobs() {
        val px = IntArray(2000) { if (it % 2 == 0) argb(0, 0, 255) else argb(255, 200, 0) }
        val c = DominantColor.extract(px, k = 2)!!
        assertTrue((b(c) > 200 && r(c) < 30) || (r(c) > 200 && g(c) > 150))
    }

    // ---- paletas
    @Test fun curatedPalettesByPackage() {
        val n = BrandPalettes.match("com.netflix.ninja", "Netflix")!!
        assertEquals(0xFFE50914.toInt(), n.glow)
        assertTrue(n.curated)
        assertNotNull(BrandPalettes.match("com.spotify.tv.android", "Spotify"))
        assertNotNull(BrandPalettes.match("com.google.android.youtube.tv", "YouTube"))
        assertNotNull(BrandPalettes.match("com.crunchyroll.crunchyroid", "Crunchyroll"))
    }

    @Test fun curatedByLabelWhenPackageUnknown() {
        assertNotNull(BrandPalettes.match("com.vendor.unknown", "Star+"))
        assertNotNull(BrandPalettes.match("com.vendor.unknown", "HBO Max"))
        assertNull(BrandPalettes.match("com.vendor.unknown", "Calculadora"))
    }

    @Test fun derivedPaletteFromAccent() {
        val p = BrandPalettes.derive(argb(30, 185, 84), "x.y")
        assertTrue(!p.curated)
        assertTrue(Hsv.luminance(p.top) > Hsv.luminance(p.bottom))
        assertEquals(0xFFFFFFFF.toInt(), p.ink)
    }

    @Test fun derivedPaletteForGrayIsNeutralAndStable() {
        val a = BrandPalettes.derive(null, "com.some.app")
        val b2 = BrandPalettes.derive(null, "com.some.app")
        assertEquals(a, b2)
        assertTrue(Hsv.fromRgb(r(a.top), g(a.top), b(a.top))[1] < 0.2f)
    }

    @Test fun hsvRoundTrip() {
        val c = argb(200, 80, 40)
        val h = Hsv.fromRgb(r(c), g(c), b(c))
        val back = Hsv.toArgb(h[0], h[1], h[2])
        assertTrue(kotlin.math.abs(r(back) - 200) <= 1 && kotlin.math.abs(g(back) - 80) <= 1 && kotlin.math.abs(b(back) - 40) <= 1)
    }
}
