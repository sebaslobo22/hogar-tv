package live.hogar.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverMatchTest {
    private fun r(artist: String, album: String?, track: String?, dur: Long = 0, url: String? = "https://x/100x100bb.jpg") =
        ItunesResult(artist, album, track, dur, url)

    @Test fun normalizeStripsAccentsCaseAndNoise() {
        assertEquals("nina angel", CoverMatch.normalize("Niña Ángel"))
        assertEquals("song", CoverMatch.normalize("Song (feat. X) - Remastered 2011"))
        assertEquals("un break my heart", CoverMatch.normalize("Un-Break My Heart!"))
        assertEquals("abbey road", CoverMatch.normalize("Abbey Road - Single"))
        assertEquals("", CoverMatch.normalize(null))
    }

    @Test fun hiResUrlReplacesSize() {
        assertEquals("https://is1.mzstatic.com/a/b/1200x1200bb.jpg", CoverMatch.hiResUrl("https://is1.mzstatic.com/a/b/100x100bb.jpg"))
        assertEquals("https://i/x/600x600bb.png", CoverMatch.hiResUrl("https://i/x/100x100bb.png", 600))
    }

    @Test fun picksByArtistAndAlbum() {
        val good = r("Bad Bunny", "Un Verano Sin Ti", "Tití Me Preguntó")
        val wrong = r("Otro Artista", "Un Verano Sin Ti", "Tití Me Preguntó")
        assertEquals(good, CoverMatch.pick(listOf(wrong, good), "Bad Bunny", "Un Verano Sin Ti", "Tití Me Preguntó", 0))
    }

    @Test fun matchesAccentsAndEditionNoise() {
        val a = r("Café Tacvba", "Re (Deluxe)", null)
        assertEquals(a, CoverMatch.pick(listOf(a), "Cafe Tacvba", "Re", "Ingrata", 0))
    }

    @Test fun collaborationUsesFirstArtist() {
        val a = r("Shakira", "Single", "BZRP Music Sessions #53", 210_000)
        assertEquals(a, CoverMatch.pick(listOf(a), "Shakira & Bizarrap", "", "BZRP Music Sessions #53", 210_000))
    }

    @Test fun durationBreaksTies() {
        val short = r("Queen", null, "Bohemian Rhapsody", 200_000)
        val real = r("Queen", null, "Bohemian Rhapsody", 355_000)
        assertEquals(real, CoverMatch.pick(listOf(short, real), "Queen", "", "Bohemian Rhapsody", 356_000))
    }

    @Test fun rejectsWeakOrUnrelatedMatches() {
        assertNull(CoverMatch.pick(listOf(r("Otro", "Otro disco", "Otra")), "Queen", "A Night at the Opera", "Love of My Life", 0))
        // Mismo artista pero ni álbum ni título coinciden.
        assertNull(CoverMatch.pick(listOf(r("Queen", "Jazz", "Mustapha")), "Queen", "A Night at the Opera", "Love of My Life", 0))
        // Sin imagen no sirve.
        assertNull(CoverMatch.pick(listOf(r("Queen", "Jazz", "Mustapha", url = null)), "Queen", "Jazz", "Mustapha", 0))
        assertNull(CoverMatch.pick(emptyList(), "a", "b", "c", 0))
    }

    @Test fun searchUrlsTryAlbumThenSong() {
        val u = CoverMatch.searchUrls("Drake feat. Rihanna", "Take Care (Deluxe)", "Take Care (feat. Rihanna)")
        assertEquals(2, u.size)
        assertTrue(u[0].contains("entity=album") && u[0].contains("term=Drake%20Take%20Care") && u[0].contains("limit=5"))
        assertTrue(u[1].contains("entity=song") && u[1].contains("term=Take%20Care%20Drake"))
        assertEquals(1, CoverMatch.searchUrls("A", "", "T").size)
    }
}
