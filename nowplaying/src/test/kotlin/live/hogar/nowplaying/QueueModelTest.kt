package live.hogar.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueModelTest {
    private fun raw(id: Long, t: String, s: String = "", uri: String? = null) = RawQueueItem(id, t, s, uri, null)

    @Test fun marksTheActiveItemById() {
        val rows = QueueMapper.map(listOf(raw(1, "A"), raw(7, "B"), raw(9, "C")), activeId = 7, currentTitle = "A")
        assertEquals(listOf(false, true, false), rows.map { it.current })
    }

    @Test fun fallsBackToTitleWhenIdUnknown() {
        val rows = QueueMapper.map(listOf(raw(1, "Uno"), raw(2, " dos ")), activeId = QueueMapper.UNKNOWN_ID, currentTitle = "DOS")
        assertEquals(listOf(false, true), rows.map { it.current })
        assertEquals("dos", rows[1].title)
    }

    @Test fun noActiveWithoutIdOrTitle() {
        val rows = QueueMapper.map(listOf(raw(1, "Uno")), QueueMapper.UNKNOWN_ID, null)
        assertTrue(rows.none { it.current })
        assertEquals(-1, QueueState(null, rows).currentIndex)
    }

    @Test fun dropsEmptyItemsBlankUriAndCapsLength() {
        val rows = QueueMapper.map(listOf(raw(1, "", ""), raw(2, "", "Solo subtítulo", " ")), QueueMapper.UNKNOWN_ID, null)
        assertEquals(1, rows.size)
        assertNull(rows[0].iconUri)
        val many = (1L..500L).map { raw(it, "T$it") }
        assertEquals(QueueMapper.MAX_ROWS, QueueMapper.map(many, QueueMapper.UNKNOWN_ID, null).size)
    }

    @Test fun emptyQueueMeansNotShared() {
        assertFalse(QueueState().available)
        assertTrue(QueueState("x", QueueMapper.map(listOf(raw(1, "A")), 1, null)).available)
    }

    @Test fun thumbnailsAreNeverUpscaled() {
        assertEquals(40, QueueMapper.thumbSidePx(nativePx = 40, slotPx = 92))
        assertEquals(92, QueueMapper.thumbSidePx(nativePx = 600, slotPx = 92))
        assertEquals(0, QueueMapper.thumbSidePx(0, 92))
    }

    @Test fun sessionCapabilitiesComeFromAdvertisedActions() {
        assertFalse(SessionCaps.canShuffle(0L))
        assertFalse(SessionCaps.canRepeat(0L))
        assertTrue(SessionCaps.canRepeat(1L shl 18))
        assertTrue(SessionCaps.canShuffle(1L shl 19))
        assertTrue(SessionCaps.canShuffle(1L shl 23))
        assertFalse(SessionCaps.canShuffle(1L shl 18))
        assertTrue(SessionCaps.canSkipToQueueItem(1L shl 12))
        assertEquals(listOf(2, 0, 1), listOf(0, 1, 2).map { SessionCaps.nextRepeat(it) })
    }

    @Test fun chromeGeometryInterpolatesAndClamps() {
        val g = ChromeGeom.of(fullPx = 1920f, centerWPx = 1280f, sideXPx = 1056f, safeHPx = 96f)
        assertEquals(1280f, g.width(0f), 0.01f)
        assertEquals(320f, g.x(0f), 0.01f)
        assertEquals(768f, g.width(1f), 0.01f)
        assertEquals(1056f, g.x(1f), 0.01f)
        assertEquals(g.width(1f), g.width(1.3f), 0.01f) // el rebote del resorte no se sale de la pantalla
        assertEquals(1920f / 2f - (112f + 420f / 2f), ChromeGeom.centerShift(1920f, 112f, 420f), 0.01f)
    }
}
