package live.hogar.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class PointerMathTest {
    @Test fun speedStartsSlowAndRampsToMax() {
        assertEquals(PointerMath.BASE_SPEED, PointerMath.speed(0), 0.001f)
        assertEquals(PointerMath.MAX_SPEED, PointerMath.speed(PointerMath.RAMP_MS), 0.001f)
        assertEquals(PointerMath.MAX_SPEED, PointerMath.speed(10_000), 0.001f)
        val mid = PointerMath.speed(PointerMath.RAMP_MS / 2)
        assertEquals((PointerMath.BASE_SPEED + PointerMath.MAX_SPEED) / 2, mid, 0.5f)
    }

    @Test fun speedNeverDecreases() {
        var prev = 0f
        for (ms in 0L..1200L step 50) {
            val s = PointerMath.speed(ms)
            assertTrue(s >= prev)
            prev = s
        }
    }

    @Test fun stepMovesInDirection() {
        val p = PointerMath.step(100f, 100f, 1, 0, 200f, 0.5f, 1920f, 1080f)
        assertEquals(200f, p.x, 0.001f)
        assertEquals(100f, p.y, 0.001f)
    }

    @Test fun diagonalIsNotFaster() {
        val p = PointerMath.step(500f, 500f, 1, 1, 300f, 1f, 1920f, 1080f)
        assertEquals(300f, hypot(p.x - 500f, p.y - 500f), 0.01f)
    }

    @Test fun stepClampsToScreen() {
        val a = PointerMath.step(10f, 10f, -1, -1, 5000f, 1f, 1920f, 1080f)
        assertEquals(0f, a.x, 0f); assertEquals(0f, a.y, 0f)
        val b = PointerMath.step(1900f, 1070f, 1, 1, 5000f, 1f, 1920f, 1080f)
        assertEquals(1919f, b.x, 0f); assertEquals(1079f, b.y, 0f)
    }

    @Test fun noDirectionNoMove() {
        val p = PointerMath.step(7f, 9f, 0, 0, 500f, 1f, 1920f, 1080f)
        assertEquals(7f, p.x, 0f); assertEquals(9f, p.y, 0f)
    }

    @Test fun edgeScrollZeroInMiddle() {
        assertEquals(0f, EdgeScroll.axis(540f, 1080f), 0f)
        assertEquals(0f, EdgeScroll.axis(EdgeScroll.BAND, 1080f), 0f)
    }

    @Test fun edgeScrollSignsAndSpeed() {
        assertEquals(-EdgeScroll.MAX, EdgeScroll.axis(0f, 1080f), 0.001f)
        assertEquals(EdgeScroll.MAX, EdgeScroll.axis(1080f, 1080f), 0.001f)
        assertTrue(EdgeScroll.axis(10f, 1080f) < 0f)
        assertTrue(EdgeScroll.axis(1070f, 1080f) > 0f)
        // más profundo en la banda = más rápido
        assertTrue(EdgeScroll.axis(1070f, 1080f) > EdgeScroll.axis(1030f, 1080f))
    }

    @Test fun edgeScrollTinyViewportDoesNothing() {
        assertEquals(0f, EdgeScroll.axis(0f, 100f), 0f)
    }

    @Test fun splitKeepsFraction() {
        var acc = 0f
        var total = 0
        repeat(10) {
            val (whole, rest) = EdgeScroll.split(acc, 0.3f)
            total += whole
            acc = rest
        }
        assertEquals(3, total)
        val (w, r) = EdgeScroll.split(0f, -2.6f)
        assertEquals(-2, w)
        assertEquals(-0.6f, r, 0.001f)
    }

    @Test fun pageStepIs80Percent() {
        assertEquals(864, EdgeScroll.pageStep(1080))
        assertEquals(1, EdgeScroll.pageStep(0))
    }
}
