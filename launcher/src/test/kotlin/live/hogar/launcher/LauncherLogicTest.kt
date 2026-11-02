package live.hogar.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherLogicTest {
    private val installed = listOf("a", "b", "c", "d", "e")
    private val empty = LauncherPrefs()

    @Test fun defaultIsAlphabeticalAllInRest() {
        val r = LauncherLogic.arrange(installed, empty)
        assertEquals(emptyList<String>(), r.pinned)
        assertEquals(installed, r.rest)
    }

    @Test fun pinnedKeepPinOrderAndLeaveRest() {
        val r = LauncherLogic.arrange(installed, empty.copy(pinned = listOf("d", "b")))
        assertEquals(listOf("d", "b"), r.pinned)
        assertEquals(listOf("a", "c", "e"), r.rest)
    }

    @Test fun hiddenExcludedUnlessShown() {
        val p = empty.copy(hidden = setOf("c"))
        assertEquals(listOf("a", "b", "d", "e"), LauncherLogic.arrange(installed, p).rest)
        assertEquals(installed, LauncherLogic.arrange(installed, p.copy(showHidden = true)).rest)
    }

    @Test fun manualOrderFirstThenNewAppsAlphabetical() {
        val r = LauncherLogic.arrange(installed, empty.copy(order = listOf("e", "x", "b")))
        assertEquals(listOf("e", "b", "a", "c", "d"), r.rest)
    }

    @Test fun uninstalledPinnedIgnored() {
        assertEquals(listOf("a"), LauncherLogic.arrange(installed, empty.copy(pinned = listOf("zz", "a"))).pinned)
    }

    @Test fun pinTogglesAndUnhides() {
        val p = LauncherLogic.togglePin(empty.copy(hidden = setOf("a")), "a")
        assertEquals(listOf("a"), p.pinned)
        assertEquals(emptySet<String>(), p.hidden)
        assertEquals(emptyList<String>(), LauncherLogic.togglePin(p, "a").pinned)
    }

    @Test fun hideUnpinsAndTogglesBack() {
        val p = LauncherLogic.toggleHidden(empty.copy(pinned = listOf("a")), "a")
        assertEquals(emptyList<String>(), p.pinned)
        assertEquals(setOf("a"), p.hidden)
        assertEquals(emptySet<String>(), LauncherLogic.toggleHidden(p, "a").hidden)
    }

    @Test fun moveInRest() {
        val p = LauncherLogic.move(installed, empty, "c", -1)
        assertEquals(listOf("a", "c", "b", "d", "e"), LauncherLogic.arrange(installed, p).rest)
        val q = LauncherLogic.move(installed, p, "c", 1)
        assertEquals(installed, LauncherLogic.arrange(installed, q).rest)
    }

    @Test fun moveAtEdgeDoesNothing() {
        assertEquals(empty, LauncherLogic.move(installed, empty, "a", -1))
        assertEquals(empty, LauncherLogic.move(installed, empty, "e", 1))
        assertEquals(empty, LauncherLogic.move(installed, empty, "nope", 1))
    }

    @Test fun moveInPinned() {
        val p = LauncherLogic.move(installed, empty.copy(pinned = listOf("a", "b", "c")), "c", -1)
        assertEquals(listOf("a", "c", "b"), LauncherLogic.arrange(installed, p).pinned)
    }

    @Test fun moveSkipsHiddenNeighbours() {
        val p = empty.copy(hidden = setOf("b"))
        val moved = LauncherLogic.move(installed, p, "c", -1)
        assertEquals(listOf("c", "a", "d", "e"), LauncherLogic.arrange(installed, moved).rest)
    }

    @Test fun encodeDecodeRoundTrip() {
        val l = listOf("com.x.y", "live.z")
        assertEquals(l, LauncherLogic.decode(LauncherLogic.encode(l)))
        assertEquals(emptyList<String>(), LauncherLogic.decode(null))
        assertEquals(emptyList<String>(), LauncherLogic.decode(""))
    }
}

class OriginalLogoTest {
    @Test fun togglesPerPackage() {
        val p = LauncherLogic.toggleOriginalLogo(LauncherPrefs(), "a")
        assertEquals(setOf("a"), p.originalLogo)
        assertEquals(setOf("a", "b"), LauncherLogic.toggleOriginalLogo(p, "b").originalLogo)
        assertEquals(emptySet<String>(), LauncherLogic.toggleOriginalLogo(p, "a").originalLogo)
    }

    @Test fun hintSeenIsSticky() {
        val p = LauncherLogic.markHintSeen(LauncherPrefs())
        assertEquals(true, p.hintSeen)
        assertEquals(p, LauncherLogic.markHintSeen(p))
    }
}
