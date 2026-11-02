package live.hogar.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TabPolicyTest {
    @Test fun touchMovesToEnd() {
        assertEquals(listOf(2, 3, 1), TabPolicy.touch(listOf(1, 2, 3), 1))
        assertEquals(listOf(1, 2, 3), TabPolicy.touch(listOf(1, 2), 3))
        assertEquals(listOf(1, 2, 3), TabPolicy.touch(listOf(1, 2, 3), 3))
    }

    @Test fun noEvictionUnderLimit() {
        assertTrue(TabPolicy.evictions(listOf(1, 2, 3), setOf(1, 2, 3), active = 3).isEmpty())
    }

    @Test fun evictsLeastRecentlyUsed() {
        // 1 es el más antiguo, 4 el activo
        assertEquals(listOf(1), TabPolicy.evictions(listOf(1, 2, 3, 4), setOf(1, 2, 3, 4), active = 4))
        assertEquals(listOf(1, 2), TabPolicy.evictions(listOf(1, 2, 3, 4, 5), setOf(1, 2, 3, 4, 5), active = 5))
    }

    @Test fun neverEvictsActiveEvenIfOldest() {
        assertEquals(listOf(2), TabPolicy.evictions(listOf(1, 2, 3, 4), setOf(1, 2, 3, 4), active = 1))
    }

    @Test fun onlyLiveTabsCount() {
        // 2 y 3 ya no tienen WebView: solo hay 3 vivas, no se expulsa nada
        assertTrue(TabPolicy.evictions(listOf(1, 2, 3, 4, 5), setOf(1, 4, 5), active = 5).isEmpty())
        // una viva que nunca se registró en el orden cuenta como la más reciente
        assertEquals(listOf(1), TabPolicy.evictions(listOf(1, 2, 3), setOf(1, 2, 3, 9), active = 3))
    }

    @Test fun maxTabsRule() {
        assertTrue(TabPolicy.canOpenNew(5))
        assertFalse(TabPolicy.canOpenNew(6))
    }

    @Test fun activeAfterClose() {
        assertNull(TabPolicy.activeAfterClose(1, 0))
        assertEquals(1, TabPolicy.activeAfterClose(4, 1)) // la vecina de la derecha ocupa el lugar
        assertEquals(2, TabPolicy.activeAfterClose(4, 3)) // cerrar la última deja la nueva última
        assertEquals(0, TabPolicy.activeAfterClose(2, 0))
    }
}
