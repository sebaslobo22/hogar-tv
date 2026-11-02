package live.hogar.nowplaying.light

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtPaletteTest {
    private fun image(w: Int, h: Int, f: (Int, Int) -> Int): IntArray = IntArray(w * h) { f(it % w, it / w) }
    private fun hue(argb: Int): Float {
        val r = ((argb shr 16) and 0xFF) / 255f; val g = ((argb shr 8) and 0xFF) / 255f; val b = (argb and 0xFF) / 255f
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b); val d = mx - mn
        if (d == 0f) return 0f
        val h = when (mx) { r -> 60f * (((g - b) / d) % 6f); g -> 60f * ((b - r) / d + 2f); else -> 60f * ((r - g) / d + 4f) }
        return if (h < 0) h + 360f else h
    }

    @Test fun twoHalvesGiveTwoDistinctColorsByProminence() {
        // 70 % rojo, 30 % azul
        val px = image(100, 100) { x, _ -> if (x < 70) 0xFFD02020.toInt() else 0xFF2030D0.toInt() }
        val r = ArtPalette.extract(px, 100, 100)
        assertFalse(r.monochrome)
        assertEquals(2, r.colors.size)
        assertTrue("el rojo (mayoritario) va primero", hue(r.colors[0]).let { it < 20f || it > 340f })
        assertTrue(hue(r.colors[1]) in 220f..260f)
    }

    @Test fun nearbyShadesMergeIntoOne() {
        val px = image(100, 100) { x, _ -> if (x < 50) 0xFFD02020.toInt() else 0xFFD82424.toInt() }
        assertEquals(1, ArtPalette.extract(px, 100, 100).colors.size)
    }

    @Test fun blackAndWhiteAndGreyAreDroppedWhenThereIsColor() {
        // fondo negro + banda blanca + 30 % verde
        val px = image(100, 100) { x, _ -> when { x < 40 -> 0xFF000000.toInt(); x < 70 -> 0xFFFFFFFF.toInt(); else -> 0xFF10B040.toInt() } }
        val r = ArtPalette.extract(px, 100, 100)
        assertEquals(1, r.colors.size)
        assertTrue(hue(r.colors[0]) in 110f..160f)
    }

    @Test fun monochromeArtGivesGreysNotColors() {
        val px = image(64, 64) { x, y -> val v = 40 + (x + y) * 2; (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        val r = ArtPalette.extract(px, 64, 64)
        assertTrue(r.monochrome)
        assertTrue(r.colors.isNotEmpty() && r.colors.size <= 3)
        for (c in r.colors) assertEquals((c shr 16) and 0xFF, c and 0xFF)
        // y la lámpara los convierte en blancos cálidos
        assertTrue(LampMath.fromArgb(r.colors[0]).isWhite)
    }

    @Test fun tinyAccentOnBlackIsStillMonochrome() {
        val px = image(100, 100) { x, y -> if (x < 10 && y < 10) 0xFFE02020.toInt() else 0xFF050505.toInt() }
        assertTrue(ArtPalette.extract(px, 100, 100).monochrome)
    }

    @Test fun neverMoreThanSixAndOrderedByProminence() {
        val cols = intArrayOf(0xFFE02020.toInt(), 0xFFE0A020.toInt(), 0xFF40C020.toInt(), 0xFF20C0C0.toInt(), 0xFF2040E0.toInt(), 0xFFA020E0.toInt(), 0xFFE020A0.toInt(), 0xFF804020.toInt())
        // anchos decrecientes
        val px = image(200, 40) { x, _ -> cols[minOf(7, (x * x) / 5000)] }
        val r = ArtPalette.extract(px, 200, 40)
        assertTrue(r.colors.size in 2..6)
        assertTrue("el primero es el de mayor área (rojo)", hue(r.colors[0]).let { it < 15f || it > 345f })
    }

    @Test fun emptyOrTransparentNeverCrashes() {
        assertTrue(ArtPalette.extract(IntArray(16), 4, 4).colors.isNotEmpty())
        assertTrue(ArtPalette.extract(IntArray(0), 0, 0).colors.isNotEmpty())
    }

    @Test fun oklabRoundTrip() {
        for (c in intArrayOf(0xFFD02020.toInt(), 0xFF10B040.toInt(), 0xFF808080.toInt())) {
            val l = ArtPalette.oklab(c)
            val back = ArtPalette.toArgb(l[0], l[1], l[2])
            for (sh in intArrayOf(16, 8, 0)) assertEquals(((c shr sh) and 0xFF).toDouble(), ((back shr sh) and 0xFF).toDouble(), 2.0)
        }
    }
}
