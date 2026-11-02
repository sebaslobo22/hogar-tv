package live.hogar.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MeshPaletteTest {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun adjacentContrast(l: List<Int>) = l.zipWithNext { a, b -> abs(MeshPalette.luma(a) - MeshPalette.luma(b)) }.sum()

    @Test fun orderStartsDarkAndAlternates() {
        val cs = listOf(rgb(200, 200, 200), rgb(10, 10, 10), rgb(120, 120, 120), rgb(60, 60, 60), rgb(240, 240, 240))
        val o = MeshPalette.orderForContrast(cs)
        assertEquals(cs.toSet(), o.toSet())
        assertEquals(rgb(10, 10, 10), o[0])
        assertEquals(rgb(240, 240, 240), o[1])
        assertTrue(adjacentContrast(o) > adjacentContrast(cs.sortedBy { MeshPalette.luma(it) }))
    }

    @Test fun buildGivesFourToSixColorsWithDarkAndNoneTooBright() {
        // Carátula casi blanca con un acento: debe oscurecerse para que el texto blanco se lea.
        val px = IntArray(576) { if (it % 6 == 0) rgb(230, 40, 60) else rgb(250, 250, 245) }
        for (n in 4..6) {
            val p = MeshPalette.build(px, n)
            assertEquals(n, p.size)
            assertTrue(p.any { MeshPalette.luma(it) < 0.22f })
            assertTrue(p.all { MeshPalette.luma(it) <= 0.67f })
            assertTrue(p.all { (it ushr 24) == 0xFF })
        }
    }

    @Test fun singleColorImageStillYieldsVariants() {
        val p = MeshPalette.build(IntArray(576) { rgb(30, 90, 200) })
        assertTrue(p.size >= 4)
        assertTrue(p.toSet().size >= 4)
    }

    @Test fun emptyInputNeverEmpty() {
        assertTrue(MeshPalette.build(IntArray(0)).size >= 4)
    }

    @Test fun vividColorsSurviveAndOrderIsContrasting() {
        val px = IntArray(576) { when (it % 3) { 0 -> rgb(220, 30, 30); 1 -> rgb(20, 40, 180); else -> rgb(240, 200, 30) } }
        val p = MeshPalette.build(px, 5)
        assertEquals(p.minByOrNull { MeshPalette.luma(it) }, p[0])
        assertTrue(adjacentContrast(p) >= adjacentContrast(p.sortedBy { MeshPalette.luma(it) }))
    }
}
