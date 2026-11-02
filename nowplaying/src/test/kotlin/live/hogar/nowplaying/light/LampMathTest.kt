package live.hogar.nowplaying.light

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LampMathTest {
    @Test fun darkColorLowersBrightnessInsteadOfDarkRgb() {
        val c = LampMath.fromArgb(0xFF200000.toInt())
        assertFalse(c.isWhite)
        assertEquals(255, c.r8()) // el LED va al máximo; el brillo baja
        assertTrue("level=${c.level}", c.level < 0.35f && c.level >= LampMath.MIN_LEVEL)
    }

    @Test fun nearBlackNeverTurnsLampOff() {
        assertTrue(LampMath.fromArgb(0xFF010101.toInt()).level >= LampMath.MIN_LEVEL)
        assertTrue(LampMath.fromArgb(0xFF000000.toInt()).level >= LampMath.MIN_LEVEL)
    }

    @Test fun greyscaleUsesWarmKelvin() {
        for (g in intArrayOf(0x30, 0x80, 0xF0)) {
            val c = LampMath.fromArgb(0xFF000000.toInt() or (g shl 16) or (g shl 8) or g)
            assertTrue(c.isWhite)
            assertTrue("k=${c.kelvin}", c.kelvin in 2700f..4000f)
        }
        val bright = LampMath.fromArgb(0xFFF0F0F0.toInt())
        val dim = LampMath.fromArgb(0xFF303030.toInt())
        assertTrue(bright.kelvin > dim.kelvin && bright.level > dim.level)
    }

    @Test fun colorsAreSaturatedWithinBounds() {
        for (s in listOf(0.15f, 0.3f, 0.5f, 0.8f, 1f)) {
            val out = LampMath.saturate(s)
            assertTrue("s=$s out=$out", out >= 0.6f && out <= 1f && out >= s)
        }
        val pale = LampMath.fromArgb(0xFFE6B4B4.toInt()) // rosa pálido: sigue siendo color
        assertFalse(pale.isWhite)
        assertTrue(minOf(pale.r8(), pale.g8(), pale.b8()) < 90)
    }

    @Test fun hueIsKept() {
        val blue = LampMath.fromArgb(0xFF3050C0.toInt())
        assertTrue(blue.b8() > blue.g8() && blue.g8() >= blue.r8())
        val orange = LampMath.fromArgb(0xFFE08020.toInt())
        assertTrue(orange.r8() > orange.g8() && orange.g8() > orange.b8())
        assertEquals(255, blue.b8())
    }

    @Test fun gammaDarkensMidChannels() {
        val c = LampMath.fromArgb(0xFFFF8000.toInt()) // naranja: g sRGB 128 → ~21 % lineal
        assertEquals(255, c.r8())
        assertTrue("g=${c.g8()}", c.g8() < 80)
    }
}
