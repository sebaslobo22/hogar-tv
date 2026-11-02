package live.hogar.browser

import java.net.URLEncoder

/** Qué hacer con lo que se escribe en la barra de direcciones: abrir la dirección o buscarla. Sin Android. */
object UrlInput {
    const val SEARCH = "https://www.google.com/search?q="

    private val label = "[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?"
    private val hostRe = Regex("^$label(?:\\.$label)*$")
    private val ipv4 = Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$")
    private val tldRe = Regex("^(?:[A-Za-z]{2,}|xn--[A-Za-z0-9-]+)$")
    private val schemeRe = Regex("^[a-z][a-z0-9+.-]*:")
    private val portRest = Regex("^\\d+(?:[/?#].*)?$")

    /** Dirección lista para cargar, o "" si no se escribió nada. Nunca devuelve esquemas que no sean http(s). */
    fun resolve(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return ""
        if (s.any { it.isWhitespace() }) return search(s)
        val lower = s.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return if (isWebHost(hostOf(s.substringAfter("://")))) s else search(s)
        }
        if (schemeRe.containsMatchIn(lower) && !portRest.matches(lower.substringAfter(':'))) {
            // javascript:, data:, file:, content:, intent:, ftp:... nunca se abren: se buscan como texto.
            return search(s)
        }
        val host = hostOf(s)
        if (!isWebHost(host)) return search(s)
        val local = host == "localhost" || ipv4.matches(host) || host.endsWith(".local")
        return (if (local) "http://" else "https://") + s
    }

    fun search(q: String): String = SEARCH + URLEncoder.encode(q, "UTF-8")

    /** Host de "usuario@host:puerto/ruta?x#y" ("" si el puerto no es válido). */
    private fun hostOf(rest: String): String {
        var auth = rest.takeWhile { it != '/' && it != '?' && it != '#' }
        if (auth.contains('@')) return "" // usuario@ en la dirección: típico de engaños, se busca como texto
        val colon = auth.lastIndexOf(':')
        if (colon >= 0) {
            val port = auth.substring(colon + 1)
            if (port.isEmpty() || port.length > 5 || !port.all { it.isDigit() }) return ""
            auth = auth.substring(0, colon)
        }
        return auth
    }

    private fun isWebHost(h: String): Boolean {
        if (h.isEmpty()) return false
        if (h.equals("localhost", true)) return true
        if (ipv4.matches(h)) return h.split('.').all { it.toInt() in 0..255 }
        if (!hostRe.matches(h) || !h.contains('.')) return false
        return tldRe.matches(h.substringAfterLast('.'))
    }

    /** Texto corto para la barra: sin esquema ni "www.". */
    fun display(url: String): String {
        if (url.isEmpty()) return ""
        var s = url.substringAfter("://", url)
        if (s.startsWith("www.")) s = s.removePrefix("www.")
        return s.trimEnd('/')
    }

    fun hostOfUrl(url: String): String =
        runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("").removePrefix("www.")
}
