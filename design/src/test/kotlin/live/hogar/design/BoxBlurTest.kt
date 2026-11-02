package live.hogar.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoxBlurTest {
    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test fun uniformImageIsUnchanged() {
        val c = argb(255, 10, 120, 200)
        val px = IntArray(12 * 8) { c }
        BoxBlur.blur(px, 12, 8, 3)
        assertTrue(px.all { it == c })
    }

    @Test fun radiusZeroIsNoOp() {
        val px = IntArray(16) { argb(255, it * 10, 0, 0) }
        val copy = px.copyOf()
        BoxBlur.blur(px, 4, 4, 0)
        assertTrue(px.contentEquals(copy))
    }

    @Test fun impulseSpreadsSymmetricallyAndKeepsMass() {
        val w = 21
        val h = 21
        val px = IntArray(w * h) { argb(255, 0, 0, 0) }
        px[10 * w + 10] = argb(255, 255, 0, 0)
        BoxBlur.blur(px, w, h, 2, passes = 1)
        val red = IntArray(w * h) { (px[it] shr 16) and 0xFF }
        assertEquals(red[10 * w + 8], red[10 * w + 12])
        assertEquals(red[8 * w + 10], red[12 * w + 10])
        assertTrue(red[10 * w + 10] < 255)
        assertTrue(red[10 * w + 11] > 0)
        assertTrue(kotlin.math.abs(red.sum() - 255) <= 20)
    }

    @Test fun blurSmoothsAHardEdge() {
        val w = 16
        val h = 4
        val px = IntArray(w * h) { argb(255, if (it % w < 8) 0 else 200, 0, 0) }
        BoxBlur.blur(px, w, h, 2)
        val mid = (px[7] shr 16) and 0xFF
        assertTrue(mid in 1..199)
    }
}
