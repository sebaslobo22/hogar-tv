package live.hogar.browser

import java.net.URI

/** Elige los iconos de un sitio a partir de su HTML (sin Android): primero el más grande, luego los de reserva. */
object IconLinks {
    private data class Cand(val href: String, val size: Int, val apple: Boolean)

    private val linkRe = Regex("<link\\b[^>]*>", setOf(RegexOption.IGNORE_CASE))
    private val attrRe = Regex("([a-zA-Z][a-zA-Z0-9_-]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')")

    /** Direcciones absolutas de iconos candidatos, mejores primero (sin SVG: no se pueden decodificar). */
    fun candidates(html: String, baseUrl: String): List<String> {
        val found = ArrayList<Cand>()
        for (m in linkRe.findAll(html)) {
            val attrs = HashMap<String, String>()
            for (a in attrRe.findAll(m.value)) {
                attrs[a.groupValues[1].lowercase()] = a.groupValues[2].ifEmpty { a.groupValues[3] }
            }
            val rel = attrs["rel"]?.lowercase()?.split(Regex("\\s+")).orEmpty()
            if (rel.none { it == "icon" || it == "apple-touch-icon" || it == "apple-touch-icon-precomposed" }) continue
            val href = attrs["href"]?.trim().orEmpty()
            if (href.isEmpty() || href.startsWith("data:")) continue
            val abs = resolve(baseUrl, href) ?: continue
            val type = attrs["type"]?.lowercase().orEmpty()
            if (abs.substringBefore('?').lowercase().endsWith(".svg") || type.contains("svg")) continue
            val apple = rel.any { it.startsWith("apple-touch-icon") }
            found += Cand(abs, sizeOf(attrs["sizes"]) ?: if (apple) 180 else 32, apple)
        }
        val ordered = found.sortedWith(compareByDescending<Cand> { it.size }.thenByDescending { it.apple }).map { it.href }
        val fallbacks = listOfNotNull(resolve(baseUrl, "/apple-touch-icon.png"), resolve(baseUrl, "/favicon.ico"))
        return (ordered + fallbacks).distinct()
    }

    /** Lado mayor de "sizes" ("180x180", "16x16 32x32"); null si no hay o es "any". */
    fun sizeOf(sizes: String?): Int? =
        sizes?.lowercase()?.split(Regex("\\s+"))?.mapNotNull { it.substringBefore('x').toIntOrNull() }?.maxOrNull()

    private fun resolve(base: String, href: String): String? = try {
        val r = URI(base).resolve(href.replace(" ", "%20"))
        if (r.scheme == "http" || r.scheme == "https") r.toString() else null
    } catch (_: Exception) {
        null
    }
}
