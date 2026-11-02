package live.hogar.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusMathTest {
    // Cuadrícula 3x2 de tarjetas de 100x100 con huecos de 20.
    private fun card(col: Int, row: Int) = Box(col * 120, row * 120, col * 120 + 100, row * 120 + 100)
    private val grid = listOf(card(0, 0), card(1, 0), card(2, 0), card(0, 1), card(1, 1), card(2, 1))

    @Test fun picksTheNeighbourInEachDirection() {
        val c = card(1, 0)
        assertEquals(2, FocusMath.nearest(c, grid, FocusDir.RIGHT))
        assertEquals(0, FocusMath.nearest(c, grid, FocusDir.LEFT))
        assertEquals(4, FocusMath.nearest(c, grid, FocusDir.DOWN))
        assertNull(FocusMath.nearest(c, grid, FocusDir.UP))
        assertEquals(1, FocusMath.nearest(card(1, 1), grid, FocusDir.UP))
    }

    @Test fun nothingBeyondTheEdges() {
        assertNull(FocusMath.nearest(card(2, 0), grid, FocusDir.RIGHT))
        assertNull(FocusMath.nearest(card(0, 0), grid, FocusDir.LEFT))
        assertNull(FocusMath.nearest(card(0, 1), grid, FocusDir.DOWN))
        assertNull(FocusMath.nearest(card(0, 0), emptyList(), FocusDir.DOWN))
    }

    @Test fun prefersTheAlignedTargetOverACloserDiagonalOne() {
        val from = Box(0, 0, 100, 100)
        val aligned = Box(300, 10, 400, 90)     // lejos pero en la misma fila
        val diagonal = Box(120, 200, 220, 300)  // más cerca en X pero otra fila
        assertEquals(0, FocusMath.nearest(from, listOf(aligned, diagonal), FocusDir.RIGHT))
        // Sin nadie en el haz, gana el más cercano.
        assertEquals(1, FocusMath.nearest(from, listOf(Box(500, 300, 600, 400), diagonal), FocusDir.RIGHT))
    }

    @Test fun ignoresItselfAndWrongSideCandidates() {
        val from = Box(100, 100, 200, 200)
        assertNull(FocusMath.nearest(from, listOf(from, Box(0, 100, 90, 200)), FocusDir.RIGHT))
        assertEquals(1, FocusMath.nearest(from, listOf(from, Box(0, 100, 90, 200)), FocusDir.LEFT))
    }

    @Test fun keyCodesMapToDirections() {
        assertEquals(FocusDir.UP, FocusDir.ofKeyCode(19)); assertEquals(FocusDir.DOWN, FocusDir.ofKeyCode(20))
        assertEquals(FocusDir.LEFT, FocusDir.ofKeyCode(21)); assertEquals(FocusDir.RIGHT, FocusDir.ofKeyCode(22))
        assertNull(FocusDir.ofKeyCode(23))                                 // Aceptar no es una flecha
        assertEquals(33, FocusDir.UP.viewDirection); assertEquals(130, FocusDir.DOWN.viewDirection)
        assertEquals(17, FocusDir.LEFT.viewDirection); assertEquals(66, FocusDir.RIGHT.viewDirection)
    }
}

class PressDetectorTest {
    private class FakeTicker : PressTicker {
        val tasks = ArrayList<Pair<Runnable, Long>>()
        override fun post(r: Runnable, delayMs: Long) { tasks += r to delayMs }
        override fun cancel(r: Runnable) { tasks.removeAll { it.first === r } }
        fun fire(maxDelay: Long) { tasks.filter { it.second <= maxDelay }.forEach { tasks.remove(it); it.first.run() } }
    }

    private val ticker = FakeTicker()
    private val seen = ArrayList<Press>()
    private val d = PressDetector(ticker) { seen += it }

    @Test fun plainButtonFiresSingleImmediately() {
        d.onDown(); assertFalse(d.onUp())
        assertEquals(listOf(Press.SINGLE), seen)
        assertTrue(ticker.tasks.isEmpty())
    }

    @Test fun singleIsDelayedOnlyWhenDoubleIsWanted() {
        d.wantsDouble = true
        d.onDown(); d.onUp()
        assertTrue(seen.isEmpty())
        assertEquals(PressDetector.DOUBLE_MS, ticker.tasks.single().second)
        ticker.fire(PressDetector.DOUBLE_MS)
        assertEquals(listOf(Press.SINGLE), seen)
    }

    @Test fun twoQuickPressesAreADoubleAndCancelTheSingle() {
        d.wantsDouble = true
        d.onDown(); d.onUp(); d.onDown(); d.onUp()
        assertEquals(listOf(Press.DOUBLE), seen)
        assertTrue(ticker.tasks.isEmpty())
    }

    @Test fun holdingFiresLongOnceAndReleaseIsSwallowed() {
        d.wantsLong = true; d.wantsDouble = true
        d.onDown()
        assertTrue(d.pressed)
        ticker.fire(PressDetector.LONG_MS)
        assertEquals(listOf(Press.LONG), seen)
        assertTrue(d.onUp())                  // quien llama no ejecuta la acción simple
        assertEquals(listOf(Press.LONG), seen)
        assertTrue(ticker.tasks.isEmpty())
    }

    @Test fun shortPressCancelsTheLongTimer() {
        d.wantsLong = true
        d.onDown(); d.onUp()
        assertEquals(listOf(Press.SINGLE), seen)
        assertTrue(ticker.tasks.isEmpty())
        assertEquals(listOf(0, 1, 2), Press.entries.map { it.code })
    }
}
