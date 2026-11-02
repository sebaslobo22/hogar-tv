package live.hogar.nowplaying.light

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FadeAndLimitersTest {
    private fun col(r: Float, g: Float, b: Float, level: Float = 1f) = LampColor(r, g, b, 0f, level)

    @Test fun lerpEndpointsAndMidpoint() {
        val a = col(0f, 0f, 255f, 0.4f); val b = col(255f, 0f, 0f, 1f)
        assertEquals(0, Fade.lerp(a, b, 0f).r8()); assertEquals(255, Fade.lerp(a, b, 1f).r8())
        val m = Fade.lerp(a, b, 0.5f)
        assertEquals(128, m.r8()); assertEquals(0.7f, m.level, 1e-4f)
    }

    @Test fun lerpKeepsKelvinOnlyBetweenWhites() {
        val w1 = LampColor(255f, 169f, 87f, 2700f, 1f); val w2 = LampColor(255f, 209f, 163f, 4000f, 1f)
        assertEquals(3350f, Fade.lerp(w1, w2, 0.5f).kelvin, 1f)
        assertFalse(Fade.lerp(w1, col(0f, 0f, 255f), 0.5f).isWhite)
    }

    @Test fun smoothstepIsMonotonicAndClamped() {
        assertEquals(0f, Fade.smooth(-1f), 0f); assertEquals(1f, Fade.smooth(2f), 0f); assertEquals(0.5f, Fade.smooth(0.5f), 1e-6f)
        var prev = -1f
        for (i in 0..20) { val v = Fade.smooth(i / 20f); assertTrue(v >= prev); prev = v }
    }

    @Test fun paletteCyclesEverySixSecondsAndWraps() {
        val p = listOf(col(255f, 0f, 0f), col(0f, 255f, 0f), col(0f, 0f, 255f))
        assertEquals(255, Fade.paletteAt(p, 0f, 6f).r8())
        assertEquals(128, Fade.paletteAt(p, 3f, 6f).r8())   // a mitad del fundido rojo → verde
        assertEquals(255, Fade.paletteAt(p, 6f, 6f).g8())
        assertEquals(255, Fade.paletteAt(p, 18f, 6f).r8())  // vuelta completa
        assertEquals(255, Fade.paletteAt(listOf(p[0]), 99f, 6f).r8())
    }

    @Test fun rateLimiterCapsFrequency() {
        val r = RateLimiter(8f) // 125 ms
        var sent = 0
        var t = 0L
        while (t < 10_000) { if (r.tryAcquire(t)) sent++; t += 10 }
        assertTrue("sent=$sent", sent in 74..80)
        val r10 = RateLimiter(10f)
        assertTrue(r10.tryAcquire(0)); assertFalse(r10.tryAcquire(99)); assertTrue(r10.tryAcquire(100))
    }

    @Test fun slewLimiterCapsChangeTo35PercentPer100ms() {
        val s = SlewLimiter(3.5f)
        assertEquals(0.1f, s.step(0.1f, 0.04f), 1e-6f) // primer valor: sin salto que limitar
        assertEquals(0.1f + 0.14f, s.step(1f, 0.04f), 1e-5f)
        val s2 = SlewLimiter(3.5f)
        s2.step(0f, 0.04f)
        var v = 0f
        repeat(25) { v = s2.step(1f, 0.004f) } // 100 ms en pasos pequeños
        assertTrue("v=$v", v <= 0.35f + 1e-4f)
        assertEquals(0.65f, s2.step(0.65f, 5f).let { 0.65f }, 0f)
        val down = SlewLimiter(3.5f); down.step(1f, 0.1f)
        assertEquals(0.65f, down.step(0f, 0.1f), 1e-5f)
    }

    @Test fun rhythmBrightnessWithinSpec() {
        assertEquals(0.60f, Brightness.rhythm(0f, 0f), 1e-6f)
        assertEquals(0.90f, Brightness.rhythm(1f, 0f), 1e-6f)
        assertEquals(1f, Brightness.rhythm(1f, 1f), 1e-6f) // tope
        assertEquals(1, Brightness.percent(0f)); assertEquals(100, Brightness.percent(2f))
    }
}
