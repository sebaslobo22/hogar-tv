package live.hogar.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LrclibPickTest {
    private fun c(s: String?, p: String?, d: Double, i: Boolean = false) = LrclibCandidate(s, p, d, i)

    @Test fun prefersSyncedThenClosestDuration() {
        val a = c(null, "plain", 200.0)
        val b = c("[00:01.00]x", null, 260.0)
        val d = c("[00:01.00]y", null, 201.0)
        assertEquals(d, LrclibPick.best(listOf(a, b, d), 200))
    }

    @Test fun fallsBackToPlainAndSkipsInstrumental() {
        val plain = c(null, "uno\ndos", 100.0)
        assertEquals(plain, LrclibPick.best(listOf(c(null, null, 100.0, true), plain), 100))
        assertNull(LrclibPick.best(listOf(c(null, null, 1.0, true)), 1))
    }

    @Test fun convertsToLyrics() {
        val l = LrclibPick.toLyrics(c("[00:01.00]hola", "hola", 1.0))!!
        assertEquals(true, l.synced)
        val p = LrclibPick.toLyrics(c(null, "a\nb", 1.0))!!
        assertEquals(false, p.synced)
        assertEquals(2, p.lines.size)
    }
}
