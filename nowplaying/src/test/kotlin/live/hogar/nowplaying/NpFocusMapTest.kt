package live.hogar.nowplaying

import live.hogar.nowplaying.NpFocusMap.AIRPLAY
import live.hogar.nowplaying.NpFocusMap.LIGHT
import live.hogar.nowplaying.NpFocusMap.LYRICS
import live.hogar.nowplaying.NpFocusMap.NEXT
import live.hogar.nowplaying.NpFocusMap.PLAY
import live.hogar.nowplaying.NpFocusMap.PREV
import live.hogar.nowplaying.NpFocusMap.QUEUE
import live.hogar.nowplaying.NpFocusMap.TV
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NpFocusMapTest {
    // Centros X medidos (px) del diseño lateral: transporte arriba, botones pequeños abajo.
    private val xs = FloatArray(NpFocusMap.COUNT).also {
        it[PREV] = 100f; it[PLAY] = 200f; it[NEXT] = 300f
        it[TV] = 50f; it[LYRICS] = 150f; it[QUEUE] = 230f; it[AIRPLAY] = 310f; it[LIGHT] = 390f
    }

    @Test fun nearestPicksClosestAndBreaksTiesToFirst() {
        val row = intArrayOf(TV, LYRICS, QUEUE, AIRPLAY, LIGHT)
        assertEquals(QUEUE, NpFocusMap.nearestByX(200f, xs, row))
        assertEquals(LIGHT, NpFocusMap.nearestByX(900f, xs, row))
        assertEquals(TV, NpFocusMap.nearestByX(-50f, xs, row))
        assertEquals(TV, NpFocusMap.nearestByX(100f, xs, row)) // 50 y 150 empatan: gana el primero
    }

    @Test fun unmeasuredFallsBackToFirstCandidate() {
        val blank = FloatArray(NpFocusMap.COUNT) { Float.NaN }
        assertEquals(PREV, NpFocusMap.nearestByX(10f, blank, intArrayOf(PREV, PLAY, NEXT)))
    }

    @Test fun downFromTransportGoesToNearestSmallButton() {
        assertEquals(QUEUE, NpFocusMap.target(PLAY, NpDir.Down, false, xs))
        assertEquals(TV, NpFocusMap.target(PREV, NpDir.Down, false, xs))
        assertEquals(AIRPLAY, NpFocusMap.target(NEXT, NpDir.Down, false, xs))
    }

    @Test fun upFromSmallButtonsGoesToNearestTransportButton() {
        assertEquals(NEXT, NpFocusMap.target(LIGHT, NpDir.Up, false, xs))
        assertEquals(PREV, NpFocusMap.target(TV, NpDir.Up, false, xs))
        assertEquals(PLAY, NpFocusMap.target(QUEUE, NpDir.Up, false, xs))
    }

    @Test fun edgesStayPut() {
        assertEquals(-1, NpFocusMap.target(PREV, NpDir.Left, false, xs))
        assertEquals(-1, NpFocusMap.target(NEXT, NpDir.Right, false, xs))
        assertEquals(-1, NpFocusMap.target(TV, NpDir.Left, false, xs))
        assertEquals(-1, NpFocusMap.target(LIGHT, NpDir.Right, false, xs))
        assertEquals(-1, NpFocusMap.target(PLAY, NpDir.Up, false, xs))
        assertEquals(-1, NpFocusMap.target(QUEUE, NpDir.Down, false, xs))
    }

    @Test fun wideIsASingleRowWithoutVerticalMoves() {
        assertEquals(PREV, NpFocusMap.target(TV, NpDir.Right, true, xs))
        assertEquals(LYRICS, NpFocusMap.target(NEXT, NpDir.Right, true, xs))
        assertEquals(-1, NpFocusMap.target(PLAY, NpDir.Up, true, xs))
        assertEquals(-1, NpFocusMap.target(PLAY, NpDir.Down, true, xs))
    }

    @Test fun everyButtonReachesEveryOtherInBothLayouts() {
        for (wide in listOf(false, true)) {
            for (start in 0 until NpFocusMap.COUNT) {
                val seen = mutableSetOf(start)
                val queue = ArrayDeque(listOf(start))
                while (queue.isNotEmpty()) {
                    val cur = queue.removeFirst()
                    for (d in NpDir.values()) {
                        val t = NpFocusMap.target(cur, d, wide, xs)
                        if (t >= 0 && seen.add(t)) queue.add(t)
                    }
                }
                assertTrue("wide=$wide desde $start: $seen", seen.size == NpFocusMap.COUNT)
            }
        }
    }
}
