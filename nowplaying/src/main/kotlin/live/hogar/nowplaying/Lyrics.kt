package live.hogar.nowplaying

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.math.abs

sealed interface LyricsState {
    data object Loading : LyricsState
    data object None : LyricsState
    data class Ready(val lyrics: Lyrics) : LyricsState
}

/** Candidato devuelto por LRCLIB (para elegir el mejor). */
data class LrclibCandidate(val synced: String?, val plain: String?, val durationSec: Double, val instrumental: Boolean)

object LrclibPick {
    const val DURATION_TOLERANCE_SEC = 3.0

    /**
     * Prefiere letra sincronizada cuya duración difiera como máximo [DURATION_TOLERANCE_SEC] de
     * [durationSec] (si se conoce; otra versión de la canción desfasaría la letra). Sin sincronizada
     * dentro de la tolerancia, cae a texto plano (no depende del tiempo), el de duración más cercana.
     */
    fun best(list: List<LrclibCandidate>, durationSec: Int): LrclibCandidate? {
        val usable = list.filter { !it.instrumental && (!it.synced.isNullOrBlank() || !it.plain.isNullOrBlank()) }
        fun dist(c: LrclibCandidate) = if (durationSec > 0) abs(c.durationSec - durationSec) else 0.0
        return usable.filter { !it.synced.isNullOrBlank() && dist(it) <= DURATION_TOLERANCE_SEC }.minByOrNull(::dist)
            ?: usable.filter { !it.plain.isNullOrBlank() }.minByOrNull(::dist)?.copy(synced = null)
    }

    fun toLyrics(c: LrclibCandidate): Lyrics? {
        if (!c.synced.isNullOrBlank()) {
            val l = LrcParser.parse(c.synced)
            if (l.synced && !l.isEmpty) return l
        }
        if (!c.plain.isNullOrBlank()) {
            val l = LrcParser.parse(c.plain)
            if (!l.isEmpty) return Lyrics(l.lines.map { LyricLine(-1L, it.text) }, synced = false)
        }
        return null
    }
}

/** Variantes de título y artista para buscar en LRCLIB (puro, probado en la JVM). */
object LrclibNames {
    private val FEAT_BRACKET = Regex("""\s*[\(\[]\s*(?:feat|ft|featuring|with|con)\b[^\)\]]*[\)\]]""", RegexOption.IGNORE_CASE)
    private val EDITION_BRACKET = Regex(
        """\s*[\(\[][^\)\]]*\b(?:remaster(?:ed)?|deluxe|bonus|mono|stereo|single version|album version|radio edit|explicit|clean|anniversary)\b[^\)\]]*[\)\]]""",
        RegexOption.IGNORE_CASE,
    )
    private val FEAT_TAIL = Regex("""\s+(?:feat\.?|ft\.?|featuring)\s+.*$""", RegexOption.IGNORE_CASE)
    private val DASH_TAIL = Regex(
        """\s+[-–—]\s+(?:\d{4}\b.*|.*\b(?:remaster(?:ed)?|version|mono|stereo|edit|deluxe|bonus|live|from)\b.*)$""",
        RegexOption.IGNORE_CASE,
    )
    private val ARTIST_SPLIT = Regex(
        """\s*(?:,|&|;)\s*|\s+(?:x|X)\s+|\s+(?:feat\.?|ft\.?|featuring|with)\s+""",
        RegexOption.IGNORE_CASE,
    )
    private val WS = Regex("""\s+""")

    /** Título sin "(feat. …)", "- Remastered 2011", "(Deluxe)", etc. */
    fun cleanTitle(title: String): String {
        var t = title
        t = FEAT_BRACKET.replace(t, "")
        t = EDITION_BRACKET.replace(t, "")
        t = DASH_TAIL.replace(t, "")
        t = FEAT_TAIL.replace(t, "")
        return WS.replace(t, " ").trim().ifEmpty { title.trim() }
    }

    /** Títulos a probar, del más fiel al más limpio, sin repetir. */
    fun titleVariants(title: String): List<String> {
        val raw = WS.replace(title, " ").trim()
        val clean = cleanTitle(title)
        return listOf(raw, clean, clean.replace("#", "").let { WS.replace(it, " ").trim() })
            .filter { it.isNotEmpty() }.distinct()
    }

    /** Artistas a probar: el completo y, si es colaboración, solo el primero. */
    fun artistVariants(artist: String): List<String> {
        val full = WS.replace(artist, " ").trim()
        val first = ARTIST_SPLIT.split(full).firstOrNull()?.trim().orEmpty()
        return listOf(full, first).filter { it.isNotEmpty() }.distinct()
    }

    /** Codifica un valor de consulta (los espacios como %20, "#" y "&" escapados). */
    fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

/**
 * Letras de LRCLIB (https://lrclib.net, pública, sin clave). Caché en disco por canción
 * (`filesDir/lyrics`); "no encontrada" se recuerda un día, los errores de red no se guardan.
 * Todo en el hilo IO y sin lanzar excepciones.
 */
class LyricsRepository(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "lyrics")

    suspend fun load(track: TrackInfo): LyricsState = withContext(Dispatchers.IO) {
        runCatching { loadBlocking(track) }.getOrDefault(LyricsState.None)
    }

    private fun loadBlocking(t: TrackInfo): LyricsState {
        val durSec = (t.durationMs / 1000).toInt()
        val file = File(dir, sha1("${t.title}|${t.artist}|${t.album}|$durSec".lowercase()) + ".json")
        readCache(file)?.let { return it }

        val titles = LrclibNames.titleVariants(t.title)
        val artists = LrclibNames.artistVariants(t.artist)
        var net = false
        var cand: LrclibCandidate? = null
        var requests = 0

        // 1) /get exacto (necesita álbum y duración): título fiel + artista completo, luego limpio + primer artista.
        if (t.album.isNotBlank() && durSec > 0) {
            val combos = listOf(titles.first() to artists.first(), titles.last() to artists.last()).distinct()
            for ((title, artist) in combos) {
                if (requests++ >= MAX_REQUESTS) break
                val r = get("get?track_name=${LrclibNames.enc(title)}&artist_name=${LrclibNames.enc(artist)}" +
                    "&album_name=${LrclibNames.enc(t.album)}&duration=$durSec")
                if (r.ok) net = true
                cand = r.body?.let { candidate(JSONObject(it)) }
                if (cand?.let { LrclibPick.toLyrics(it) } != null) break
            }
        }
        // 2) /search por título y artista (variantes), eligiendo por duración ±3 s.
        if (cand?.let { LrclibPick.toLyrics(it) } == null) {
            val all = ArrayList<LrclibCandidate>()
            search@ for (artist in artists) for (title in titles) {
                if (requests++ >= MAX_REQUESTS) break@search
                val r = get("search?track_name=${LrclibNames.enc(title)}&artist_name=${LrclibNames.enc(artist)}")
                if (r.ok) net = true
                all += r.body?.let { runCatching { parseList(it) }.getOrNull() }.orEmpty()
                val b = LrclibPick.best(all, durSec)
                if (b != null && !b.synced.isNullOrBlank()) break@search
            }
            // 3) búsqueda libre como último recurso.
            if (LrclibPick.best(all, durSec) == null && requests < MAX_REQUESTS) {
                requests++
                val r = get("search?q=${LrclibNames.enc(titles[titles.size - 1] + " " + artists.last())}")
                if (r.ok) net = true
                all += r.body?.let { runCatching { parseList(it) }.getOrNull() }.orEmpty()
            }
            cand = LrclibPick.best(all, durSec) ?: cand
        }
        val lyrics = cand?.let { LrclibPick.toLyrics(it) }
        if (lyrics != null) {
            writeCache(file, JSONObject().put("v", 2).put("found", true).put("synced", lyrics.synced)
                .put("text", encode(lyrics)))
            return LyricsState.Ready(lyrics)
        }
        if (net) writeCache(file, JSONObject().put("v", 2).put("found", false).put("at", System.currentTimeMillis()))
        return LyricsState.None
    }

    // ------------------------------------------------------------------ red

    private class Resp(val ok: Boolean, val body: String?)

    private fun get(path: String): Resp = try {
        val c = URL("https://lrclib.net/api/$path").openConnection() as HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "Hogar-TV/0.1 (https://github.com/sebaslobo22)")
        c.setRequestProperty("Accept", "application/json")
        try {
            when (c.responseCode) {
                200 -> Resp(true, c.inputStream.bufferedReader().use { it.readText() })
                404 -> Resp(true, null) // el servidor respondió: no existe
                else -> Resp(false, null)
            }
        } finally { c.disconnect() }
    } catch (_: Exception) { Resp(false, null) }

    private fun candidate(o: JSONObject) = LrclibCandidate(
        synced = o.optString("syncedLyrics").takeIf { !o.isNull("syncedLyrics") && it.isNotBlank() },
        plain = o.optString("plainLyrics").takeIf { !o.isNull("plainLyrics") && it.isNotBlank() },
        durationSec = o.optDouble("duration", 0.0),
        instrumental = o.optBoolean("instrumental", false),
    )

    private fun parseList(body: String): List<LrclibCandidate> {
        val a = JSONArray(body)
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::candidate) }
    }

    // ------------------------------------------------------------------ disco

    private fun encode(l: Lyrics): String =
        if (l.synced) l.lines.joinToString("\n") { stamp("[", it.timeMs, "]") + body(it) }
        else l.lines.joinToString("\n") { it.text }

    private fun stamp(open: String, ms: Long, close: String) =
        "%s%02d:%02d.%02d%s".format(open, ms / 60000, ms / 1000 % 60, ms % 1000 / 10, close)

    /** Texto de la línea; con palabras, en formato enhanced (`<mm:ss.xx>palabra`) para no perder el barrido. */
    private fun body(l: LyricLine): String =
        if (l.words.isEmpty()) l.text
        else l.words.joinToString("") { stamp("<", it.startMs, ">") + it.text } + if (l.endMs >= 0) stamp("<", l.endMs, ">") else ""

    private fun readCache(f: File): LyricsState? = runCatching {
        if (!f.isFile) return null
        val o = JSONObject(f.readText())
        if (o.optBoolean("found")) {
            val text = o.getString("text")
            val l = if (o.optBoolean("synced")) LrcParser.parse(text)
            else Lyrics(text.lines().map { LyricLine(-1L, it) }, synced = false)
            if (l.isEmpty) null else LyricsState.Ready(l)
        } else if (o.optInt("v") >= 2 && System.currentTimeMillis() - o.optLong("at") < NEGATIVE_TTL_MS) LyricsState.None else null
    }.getOrNull()

    private fun writeCache(f: File, o: JSONObject) {
        runCatching { dir.mkdirs(); f.writeText(o.toString()) }
    }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val NEGATIVE_TTL_MS = 24 * 3600_000L
        const val MAX_REQUESTS = 6
    }
}
