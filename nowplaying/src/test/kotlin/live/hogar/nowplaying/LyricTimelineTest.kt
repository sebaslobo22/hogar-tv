package live.hogar.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricTimelineTest {
    private fun build(lrc: String) = LyricTimeline.build(LrcParser.parse(lrc))

    @Test fun insertsGapWhenLinesAreFarApart() {
        val items = build("[00:01.00]Hola\n[00:30.00]Adios")
        assertEquals(listOf(false, true, false), items.map { it.gap })
        val gap = items[1]
        assertTrue(gap.startMs in 2_000L..8_000L)
        assertEquals(30_000L, gap.endMs)
    }

    @Test fun noGapForCloseLines() {
        val items = build("[00:01.00]Hola mundo cruel\n[00:05.00]Adios")
        assertTrue(items.none { it.gap })
    }

    @Test fun gapNeedsAtLeastFiveSeconds() {
        // "Hi": se estima que dura ~1,1 s; la siguiente a 5,9 s deja 4,8 s libres: no hay hueco.
        assertTrue(build("[00:01.00]Hi\n[00:06.90]Next").none { it.gap })
        // Con 6,2 s libres sí.
        assertTrue(build("[00:01.00]Hi\n[00:08.00]Next").any { it.gap })
    }

    @Test fun explicitEndFromEnhancedLineIsUsed() {
        val items = build("[00:10.00]<00:10.00>Hola <00:11.00>mundo <00:12.00>\n[00:30.00]Fin")
        val gap = items.filter { it.gap }[1]
        assertEquals(12_000L, gap.startMs)
        assertEquals(30_000L, gap.endMs)
    }

    @Test fun blankLineMarksInstrumentalBreak() {
        val items = build("[00:02.00]Cantando\n[00:14.00]\n[00:40.00]Otra vez")
        val gaps = items.filter { it.gap }
        assertEquals(1, gaps.size)
        assertEquals(14_000L, gaps[0].startMs)
        assertEquals(40_000L, gaps[0].endMs)
        assertFalse(items.any { !it.gap && it.text.isBlank() })
    }

    @Test fun introGapBeforeFirstLine() {
        val items = build("[00:20.00]Empieza")
        assertTrue(items[0].gap)
        assertEquals(0L, items[0].startMs)
        assertEquals(20_000L, items[0].endMs)
    }

    @Test fun adjacentGapsAreMerged() {
        val items = build("[00:01.00]Hi\n[00:20.00]\n[00:30.00]Fin")
        assertEquals(1, items.count { it.gap })
        assertEquals(30_000L, items.first { it.gap }.endMs)
    }

    @Test fun indexAtFindsCurrentItem() {
        val items = build("[00:01.00]Hola\n[00:30.00]Adios")
        assertEquals(-1, LyricTimeline.indexAt(items, 500))
        assertEquals(0, LyricTimeline.indexAt(items, 1_000))
        assertEquals(1, LyricTimeline.indexAt(items, 12_000))
        assertEquals(2, LyricTimeline.indexAt(items, 31_000))
    }

    @Test fun plainLyricsProduceNoTimeline() {
        assertTrue(LyricTimeline.build(LrcParser.parse("uno\ndos")).isEmpty())
    }
}
