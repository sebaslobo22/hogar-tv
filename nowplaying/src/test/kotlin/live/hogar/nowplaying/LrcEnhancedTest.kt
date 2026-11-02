package live.hogar.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcEnhancedTest {
    @Test fun parsesWordTimings() {
        val l = LrcParser.parse("[00:05.00]<00:05.00>Hola <00:05.50>mundo <00:06.20>cruel <00:07.00>")
        val line = l.lines.single()
        assertEquals("Hola mundo cruel", line.text)
        assertEquals(listOf(5000L, 5500L, 6200L), line.words.map { it.startMs })
        assertEquals(listOf("Hola ", "mundo ", "cruel"), line.words.map { it.text })
        assertEquals(7000L, line.endMs)
        assertEquals(line.text, line.words.joinToString("") { it.text })
    }

    @Test fun textBeforeFirstTagUsesLineTime() {
        val line = LrcParser.parse("[00:10.00]Oh <00:10.40>baby").lines.single()
        assertEquals(listOf(10_000L, 10_400L), line.words.map { it.startMs })
        assertEquals("Oh baby", line.text)
    }

    @Test fun offsetShiftsWordsToo() {
        val line = LrcParser.parse("[offset:-1000]\n[00:05.00]<00:05.00>a <00:06.00>b <00:07.00>").lines.single()
        assertEquals(6000L, line.timeMs)
        assertEquals(listOf(6000L, 7000L), line.words.map { it.startMs })
        assertEquals(8000L, line.endMs)
    }

    @Test fun repeatedStampsDropWordsButStripTags() {
        val l = LrcParser.parse("[00:10.00][01:20.00]<00:10.00>Coro <00:11.00>aqui")
        assertEquals(2, l.lines.size)
        assertTrue(l.lines.all { it.words.isEmpty() && it.text == "Coro aqui" })
    }

    @Test fun plainLinesHaveNoWords() {
        val line = LrcParser.parse("[00:05.00]Hola mundo").lines.single()
        assertTrue(line.words.isEmpty())
        assertEquals(-1L, line.endMs)
    }

    @Test fun sweepPositionAdvancesWordByWord() {
        val words = listOf(LyricWord(1000, "a "), LyricWord(2000, "b "), LyricWord(3000, "c"))
        assertEquals(0f, WordSweep.position(words, 4000, 500), 0f)
        assertEquals(0.5f, WordSweep.position(words, 4000, 1500), 1e-4f)
        assertEquals(1.25f, WordSweep.position(words, 4000, 2250), 1e-4f)
        assertEquals(2.5f, WordSweep.position(words, 4000, 3500), 1e-4f)
        assertEquals(3f, WordSweep.position(words, 4000, 4000), 0f)
        assertEquals(3f, WordSweep.position(words, 4000, 9999), 0f)
    }
}
