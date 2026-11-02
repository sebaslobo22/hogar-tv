package live.hogar.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcTest {
    @Test fun parsesTimestampsAndFractions() {
        val l = LrcParser.parse("[00:12.34]Hola\n[01:02.5]Mundo\n[00:03]Sin fraccion\n[00:04.123]Milis")
        assertTrue(l.synced)
        assertEquals(listOf(3000L, 4123L, 12340L, 62500L), l.lines.map { it.timeMs })
        assertEquals("Hola", l.lines[2].text)
    }

    @Test fun multipleStampsPerLineRepeatTheText() {
        val l = LrcParser.parse("[00:10.00][01:20.00]Coro\n[00:30.00]Verso")
        assertEquals(listOf(10_000L to "Coro", 30_000L to "Verso", 80_000L to "Coro"), l.lines.map { it.timeMs to it.text })
    }

    @Test fun offsetShiftsTimes() {
        val l = LrcParser.parse("[offset:+500]\n[00:10.00]A\n[00:01.00]B")
        assertEquals(listOf(500L, 9500L), l.lines.map { it.timeMs }) // positivo = antes; nunca negativo
        val neg = LrcParser.parse("[offset:-1000]\n[00:10.00]A")
        assertEquals(11_000L, neg.lines[0].timeMs)
    }

    @Test fun ignoresMetadataAndWordTags() {
        val l = LrcParser.parse("[ar:Artista]\n[ti:Titulo]\n[00:05.00]<00:05.00>Hola <00:05.50>mundo")
        assertEquals(1, l.lines.size)
        assertEquals("Hola mundo", l.lines[0].text)
    }

    @Test fun plainTextIsUnsynced() {
        val l = LrcParser.parse("uno\n\ndos")
        assertFalse(l.synced)
        assertEquals(listOf("uno", "dos"), l.lines.map { it.text })
        assertEquals(-1, l.indexAt(5000))
    }

    @Test fun currentLineLookup() {
        val l = LrcParser.parse("[00:05.00]a\n[00:10.00]b\n[00:20.00]c")
        assertEquals(-1, l.indexAt(0))
        assertEquals(-1, l.indexAt(4999))
        assertEquals(0, l.indexAt(5000))
        assertEquals(0, l.indexAt(9999))
        assertEquals(1, l.indexAt(10_000))
        assertEquals(2, l.indexAt(999_999))
        assertEquals(1, l.indexAt(9_800, leadMs = 250))
    }

    @Test fun emptyTextStampIsKept() {
        val l = LrcParser.parse("[00:01.00]\n[00:02.00]x")
        assertEquals(2, l.lines.size)
        assertEquals("", l.lines[0].text)
    }
}
