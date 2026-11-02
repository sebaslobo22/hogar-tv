package live.hogar.nowplaying

import java.text.Normalizer
import kotlin.math.abs

/** Un resultado de la API pública de búsqueda de iTunes. */
data class ItunesResult(
    val artist: String?,
    val album: String?,
    val track: String?,
    val durationMs: Long,
    val artworkUrl100: String?,
)

/**
 * Elección de la carátula de alta resolución en iTunes Search (puro, probado en la JVM).
 * Se compara con nombres normalizados (sin tildes, mayúsculas, "feat.", "Remastered", signos).
 */
object CoverMatch {
    const val HI_RES = 1200
    private const val MIN_SCORE = 4.0
    private val PUNCT = Regex("""[^\p{L}\p{N}]+""")
    private val ALBUM_SUFFIX = Regex("""\s*[-–—]\s*(?:single|ep)\s*$""", RegexOption.IGNORE_CASE)
    private val SIZE = Regex("""\d+x\d+bb""")

    fun normalize(s: String?): String {
        if (s.isNullOrBlank()) return ""
        val base = ALBUM_SUFFIX.replace(LrclibNames.cleanTitle(s), "")
        val flat = Normalizer.normalize(base, Normalizer.Form.NFD).replace(Regex("""\p{M}+"""), "")
        return PUNCT.replace(flat.lowercase(), " ").trim()
    }

    /** "…/100x100bb.jpg" -> "…/1200x1200bb.jpg". */
    fun hiResUrl(artworkUrl100: String, size: Int = HI_RES): String =
        SIZE.replace(artworkUrl100, "${size}x${size}bb")

    private fun nameScore(want: String, got: String): Double = when {
        want.isEmpty() || got.isEmpty() -> 0.0
        want == got -> 3.0
        got.contains(want) || want.contains(got) -> 1.5
        else -> 0.0
    }

    /** Artistas a comparar: el completo y el primero de una colaboración. */
    private fun artistScore(wantArtist: String, got: String?): Double {
        val g = normalize(got)
        return LrclibNames.artistVariants(wantArtist).maxOfOrNull { nameScore(normalize(it), g) } ?: 0.0
    }

    /**
     * Mejor resultado para la canción, o null si ninguno convence. Exige artista coincidente y,
     * además, álbum o título; la duración (±3 s) desempata y suma.
     */
    fun pick(results: List<ItunesResult>, artist: String, album: String, title: String, durationMs: Long): ItunesResult? {
        var best: ItunesResult? = null
        var bestScore = 0.0
        val wantAlbum = normalize(album)
        val wantTitle = normalize(title)
        for (r in results) {
            if (r.artworkUrl100.isNullOrBlank()) continue
            val a = artistScore(artist, r.artist)
            if (a <= 0.0) continue
            val al = nameScore(wantAlbum, normalize(r.album))
            val t = nameScore(wantTitle, normalize(r.track))
            if (al <= 0.0 && t <= 0.0) continue
            var score = a + al + t
            if (durationMs > 0 && r.durationMs > 0 && abs(r.durationMs - durationMs) <= 3_000) score += 1.0
            if (score > bestScore) { bestScore = score; best = r }
        }
        return best.takeIf { bestScore >= MIN_SCORE }
    }

    /** Consultas, de la más precisa a la más amplia (álbum y luego canción). */
    fun searchUrls(artist: String, album: String, title: String): List<String> {
        val first = LrclibNames.artistVariants(artist).last()
        val urls = ArrayList<String>()
        val alb = normalize(album)
        if (alb.isNotEmpty()) urls += url("$first ${LrclibNames.cleanTitle(album)}", "album")
        val t = LrclibNames.cleanTitle(title)
        if (t.isNotEmpty()) urls += url("$t $first", "song")
        return urls
    }

    private fun url(term: String, entity: String) =
        "https://itunes.apple.com/search?term=${LrclibNames.enc(term.trim())}&entity=$entity&limit=5"
}
