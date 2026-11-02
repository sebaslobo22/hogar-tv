package live.hogar.nowplaying

/**
 * Qué se dibuja en la columna de letra, decidido en un solo sitio (puro, probado en la JVM).
 * Antes `items` y `plain` se derivaban por separado y una letra sincronizada cuya lista de
 * dibujo salía vacía dejaba la columna sin nada aunque el modo letra estuviera activo.
 */
sealed interface LyricsView {
    /** "synced" | "plain" | "none" (para el registro HogarLyrics). */
    val source: String
    val lineCount: Int

    data object None : LyricsView {
        override val source get() = "none"
        override val lineCount get() = 0
    }

    class Synced(val items: List<LyricItem>) : LyricsView {
        override val source get() = "synced"
        override val lineCount get() = items.size
    }

    class Plain(val lines: List<String>) : LyricsView {
        override val source get() = "plain"
        override val lineCount get() = lines.size
    }

    companion object {
        /** Sincronizada con línea de tiempo → [Synced]; si no hay línea de tiempo usable, texto → [Plain]; nada → [None]. */
        fun resolve(lyrics: Lyrics?): LyricsView {
            if (lyrics == null || lyrics.isEmpty) return None
            if (lyrics.synced) {
                val items = LyricTimeline.build(lyrics)
                if (items.isNotEmpty()) return Synced(items)
            }
            if (lyrics.lines.none { it.text.isNotBlank() }) return None
            return Plain(lyrics.lines.map { it.text })
        }

        /** Línea de registro (etiqueta HogarLyrics): canción, origen, líneas, índice vigente. */
        fun describe(track: String, view: LyricsView, index: Int, loading: Boolean = false): String =
            "track=$track source=${view.source} lines=${view.lineCount} index=$index" + if (loading) " loading=true" else ""
    }
}
