package live.hogar.browser

import org.json.JSONArray
import org.json.JSONObject

data class Site(val url: String, val title: String)
data class Visit(val url: String, val title: String, val at: Long)
data class SavedTab(val url: String, val title: String)

/** Todo lo que el navegador recuerda. */
data class BrowserData(
    val favorites: List<Site> = DefaultFavorites,
    val history: List<Visit> = emptyList(),
    val tabs: List<SavedTab> = emptyList(),
    val active: Int = 0,
    val desktop: Boolean = true,
    val zoom: Int = 100,
    val scrollMode: Boolean = false,
) {
    companion object {
        val DefaultFavorites = listOf(
            Site("https://www.google.com/", "Google"),
            Site("https://www.youtube.com/", "YouTube"),
            Site("https://music.apple.com/", "Apple Music"),
            Site("https://www.apkmirror.com/", "APKMirror"),
            Site("https://es.wikipedia.org/", "Wikipedia"),
        )
    }
}

object Favorites {
    /** Clave para comparar direcciones: sin esquema, sin "www.", sin fragmento ni "/" final. */
    fun key(url: String): String =
        url.substringBefore('#').substringAfter("://", url).removePrefix("www.").trimEnd('/').lowercase()

    fun contains(list: List<Site>, url: String): Boolean = list.any { key(it.url) == key(url) }

    /** Añade al final si no está; si está, lo quita. */
    fun toggle(list: List<Site>, site: Site): List<Site> =
        if (contains(list, site.url)) list.filterNot { key(it.url) == key(site.url) }
        else list + site.copy(title = site.title.ifBlank { UrlInput.hostOfUrl(site.url) })
}

object History {
    const val CAP = 40

    /** La visita más reciente va primero; una dirección repetida sube en vez de duplicarse. */
    fun add(list: List<Visit>, v: Visit, cap: Int = CAP): List<Visit> =
        (listOf(v) + list.filterNot { Favorites.key(it.url) == Favorites.key(v.url) }).take(cap)
}

object DataJson {
    private const val VERSION = 1

    fun encode(d: BrowserData): String {
        val o = JSONObject()
        o.put("v", VERSION)
        o.put("favorites", JSONArray().also { a -> d.favorites.forEach { a.put(JSONObject().put("u", it.url).put("t", it.title)) } })
        o.put("history", JSONArray().also { a -> d.history.forEach { a.put(JSONObject().put("u", it.url).put("t", it.title).put("a", it.at)) } })
        o.put("tabs", JSONArray().also { a -> d.tabs.forEach { a.put(JSONObject().put("u", it.url).put("t", it.title)) } })
        o.put("active", d.active)
        o.put("desktop", d.desktop)
        o.put("zoom", d.zoom)
        o.put("scroll", d.scrollMode)
        return o.toString()
    }

    /** Tolerante: texto vacío o dañado devuelve los valores de fábrica. Sin clave "favorites" = fábrica; con clave vacía = vacía. */
    fun decode(text: String?): BrowserData {
        if (text.isNullOrBlank()) return BrowserData()
        return try {
            val o = JSONObject(text)
            val favs = o.optJSONArray("favorites")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    val e = a.optJSONObject(i) ?: return@mapNotNull null
                    val u = e.optString("u"); if (!isWeb(u)) null else Site(u, e.optString("t"))
                }
            } ?: BrowserData.DefaultFavorites
            val hist = o.optJSONArray("history")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    val e = a.optJSONObject(i) ?: return@mapNotNull null
                    val u = e.optString("u"); if (!isWeb(u)) null else Visit(u, e.optString("t"), e.optLong("a"))
                }
            }.orEmpty().take(History.CAP)
            val tabs = o.optJSONArray("tabs")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    val e = a.optJSONObject(i) ?: return@mapNotNull null
                    val u = e.optString("u"); if (u.isNotEmpty() && !isWeb(u)) null else SavedTab(u, e.optString("t"))
                }
            }.orEmpty().take(TabPolicy.MAX_TABS)
            BrowserData(
                favorites = favs, history = hist, tabs = tabs,
                active = o.optInt("active", 0).coerceIn(0, (tabs.size - 1).coerceAtLeast(0)),
                desktop = o.optBoolean("desktop", true),
                zoom = ZoomSteps.clamp(o.optInt("zoom", 100)),
                scrollMode = o.optBoolean("scroll", false),
            )
        } catch (_: Exception) {
            BrowserData()
        }
    }

    private fun isWeb(u: String) = u.startsWith("http://") || u.startsWith("https://")
}
