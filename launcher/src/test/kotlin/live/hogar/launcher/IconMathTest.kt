package live.hogar.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IconMathTest {
    private val eps = 0.01f

    // ---- 5:3 y esquina
    @Test fun heightIsThreeFifthsOfWidth() {
        assertEquals(87f, IconGeometry.heightForWidth(145f), eps)
        assertEquals(8.7f, IconGeometry.cornerRadius(145f), eps)
    }

    // ---- cubrir: recorte centrado
    @Test fun coverSquareOntoWideCropsVertically() {
        val b = IconGeometry.cover(1f, 1f, 500f, 300f)
        assertEquals(500f, b.width, eps)
        assertEquals(500f, b.height, eps)
        assertEquals(0f, b.left, eps)
        assertEquals(-100f, b.top, eps) // sobran 100 px arriba y 100 abajo
    }

    @Test fun cover16x9OntoFiveByThreeCropsSides() {
        val b = IconGeometry.cover(16f, 9f, 500f, 300f)
        assertEquals(300f, b.height, eps)
        assertEquals(533.33f, b.width, eps)
        assertEquals(-16.67f, b.left, eps)
        assertEquals(0f, b.top, eps)
    }

    @Test fun coverAlwaysCoversDestination() {
        for ((sw, sh) in listOf(1f to 1f, 16f to 9f, 5f to 3f, 3f to 4f, 4f to 1f)) {
            val b = IconGeometry.cover(sw, sh, 500f, 300f)
            assertTrue(b.left <= eps && b.top <= eps && b.right >= 500f - eps && b.bottom >= 300f - eps)
        }
    }

    // ---- zona segura del primer plano
    @Test fun foregroundSafeZoneIsSeventyPercentOfHeight() {
        val h = 87f
        val side = IconGeometry.foregroundSide(h)
        // La zona segura (66 de 108) del lado debe medir el 70 % de la altura.
        assertEquals(h * 0.70f, side * 66f / 108f, eps)
        assertEquals(1.1455f, side / h, 0.001f)
    }

    @Test fun centeredBoxIsCentered() {
        val b = IconGeometry.centered(100f, 145f, 87f)
        assertEquals(22.5f, b.left, eps)
        assertEquals(-6.5f, b.top, eps)
    }

    // ---- nunca ampliar un mapa de bits
    @Test fun rasterNeverUpscalesBitmap() {
        // Destino 600×360, fuente 300×300: cubrir exigiría ampliar 2× -> se reduce a 300×180.
        val (w, h) = IconGeometry.coverRaster(600, 5f, 3f, 300, 300)
        assertEquals(300, w)
        assertEquals(180, h)
    }

    @Test fun rasterKeepsTargetWhenSourceIsBigger() {
        val (w, h) = IconGeometry.coverRaster(330, 5f, 3f, 1024, 1024)
        assertEquals(330, w)
        assertEquals(198, h)
    }

    @Test fun rasterOfVectorUsesTarget() {
        val (w, h) = IconGeometry.coverRaster(330, 5f, 3f, null, null)
        assertEquals(330 to 198, w to h)
    }

    @Test fun rasterSizesFollowDensityAndFocusScale() {
        // 145 dp × 2 × 1,14 × 1,06 = 350,5 -> 351
        assertEquals(351, IconGeometry.backWidthPx(145f, 2f))
        // lado del primer plano: 87 × 1,1455 × 2 × 1,14 = 227,2 -> 228
        assertEquals(228, IconGeometry.frontSidePx(145f, 2f))
    }

    // ---- parallax
    @Test fun frontMovesMoreThanBack() {
        val front = Parallax.shiftDp(1f, Parallax.FrontFactor)
        val back = Parallax.shiftDp(1f, Parallax.BackFactor)
        assertTrue(front > back && back > 0f)
        assertEquals(5f, front, eps)
        assertEquals(-1.75f, Parallax.shiftDp(-1f, Parallax.BackFactor), eps)
    }

    @Test fun parallaxIsClampedAndSymmetric() {
        assertEquals(Parallax.shiftDp(1f, 1f), Parallax.shiftDp(3f, 1f), eps)
        assertEquals(0f, Parallax.shiftDp(0f, 1f), eps)
        assertEquals(-Parallax.shiftDp(0.4f, 0.35f), Parallax.shiftDp(-0.4f, 0.35f), eps)
    }

    @Test fun backOverscanCoversItsOwnShift() {
        assertTrue(Parallax.overscanCovers(145f))
        assertTrue(Parallax.overscanCovers(176f))
    }

    // ---- rejilla
    @Test fun gridMetricsFillUsableWidthWithFourPercentGaps() {
        val m = IconGrid.metrics(864f)
        assertEquals(34.56f, m.gap, eps)
        assertEquals(864f, m.cellWidth * 5 + m.gap * 4, 0.05f)
        assertEquals(m.cellWidth * 0.6f, m.iconHeight, eps)
        assertEquals(m.rowHeight + IconGrid.RowGapDp, m.rowPitch, eps)
    }

    @Test fun rowsChunkByFiveWithPinnedFirst() {
        val installed = listOf("a", "b", "c", "d", "e", "f", "g", "h")
        val arranged = LauncherLogic.arrange(installed, LauncherPrefs(pinned = listOf("g", "c")))
        val rows = IconGrid.rows(IconGrid.flat(arranged.pinned, arranged.rest))
        assertEquals(listOf(listOf("g", "c", "a", "b", "d"), listOf("e", "f", "h")), rows)
    }

    @Test fun rowsOfEmptyAndExact() {
        assertEquals(emptyList<List<Int>>(), IconGrid.rows(emptyList<Int>()))
        assertEquals(2, IconGrid.rows((1..10).toList()).size)
        assertEquals(3, IconGrid.rows((1..11).toList()).size)
    }

    @Test fun rowOfIndex() {
        assertEquals(0, IconGrid.rowOf(4))
        assertEquals(1, IconGrid.rowOf(5))
        assertEquals(-1, IconGrid.rowOf(-1))
    }

    @Test fun scrollTargetsRestAndPivot() {
        assertEquals(0f, IconGrid.targetScroll(0, 243f, 130f, 130f), eps)
        // fila 1: 243 + 130 - 130 = 243 (la fila queda en el ancla y el Top Shelf se va del todo)
        assertEquals(243f, IconGrid.targetScroll(1, 243f, 130f, 130f), eps)
        assertEquals(373f, IconGrid.targetScroll(2, 243f, 130f, 130f), eps)
    }

    @Test fun currentScrollFromFirstVisible() {
        assertEquals(0f, IconGrid.currentScroll(0, 0, 130f), eps)
        assertEquals(130f + 40f, IconGrid.currentScroll(5, 40, 130f), eps)
        assertEquals(260f, IconGrid.currentScroll(10, 0, 130f), eps)
    }

    @Test fun shelfCollapseClamps() {
        assertEquals(0f, IconGrid.shelfCollapse(-5f, 243f), eps)
        assertEquals(0.5f, IconGrid.shelfCollapse(121.5f, 243f), eps)
        assertEquals(1f, IconGrid.shelfCollapse(500f, 243f), eps)
    }

    // ---- sombra horneada (en el módulo design se prueba su geometría; aquí, que la rejilla cabe)
    @Test fun focusedScaledIconOverlapsLessThanGap() {
        val m = IconGrid.metrics(864f)
        val grow = m.cellWidth * (IconGeometry.FocusScale - 1f) / 2f
        assertTrue("el icono enfocado debe caber en el hueco", grow < m.gap)
    }
}
