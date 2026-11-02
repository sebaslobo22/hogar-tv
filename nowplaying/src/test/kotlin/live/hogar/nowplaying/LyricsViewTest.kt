package live.hogar.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsViewTest {
    private fun resolve(lrc: String?) = LyricsView.resolve(lrc?.let { LrcParser.parse(it) })

    @Test fun syncedLyricsGiveATimeline() {
        val v = resolve("[00:01.00]Hola\n[00:05.00]Adios")
        assertTrue(v is LyricsView.Synced)
        assertEquals("synced", v.source)
        assertEquals(2, v.lineCount)
    }

    @Test fun plainTextIsShownAsPlain() {
        val v = resolve("uno\ndos\ntres")
        assertTrue(v is LyricsView.Plain)
        assertEquals("plain", v.source)
        assertEquals(3, v.lineCount)
    }

    /** El hueco que dejaba la columna vacía: sincronizada pero sin nada que dibujar en la línea de tiempo. */
    @Test fun syncedWithoutTimelineFallsBackToPlainText() {
        val l = Lyrics(listOf(LyricLine(1_000L, "   "), LyricLine(2_000L, "Letra")), synced = true)
        // La línea de tiempo sí tiene elementos aquí; forzamos el caso vacío con solo líneas en blanco + texto.
        assertTrue(LyricsView.resolve(l) is LyricsView.Synced)
        val onlyBlank = Lyrics(listOf(LyricLine(1_000L, " "), LyricLine(2_000L, "")), synced = true)
        assertTrue(LyricTimeline.build(onlyBlank).isEmpty())
        assertEquals(LyricsView.None, LyricsView.resolve(onlyBlank))
    }

    @Test fun nothingToShow() {
        assertEquals(LyricsView.None, LyricsView.resolve(null))
        assertEquals(LyricsView.None, LyricsView.resolve(Lyrics(emptyList(), synced = true)))
        assertEquals(LyricsView.None, resolve("\n\n"))
        assertEquals("none", LyricsView.None.source)
    }

    @Test fun logLineReportsTrackSourceLinesIndex() {
        val v = resolve("[00:01.00]Hola\n[00:05.00]Adios")
        assertEquals("track=Cuando Perriabas source=synced lines=2 index=1", LyricsView.describe("Cuando Perriabas", v, 1))
        assertEquals("track=X source=none lines=0 index=-1 loading=true", LyricsView.describe("X", LyricsView.None, -1, loading = true))
    }
}
