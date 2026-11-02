package live.hogar.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkMathTest {
    @Test fun neverUpscales() {
        assertEquals(300, ArtworkMath.displaySide(300, 300, 600))
        assertEquals(200, ArtworkMath.displaySide(200, 500, 600)) // lado corto
        assertEquals(1, ArtworkMath.displaySide(0, 0, 600).coerceAtLeast(1))
    }

    @Test fun shrinksWhenLargerThanTarget() {
        assertEquals(600, ArtworkMath.displaySide(1400, 1400, 600))
        assertEquals(600, ArtworkMath.displaySide(3000, 1500, 600))
    }

    @Test fun squareCropIsCentered() {
        assertEquals(SquareCrop(250, 0, 500), ArtworkMath.squareCrop(1000, 500))
        assertEquals(SquareCrop(0, 100, 300), ArtworkMath.squareCrop(300, 500))
    }

    @Test fun sampleSizeKeepsAtLeastMax() {
        assertEquals(1, ArtworkMath.sampleSize(1200, 1200, 1600))
        assertEquals(2, ArtworkMath.sampleSize(3200, 3200, 1600))
        assertEquals(4, ArtworkMath.sampleSize(6400, 6400, 1600))
    }

    @Test fun paletteFindsDistinctColors() {
        val red = 0xFFD02020.toInt(); val blue = 0xFF2030D0.toInt()
        val px = IntArray(400) { if (it % 4 == 0) blue else red }
        val p = Palette.dominant(px)
        assertEquals(2, p.size)
        assertTrue(Palette.distance(p[0], red) < 40)
        assertTrue(Palette.distance(p[1], blue) < 40)
    }

    @Test fun paletteNeverEmpty() {
        assertEquals(1, Palette.dominant(IntArray(0)).size)
        assertEquals(1, Palette.dominant(IntArray(16) { 0 }).size) // transparentes
    }
}
