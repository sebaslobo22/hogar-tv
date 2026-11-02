package live.hogar.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTest {
    @Test fun defaultsHaveTheFiveSites() {
        val d = DataJson.decode(null)
        assertEquals(
            listOf("Google", "YouTube", "Apple Music", "APKMirror", "Wikipedia"),
            d.favorites.map { it.title },
        )
        assertTrue(d.desktop)
        assertEquals(100, d.zoom)
    }

    @Test fun roundTrip() {
        val d = BrowserData(
            favorites = listOf(Site("https://a.com/", "A \"quoted\" ñ")),
            history = listOf(Visit("https://b.com/x", "B", 123L)),
            tabs = listOf(SavedTab("https://a.com/", "A"), SavedTab("", "")),
            active = 1, desktop = false, zoom = 150, scrollMode = true,
        )
        assertEquals(d, DataJson.decode(DataJson.encode(d)))
    }

    @Test fun emptyFavoritesStayEmpty() {
        val d = BrowserData(favorites = emptyList())
        assertEquals(emptyList<Site>(), DataJson.decode(DataJson.encode(d)).favorites)
    }

    @Test fun corruptFileFallsBackToDefaults() {
        assertEquals(BrowserData(), DataJson.decode("{ no es json"))
        assertEquals(BrowserData(), DataJson.decode(""))
    }

    @Test fun missingFavoritesKeyMeansFactoryList() {
        assertEquals(BrowserData.DefaultFavorites, DataJson.decode("""{"v":1,"zoom":125}""").favorites)
    }

    @Test fun nonWebUrlsAreDroppedOnLoad() {
        val json = """{"favorites":[{"u":"javascript:alert(1)","t":"x"},{"u":"https://ok.com/","t":"ok"}],
            "tabs":[{"u":"file:///a","t":"f"},{"u":"https://ok.com/","t":"ok"}]}"""
        val d = DataJson.decode(json)
        assertEquals(listOf(Site("https://ok.com/", "ok")), d.favorites)
        assertEquals(listOf(SavedTab("https://ok.com/", "ok")), d.tabs)
    }

    @Test fun zoomAndActiveAreClamped() {
        val d = DataJson.decode("""{"zoom":300,"active":9,"tabs":[{"u":"https://a.com/","t":""}]}""")
        assertEquals(175, d.zoom)
        assertEquals(0, d.active)
    }

    @Test fun tabsCapped() {
        val tabs = (1..9).joinToString(",") { """{"u":"https://t$it.com/","t":""}""" }
        assertEquals(TabPolicy.MAX_TABS, DataJson.decode("""{"tabs":[$tabs]}""").tabs.size)
    }

    @Test fun toggleFavorite() {
        val base = listOf(Site("https://www.google.com/", "Google"))
        val added = Favorites.toggle(base, Site("https://example.com/p", "Ej"))
        assertEquals(2, added.size)
        assertTrue(Favorites.contains(added, "https://example.com/p"))
        // misma página con otra forma de escribirla: la quita
        val removed = Favorites.toggle(added, Site("http://www.example.com/p/#frag", "Ej"))
        assertEquals(base, removed)
        assertFalse(Favorites.contains(removed, "https://example.com/p"))
    }

    @Test fun toggleFillsBlankTitleWithHost() {
        val r = Favorites.toggle(emptyList(), Site("https://www.foo.org/a", ""))
        assertEquals("foo.org", r.single().title)
    }

    @Test fun historyMovesRepeatedToFrontAndCaps() {
        var h = emptyList<Visit>()
        h = History.add(h, Visit("https://a.com/", "A", 1))
        h = History.add(h, Visit("https://b.com/", "B", 2))
        h = History.add(h, Visit("https://www.a.com", "A2", 3))
        assertEquals(listOf("A2", "B"), h.map { it.title })
        repeat(60) { h = History.add(h, Visit("https://s$it.com/", "s", it.toLong())) }
        assertEquals(History.CAP, h.size)
        assertEquals("https://s59.com/", h.first().url)
    }
}
