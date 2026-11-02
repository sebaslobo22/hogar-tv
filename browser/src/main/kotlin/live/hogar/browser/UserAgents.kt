package live.hogar.browser

/** Cadenas User-Agent (sin Android). La versión de Chrome sale del WebView instalado, para que coincida con su motor. */
object UserAgents {
    private val chromeRe = Regex("Chrome/(\\d+)")
    private const val FALLBACK_MAJOR = "120"

    fun chromeMajor(default: String): String = chromeRe.find(default)?.groupValues?.get(1) ?: FALLBACK_MAJOR

    /** Chrome de escritorio (Linux): los sitios entregan el diseño completo. */
    fun desktop(default: String): String =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/${chromeMajor(default)}.0.0.0 Safari/537.36"

    /** Chrome móvil de Android. */
    fun mobile(default: String): String =
        "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/${chromeMajor(default)}.0.0.0 Mobile Safari/537.36"
}

/** Tamaños de texto del navegador: 100–175 %, de 25 en 25. */
object ZoomSteps {
    val steps = listOf(100, 125, 150, 175)
    fun clamp(v: Int): Int = steps.minByOrNull { kotlin.math.abs(it - v) } ?: 100
    fun more(v: Int): Int = steps.firstOrNull { it > v } ?: steps.last()
    fun less(v: Int): Int = steps.lastOrNull { it < v } ?: steps.first()
}
