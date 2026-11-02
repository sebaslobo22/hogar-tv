package live.hogar.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Iconos de los sitios de la página de inicio: se busca el más grande que declara la página
 * (apple-touch-icon / icon con sizes), se guarda en disco y no se vuelve a pedir. Si el sitio
 * no tiene ninguno decente se recuerda el fallo unos días y la tarjeta usa una letra.
 */
class IconRepository(ctx: Context) {
    private val dir = File(ctx.cacheDir, "navegador-iconos").also { it.mkdirs() }
    private val mem = LruCache<String, ImageBitmap>(24)

    suspend fun load(siteUrl: String): ImageBitmap? = withContext(Dispatchers.IO) {
        val host = UrlInput.hostOfUrl(siteUrl)
        if (host.isEmpty()) return@withContext null
        val key = host.replace(Regex("[^A-Za-z0-9.-]"), "_")
        mem.get(key)?.let { return@withContext it }
        val png = File(dir, "$key.png")
        if (png.exists()) {
            decode(png.readBytes())?.let { b -> return@withContext b.asImageBitmap().also { mem.put(key, it) } }
        }
        val none = File(dir, "$key.none")
        if (none.exists() && System.currentTimeMillis() - none.lastModified() < RETRY_MS) return@withContext null
        val bmp = runCatching { fetch(siteUrl) }.getOrNull()
        if (bmp == null) {
            runCatching { none.writeText("") }
            return@withContext null
        }
        runCatching { png.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        none.delete()
        bmp.asImageBitmap().also { mem.put(key, it) }
    }

    private fun fetch(siteUrl: String): Bitmap? {
        val origin = URL(siteUrl).let { "${it.protocol}://${it.host}/" }
        val html = runCatching { String(download(origin, HTML_MAX) ?: ByteArray(0), Charsets.UTF_8) }.getOrDefault("")
        var best: Bitmap? = null
        for (candidate in IconLinks.candidates(html, origin).take(6)) {
            val b = download(candidate, ICON_MAX)?.let { decode(it) } ?: continue
            if (best == null || b.width > best.width) best = b
            if (b.width >= GOOD_PX) break
        }
        return best
    }

    private fun decode(bytes: ByteArray): Bitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.takeIf { it.width > 1 }

    private fun download(url: String, max: Int): ByteArray? {
        if (!url.startsWith("https://")) return null
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 6000
            c.readTimeout = 6000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", UserAgents.desktop(""))
            if (c.responseCode !in 200..299) return null
            return c.inputStream.use { readLimited(it, max) }
        } finally {
            c.disconnect()
        }
    }

    private fun readLimited(s: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < max) {
            val n = s.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private companion object {
        const val RETRY_MS = 3L * 24 * 3600 * 1000
        const val HTML_MAX = 160 * 1024
        const val ICON_MAX = 1024 * 1024
        const val GOOD_PX = 120
    }
}
