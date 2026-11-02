package live.hogar.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayQueueTest {
    @Test fun `next y previous recorren y se detienen en los extremos`() {
        val q = PlayQueue(listOf("a", "b", "c"))
        assertEquals("a", q.current)
        assertTrue(!q.hasPrevious)
        assertNull(q.previous())
        assertEquals("b", q.next())
        assertEquals("c", q.next())
        assertNull(q.next())
        assertEquals("c", q.current)
        assertEquals("b", q.previous())
        assertEquals("a", q.previous())
        assertEquals(0, q.index)
    }

    @Test fun `replace jumpTo y peekNext`() {
        val q = PlayQueue<String>()
        assertNull(q.current)
        assertNull(q.next())
        q.replace(listOf("x", "y", "z"), startIndex = 1)
        assertEquals("y", q.current)
        assertEquals("z", q.peekNext())
        assertEquals("z", q.jumpTo(99))
        assertEquals("x", q.jumpTo(-5))
        q.replace(emptyList())
        assertNull(q.current)
        assertEquals(0, q.size)
    }

    @Test fun `orden natural`() {
        val l = listOf("10 Final.flac", "2 Medio.flac", "01 Inicio.flac", "02 b.flac").sortedWith(::naturalCompare)
        assertEquals(listOf("01 Inicio.flac", "02 b.flac", "2 Medio.flac", "10 Final.flac"), l)
        assertTrue(naturalCompare("Pista 2", "pista 10") < 0)
    }

    @Test fun `etiqueta de carpeta`() {
        assertEquals("Jazz", folderLabel("1234-ABCD:Música/Jazz"))
        assertEquals("Music", folderLabel("primary:Music"))
        assertEquals("Almacenamiento", folderLabel("primary:"))
    }
}
