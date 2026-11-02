package live.hogar.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshFieldTest {
    private val palette = listOf(0xFF1B1F3A.toInt(), 0xFFE0405A.toInt(), 0xFF204080.toInt(), 0xFFF0B030.toInt(), 0xFF402060.toInt())

    private fun avgLuma(px: IntArray) = px.map { MeshPalette.luma(it) }.average()

    @Test fun rendersOpaqueSmoothFrames() {
        val f = MeshField(96, 54)
        f.setTarget(palette)
        val px = IntArray(96 * 54)
        f.render(px, 3f, 0.3f, 0f)
        assertTrue(px.all { (it ushr 24) == 0xFF })
        // Suavidad: ningún salto brusco entre píxeles vecinos (se estira ~20x sin verse a bloques).
        var maxStep = 0
        for (y in 0 until 54) for (x in 0 until 95) {
            val a = px[y * 96 + x]; val b = px[y * 96 + x + 1]
            maxStep = maxOf(maxStep, Palette.distance(a, b))
        }
        assertTrue("maxStep=$maxStep", maxStep < 40)
    }

    @Test fun vignetteDarkensCorners() {
        val f = MeshField(96, 54)
        f.setTarget(listOf(0xFF808080.toInt()))
        val px = IntArray(96 * 54)
        f.render(px, 0f, 0f, 0f)
        assertTrue(MeshPalette.luma(px[0]) < MeshPalette.luma(px[27 * 96 + 48]))
    }

    @Test fun energyBrightensByAtMostAboutTwelvePercent() {
        val f = MeshField(96, 54)
        f.setTarget(palette)
        val a = IntArray(96 * 54); val b = IntArray(96 * 54)
        f.render(a, 5f, 0f, 0f)
        f.render(b, 5f, 1f, 0.06f)
        val ratio = avgLuma(b) / avgLuma(a)
        assertTrue("ratio=$ratio", ratio in 1.0..1.16)
    }

    @Test fun movesSlowlyOverTime() {
        val f = MeshField(96, 54)
        f.setTarget(palette)
        val a = IntArray(96 * 54); val b = IntArray(96 * 54)
        f.render(a, 10f, 0f, 0f)
        f.render(b, 10.04f, 0f, 0f) // un cuadro a 25 fps
        val diff = a.indices.sumOf { Palette.distance(a[it], b[it]) } / a.size
        assertTrue("diff=$diff", diff <= 1)
    }

    @Test fun colorsFadeTowardNewTarget() {
        val f = MeshField(96, 54)
        f.setTarget(listOf(0xFFFF0000.toInt()))
        f.setTarget(listOf(0xFF0000FF.toInt()))
        val px = IntArray(96 * 54)
        repeat(60) { f.step(0.05f) }
        f.render(px, 0f, 0f, 0f)
        val mid = px[27 * 96 + 48]
        assertTrue(((mid and 0xFF) > ((mid shr 16) and 0xFF)))
        assertEquals(96 * 54, px.size)
    }
}
