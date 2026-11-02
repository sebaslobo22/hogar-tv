package live.hogar.launcher.pointer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PointerMathTest {
    private val eps = 0.01f
    private val launcher = PointerPolicy.CAT_LAUNCHER
    private val leanback = PointerPolicy.CAT_LEANBACK

    @Test fun noSpeedBeforeHoldDelay() {
        assertEquals(0f, PointerMath.speedPxPerSec(0), eps)
        assertEquals(0f, PointerMath.speedPxPerSec(PointerTuning.HOLD_DELAY_MS - 1), eps)
    }

    @Test fun rampStartsAtMinAndCapsAtMax() {
        assertEquals(PointerTuning.MIN_SPEED, PointerMath.speedPxPerSec(PointerTuning.HOLD_DELAY_MS), eps)
        val end = PointerTuning.HOLD_DELAY_MS + PointerTuning.RAMP_MS
        assertEquals(1400f, PointerMath.speedPxPerSec(end), eps)
        assertEquals(1400f, PointerMath.speedPxPerSec(end + 5000), eps)
    }

    @Test fun rampIsMonotonic() {
        var prev = 0f
        for (ms in 0L..2000L step 20) {
            val v = PointerMath.speedPxPerSec(ms)
            assertTrue(v >= prev)
            prev = v
        }
    }

    @Test fun pressClassification() {
        assertEquals(PressKind.TAP, PointerMath.classifyPress(0))
        assertEquals(PressKind.TAP, PointerMath.classifyPress(449))
        assertEquals(PressKind.LONG_PRESS, PointerMath.classifyPress(450))
        assertEquals(PressKind.LONG_PRESS, PointerMath.classifyPress(1499))
        assertEquals(PressKind.TOGGLE, PointerMath.classifyPress(1500))
        assertEquals(PressKind.TOGGLE, PointerMath.classifyPress(4000))
    }

    @Test fun pushedEdgeOnlyWhenAtEdgeAndPushingOut() {
        assertEquals(Edge.DOWN, PointerMath.pushedEdge(500f, 1079f, 1920, 1080, 0, 1))
        assertEquals(Edge.UP, PointerMath.pushedEdge(500f, 0f, 1920, 1080, 0, -1))
        assertEquals(Edge.LEFT, PointerMath.pushedEdge(0f, 300f, 1920, 1080, -1, 0))
        assertEquals(Edge.RIGHT, PointerMath.pushedEdge(1919f, 300f, 1920, 1080, 1, 0))
        assertNull(PointerMath.pushedEdge(500f, 1079f, 1920, 1080, 0, -1))
        assertNull(PointerMath.pushedEdge(500f, 500f, 1920, 1080, 0, 1))
    }

    @Test fun edgeScrollNeedsDwellAndCooldown() {
        assertFalse(PointerMath.shouldEdgeScroll(-1, 10_000, 0))
        assertFalse(PointerMath.shouldEdgeScroll(10_000, 10_100, 0))
        assertTrue(PointerMath.shouldEdgeScroll(10_000, 10_200, 0))
        assertFalse(PointerMath.shouldEdgeScroll(10_000, 10_500, 10_300))
        assertTrue(PointerMath.shouldEdgeScroll(10_000, 10_700, 10_300))
    }

    @Test fun swipeMovesContentOppositeToEdgeAndIs35Percent() {
        val s = PointerMath.swipeFor(Edge.DOWN, 1920, 1080, 700f, 1079f)
        assertEquals(700f, s.x0, eps)
        assertTrue(s.y0 > s.y1)
        assertEquals(0.35f * 1080, s.y0 - s.y1, eps)
        val l = PointerMath.swipeFor(Edge.LEFT, 1920, 1080, 0f, 300f)
        assertTrue(l.x1 > l.x0)
        assertEquals(0.35f * 1920, l.x1 - l.x0, eps)
        assertEquals(300f, l.y0, eps)
    }

    @Test fun touchOnlyWhenLauncherWithoutLeanback() {
        assertTrue(PointerPolicy.isTouchOnly(setOf(launcher)))
        assertFalse(PointerPolicy.isTouchOnly(setOf(launcher, leanback)))
        assertFalse(PointerPolicy.isTouchOnly(setOf(leanback)))
        assertFalse(PointerPolicy.isTouchOnly(emptySet()))
    }

    @Test fun defaultEnabledExcludesOwnStockSystemAndIme() {
        val stock = setOf("com.google.android.tvlauncher")
        val ime = setOf("com.google.android.inputmethod.latin")
        val touch = setOf(launcher)
        fun d(p: String) = PointerPolicy.defaultEnabled(p, "live.hogar", stock, ime, touch)
        assertTrue(d("com.apple.android.music"))
        assertFalse(d("live.hogar"))
        assertFalse(d("live.hogar.lab"))
        assertFalse(d("com.google.android.tvlauncher"))
        assertFalse(d("com.android.systemui"))
        assertFalse(d("com.google.android.inputmethod.latin"))
        assertFalse(PointerPolicy.defaultEnabled("com.netflix.ninja", "live.hogar", stock, ime, setOf(launcher, leanback)))
        assertTrue(PointerPolicy.isTransient("android", ime))
    }

    @Test fun overrideBeatsHeuristic() {
        assertTrue(PointerPolicy.effective(false, true))
        assertFalse(PointerPolicy.effective(true, false))
        assertTrue(PointerPolicy.effective(true, null))
        assertFalse(PointerPolicy.effective(false, null))
    }
}
