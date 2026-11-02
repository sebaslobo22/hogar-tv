package live.hogar.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NpLayoutTest {
    // 1920x1080 @ 320 dpi = 960x540 dp. Controles: 4 + 24 + 14 + 68 + 10 + 40 = unos 170 dp.
    private fun input(ctrlH: Float = 170f, w: Float = 960f, h: Float = 540f) = NpLayoutIn(
        width = w, height = h, safeH = 48f, safeV = 27f, titleH = 68f, ctrlH = ctrlH, toastH = 64f,
        artMax = 420f, centerCtrlW = 640f, gap = 8f, colGap = 44f, startPad = 64f,
    )

    private val steps = listOf(0f, 0.25f, 0.5f, 0.75f, 1f) + (1..19).map { it / 20f }

    private fun check(i: NpLayoutIn, p: Float, c: Float, t: Float, sideShown: Boolean, tag: String) {
        val f = NpLayout.frame(i, p, c, t)
        val safe = NpRect(i.safeH, i.safeV, i.width - i.safeH, i.height - i.safeV)
        val msg = "$tag p=$p chrome=$c toast=$t"
        assertTrue("$msg carátula cuadrada", kotlin.math.abs(f.art.w - f.art.h) < 0.01f)
        assertTrue("$msg carátula dentro del área segura", f.art.l >= safe.l - 0.5f && f.art.r <= safe.r + 0.5f && f.art.t >= safe.t - 0.5f)
        assertTrue("$msg título dentro del área segura", f.title.b <= safe.b + 0.5f && f.title.r <= safe.r + 0.5f)
        assertTrue("$msg carátula/título vs controles", !f.art.intersects(f.ctrl) && !f.title.intersects(f.ctrl))
        assertTrue("$msg carátula/título vs aviso", !f.art.intersects(f.toast) && !f.title.intersects(f.toast))
        assertTrue("$msg carátula vs título", !f.art.intersects(f.title))
        assertTrue("$msg controles vs aviso", !f.ctrl.intersects(f.toast))
        if (sideShown && f.sideClear > 0f) {
            assertTrue("$msg columna vs carátula", !f.side.intersects(f.art) && !f.side.intersects(f.title))
            assertTrue("$msg columna vs controles", !f.side.intersects(f.ctrl))
            assertTrue("$msg columna vs aviso", !f.side.intersects(f.toast))
            assertTrue("$msg columna dentro del área segura", f.side.r <= safe.r + 0.5f && f.side.b <= safe.b + 0.5f)
        }
        // Los controles visibles (los que ocupan sitio) caben en el área segura.
        if (c >= 1f) assertTrue("$msg controles dentro", f.ctrl.b <= safe.b + 0.5f && f.ctrl.l >= safe.l - 0.5f && f.ctrl.r <= safe.r + 0.5f)
    }

    @Test fun noIntersectionInAnyStateOrTransition() {
        val i = input()
        for (sideShown in listOf(false, true)) // letra/cola apagadas o encendidas (síncrona, plana o cola: misma geometría)
            for (p in steps) for (c in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) for (t in listOf(0f, 0.25f, 0.5f, 0.75f, 1f))
                check(i, p, c, t, sideShown, "side=$sideShown")
    }

    @Test fun holdsForOtherControlHeightsAndScreens() {
        for (ctrlH in listOf(150f, 190f, 210f))
            for (p in steps) for (c in listOf(0f, 0.5f, 1f)) for (t in listOf(0f, 1f))
                check(input(ctrlH), p, c, t, true, "ctrlH=$ctrlH")
        for ((w, h) in listOf(1280f to 720f, 1366f to 768f, 1920f to 1080f))
            for (p in steps) for (c in listOf(0f, 0.5f, 1f)) for (t in listOf(0f, 1f))
                check(input(w = w, h = h), p, c, t, true, "${w}x$h")
    }

    @Test fun artworkIsAsLargeAsFits() {
        val i = input()
        // Lateral: ocupa todo el alto útil (hasta 420) sin tocar a los controles, que quedan a su derecha.
        val side = NpLayout.frame(i, 1f, 1f, 0f)
        assertEquals(NpLayout.sideArt(i), side.art.w, 0.01f)
        assertTrue(side.art.w > 380f)
        // Centrado con controles: más pequeña que sin controles (cede antes de solaparse).
        val withCtrl = NpLayout.frame(i, 0f, 1f, 0f).art.w
        val without = NpLayout.frame(i, 0f, 0f, 0f).art.w
        assertTrue(withCtrl < without)
        assertTrue(without <= 420f + 0.01f)
        assertTrue(withCtrl > 150f)
    }

    @Test fun nearestFreeSpaceIsFilledWhenControlsHide() {
        val i = input()
        assertTrue(NpLayout.frame(i, 1f, 0f, 0f).side.b > NpLayout.frame(i, 1f, 1f, 0f).side.b + 100f)
    }

    @Test fun marqueePausesScrollsPausesAndResets() {
        val speed = 60f
        assertEquals(0f, MarqueeMath.offset(0f, 300f, speed), 0.001f)
        assertEquals(0f, MarqueeMath.offset(1.9f, 300f, speed), 0.001f)
        assertEquals(60f, MarqueeMath.offset(3f, 300f, speed), 0.001f)   // 1 s tras la pausa
        assertEquals(300f, MarqueeMath.offset(7.5f, 300f, speed), 0.001f) // fin del avance + pausa
        assertEquals(0f, MarqueeMath.offset(9f + 0.0f, 300f, speed), 0.001f) // ciclo = 2 + 5 + 2
        assertEquals(0f, MarqueeMath.offset(5f, 0f, speed), 0f)
    }
}
