package live.hogar.nowplaying

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Carátula de alta resolución (1200 px) desde la API pública de iTunes Search cuando la sesión de
 * medios solo ofrece una miniatura. Caché en disco por canción (`cacheDir/covers`); "no hay" se
 * recuerda un día. IO bloqueante: llamar desde Dispatchers.IO. Nunca lanza.
 */
class CoverRepository(context: Context) {
    private val dir = File(context.applicationContext.cacheDir, "covers")

    fun fetch(t: TrackInfo): Bitmap? = runCatching { fetchBlocking(t) }.getOrNull()

    private fun fetchBlocking(t: TrackInfo): Bitmap? {
        val name = sha1("v2|${t.title}|${t.artist}|${t.album}".lowercase()) // v2: con Deezer (invalida "no encontrado" previos)
        val img = File(dir, "$name.jpg")
        val none = File(dir, "$name.none")
        if (img.isFile) decode(img.readBytes())?.let { return it }
        if (none.isFile && System.currentTimeMillis() - none.lastModified() < NEGATIVE_TTL_MS) return null

        var net = false
        for (u in CoverMatch.searchUrls(t.artist, t.album, t.title)) {
            val body = httpText(u) ?: continue
            net = true
            val hit = CoverMatch.pick(parse(body), t.artist, t.album, t.title, t.durationMs) ?: continue
            val bytes = httpBytes(CoverMatch.hiResUrl(hit.artworkUrl100!!)) ?: continue
            val bmp = decode(bytes) ?: continue
            runCatching { dir.mkdirs(); img.writeBytes(bytes) }
            return bmp
        }
        // Segunda fuente: Deezer (catálogo público, portadas de 1000 px o más; cubre discos que la
        // búsqueda pública de Apple no devuelve, por ejemplo X 100PRE).
        for (u in deezerUrls(t)) {
            val body = httpText(u) ?: continue
            net = true
            val hit = CoverMatch.pick(parseDeezer(body), t.artist, t.album, t.title, t.durationMs) ?: continue
            val xl = hit.artworkUrl100 ?: continue
            val bytes = httpBytes(xl.replace("1000x1000", "1400x1400")) ?: httpBytes(xl) ?: continue
            val bmp = decode(bytes) ?: continue
            runCatching { dir.mkdirs(); img.writeBytes(bytes) }
            return bmp
        }
        if (net) runCatching { dir.mkdirs(); none.writeText("") }
        return null
    }

    private fun deezerUrls(t: TrackInfo): List<String> {
        val artist = LrclibNames.artistVariants(t.artist).last().replace("\"", "")
        val out = ArrayList<String>(2)
        val album = LrclibNames.cleanTitle(t.album).replace("\"", "")
        if (album.isNotBlank()) out += "https://api.deezer.com/search/album?limit=5&q=" + LrclibNames.enc("artist:\"$artist\" album:\"$album\"")
        val title = LrclibNames.cleanTitle(t.title).replace("\"", "")
        if (title.isNotBlank()) out += "https://api.deezer.com/search/track?limit=5&q=" + LrclibNames.enc("artist:\"$artist\" track:\"$title\"")
        return out
    }

    /** Resultados de Deezer (álbum o canción) en la misma forma que los de Apple; la portada va en artworkUrl100. */
    private fun parseDeezer(body: String): List<ItunesResult> {
        val arr = JSONObject(body).optJSONArray("data") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val album = o.optJSONObject("album")
            val isTrack = album != null
            ItunesResult(
                artist = o.optJSONObject("artist")?.optString("name")?.ifBlank { null },
                album = if (isTrack) album!!.optString("title").ifBlank { null } else o.optString("title").ifBlank { null },
                track = if (isTrack) o.optString("title").ifBlank { null } else null,
                durationMs = if (isTrack) o.optLong("duration", 0L) * 1000 else 0L,
                artworkUrl100 = (if (isTrack) album!!.optString("cover_xl") else o.optString("cover_xl")).ifBlank { null },
            )
        }
    }

    private fun parse(body: String): List<ItunesResult> {
        val arr = JSONObject(body).optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let {
                ItunesResult(
                    artist = it.optString("artistName").ifBlank { null },
                    album = it.optString("collectionName").ifBlank { null },
                    track = it.optString("trackName").ifBlank { null },
                    durationMs = it.optLong("trackTimeMillis", 0L),
                    artworkUrl100 = it.optString("artworkUrl100").ifBlank { null },
                )
            }
        }
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, b)
        if (b.outWidth <= 0) return null
        val o = BitmapFactory.Options().apply { inSampleSize = ArtworkMath.sampleSize(b.outWidth, b.outHeight, 1600) }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
    }

    private fun open(u: String): HttpURLConnection = (URL(u).openConnection() as HttpURLConnection).apply {
        connectTimeout = 6000; readTimeout = 10000
        setRequestProperty("User-Agent", "Hogar-TV/0.1")
    }

    private fun httpText(u: String): String? = try {
        val c = open(u)
        try { if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() } else null } finally { c.disconnect() }
    } catch (_: Exception) { null }

    private fun httpBytes(u: String): ByteArray? = try {
        val c = open(u)
        try { if (c.responseCode == 200) c.inputStream.use { it.readBytes() } else null } finally { c.disconnect() }
    } catch (_: Exception) { null }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val NEGATIVE_TTL_MS = 24 * 3600_000L
    }
}
