package live.hogar.launcher

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Lógica PURA (sin Android) de las tarjetas de Hogar: se prueba en la JVM.

/** Saludo según la hora (0..23). */
object Greeting {
    fun forHour(hour: Int): String = when (hour) {
        in 5..11 -> "Buenos días"
        in 12..19 -> "Buenas tardes"
        else -> "Buenas noches"
    }
}

/** Fecha larga en español, p. ej. "Domingo, 1 de noviembre". */
object SpanishDate {
    private val days = arrayOf("Domingo", "Lunes", "Martes", "Miércoles", "Jueves", "Viernes", "Sábado")
    private val months = arrayOf(
        "enero", "febrero", "marzo", "abril", "mayo", "junio",
        "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre",
    )

    /** [dayOfWeek] como `Calendar.DAY_OF_WEEK` (1 = domingo); [month0] 0..11. */
    fun long(dayOfWeek: Int, day: Int, month0: Int): String =
        "${days[(dayOfWeek - 1).coerceIn(0, 6)]}, $day de ${months[month0.coerceIn(0, 11)]}"
}

/** Tamaños de rasterizado: píxeles reales, jamás ampliar un mapa de bits. */
object RasterSize {
    /** Píxeles que ocupa [dp] a la densidad dada con el zoom máximo de foco. */
    fun px(dp: Float, density: Float, maxFocusScale: Float): Int =
        max(1, ceil(dp * density * maxFocusScale).toInt())

    /**
     * Tamaño de salida para una imagen que se mostrará a [targetW]×[targetH] px. Si la fuente
     * ([srcW]×[srcH]) ya es menor o igual, se conserva (no se amplía); si es mayor, se reduce
     * sin deformarla. Fuente desconocida (vectores) = tamaño destino.
     */
    fun fit(targetW: Int, targetH: Int, srcW: Int?, srcH: Int?): Pair<Int, Int> {
        if (srcW == null || srcH == null || srcW <= 0 || srcH <= 0) return targetW to targetH
        if (srcW <= targetW && srcH <= targetH) return srcW to srcH
        val k = min(targetW.toFloat() / srcW, targetH.toFloat() / srcH)
        return max(1, (srcW * k).roundToInt()) to max(1, (srcH * k).roundToInt())
    }
}

/** Tamaño del nombre en la tarjeta según su largo (sp). */
object Wordmark {
    fun sizeSp(length: Int, base: Float = 26f, min: Float = 15f): Float =
        (base - max(0, length - 8) * 1.1f).coerceIn(min, base)
}

// ---------------------------------------------------------------- color

/** HSV puro (h 0..360, s y v 0..1) para no depender de android.graphics.Color. */
object Hsv {
    fun fromRgb(r: Int, g: Int, b: Int): FloatArray {
        val rf = r / 255f; val gf = g / 255f; val bf = b / 255f
        val mx = max(rf, max(gf, bf)); val mn = min(rf, min(gf, bf))
        val d = mx - mn
        val h = when {
            d == 0f -> 0f
            mx == rf -> 60f * (((gf - bf) / d) % 6f)
            mx == gf -> 60f * ((bf - rf) / d + 2f)
            else -> 60f * ((rf - gf) / d + 4f)
        }.let { if (it < 0f) it + 360f else it }
        return floatArrayOf(h, if (mx == 0f) 0f else d / mx, mx)
    }

    fun toArgb(h: Float, s: Float, v: Float): Int {
        val hh = ((h % 360f) + 360f) % 360f
        val c = v * s
        val x = c * (1f - abs((hh / 60f) % 2f - 1f))
        val m = v - c
        val (r, g, b) = when ((hh / 60f).toInt()) {
            0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
        }
        fun ch(f: Float) = ((f + m).coerceIn(0f, 1f) * 255f).roundToInt()
        return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }

    fun luminance(argb: Int): Float {
        val r = (argb shr 16) and 0xFF; val g = (argb shr 8) and 0xFF; val b = argb and 0xFF
        return (0.299f * r + 0.587f * g + 0.114f * b) / 255f
    }
}

/** Color de marca dominante de un icono, con k-means propio (sin Palette). */
object DominantColor {
    /**
     * [pixels] ARGB. Ignora lo transparente; agrupa en [k] colores (k-means determinista, en RGB)
     * y elige el que más "parece marca": grande, saturado y ni casi negro ni casi blanco.
     * Devuelve ARGB opaco, o null si no hay píxeles útiles.
     */
    fun extract(pixels: IntArray, k: Int = 4, iterations: Int = 8): Int? {
        var n = 0
        for (p in pixels) if ((p ushr 24) >= 128) n++
        if (n == 0) return null
        val step = max(1, n / 1024)
        val rs = FloatArray(min(n, 1100)); val gs = FloatArray(rs.size); val bs = FloatArray(rs.size)
        var m = 0; var seen = 0
        for (p in pixels) {
            if ((p ushr 24) < 128) continue
            if (seen++ % step != 0 || m >= rs.size) continue
            rs[m] = ((p shr 16) and 0xFF).toFloat(); gs[m] = ((p shr 8) and 0xFF).toFloat(); bs[m] = (p and 0xFF).toFloat(); m++
        }
        if (m == 0) return null
        val kk = min(k, m)
        val cr = FloatArray(kk); val cg = FloatArray(kk); val cb = FloatArray(kk)
        for (i in 0 until kk) { val j = i * m / kk; cr[i] = rs[j]; cg[i] = gs[j]; cb[i] = bs[j] }
        val assign = IntArray(m)
        val cnt = IntArray(kk)
        repeat(iterations) {
            for (i in 0 until m) {
                var best = 0; var bd = Float.MAX_VALUE
                for (c in 0 until kk) {
                    val dr = rs[i] - cr[c]; val dg = gs[i] - cg[c]; val db = bs[i] - cb[c]
                    val d = dr * dr + dg * dg + db * db
                    if (d < bd) { bd = d; best = c }
                }
                assign[i] = best
            }
            val sr = FloatArray(kk); val sg = FloatArray(kk); val sb = FloatArray(kk)
            cnt.fill(0)
            for (i in 0 until m) { val c = assign[i]; sr[c] += rs[i]; sg[c] += gs[i]; sb[c] += bs[i]; cnt[c]++ }
            for (c in 0 until kk) if (cnt[c] > 0) { cr[c] = sr[c] / cnt[c]; cg[c] = sg[c] / cnt[c]; cb[c] = sb[c] / cnt[c] }
        }
        var bestScore = -1f; var best = -1
        for (c in 0 until kk) {
            if (cnt[c] == 0) continue
            val hsv = Hsv.fromRgb(cr[c].roundToInt(), cg[c].roundToInt(), cb[c].roundToInt())
            val s = hsv[1]; val v = hsv[2]
            var score = (cnt[c].toFloat() / m) * (0.2f + s) * (0.2f + s)
            if (v < 0.22f) score *= 0.15f
            if (s < 0.12f && v > 0.85f) score *= 0.12f
            if (score > bestScore) { bestScore = score; best = c }
        }
        if (best < 0) return null
        return (0xFF shl 24) or (cr[best].roundToInt() shl 16) or (cg[best].roundToInt() shl 8) or cb[best].roundToInt()
    }
}

// ---------------------------------------------------------------- paletas

enum class WordFamily { Black, Bold, Condensed, Medium, Light }

/** Cómo se escribe el nombre en la tarjeta (nuestro lettering; nunca el logo de la marca). */
data class WordStyle(val family: WordFamily, val upper: Boolean, val trackingSp: Float, val centered: Boolean)

/** Colores de la tarjeta: degradado [top]→[bottom], tinta del texto, color del brillo de fondo. */
data class CardPalette(val top: Int, val bottom: Int, val ink: Int, val glow: Int, val word: WordStyle, val curated: Boolean)

/** Glifos propios y genéricos (engranaje, TV, bluetooth, carpeta, nota): nunca arte de una marca. */
enum class GlyphKind { Gear, Tv, Bluetooth, Folder, Note }

/**
 * Ficha de marca curada: paleta (fondo [CardPalette.top]→[CardPalette.bottom]), glifo propio si es una
 * app del sistema ([glyphAlways]) o de respaldo si la app no ofrece un logo nítido, y el orden de
 * preferencia del logo.
 */
data class BrandSpec(
    val palette: CardPalette,
    val glyph: GlyphKind? = null,
    val glyphAlways: Boolean = false,
    val logoOrder: LogoOrder = LogoOrder.Auto,
)

object BrandPalettes {
    private val Generic = WordStyle(WordFamily.Bold, upper = false, trackingSp = 0f, centered = false)
    private val Centered = WordStyle(WordFamily.Bold, upper = false, trackingSp = 0f, centered = true)

    private class Rule(val pkgs: List<String>, val labels: List<String>, val spec: BrandSpec)

    private fun p(top: Long, bottom: Long, ink: Long, glow: Long, word: WordStyle = Centered) =
        CardPalette(top.toInt(), bottom.toInt(), ink.toInt(), glow.toInt(), word, curated = true)

    private fun rule(pkgs: List<String>, labels: List<String>, pal: CardPalette, glyph: GlyphKind? = null,
                     always: Boolean = false, order: LogoOrder = LogoOrder.Auto) =
        Rule(pkgs, labels, BrandSpec(pal, glyph, always, order))

    private val white = 0xFFFFFFFF
    private val graphite = p(0xFF3A3A40, 0xFF161619, 0xFFFFFFFF, 0xFF8E8E9A)
    private val rules = listOf(
        // Los de paquete más específico van primero (el primero que calza gana).
        rule(listOf("com.google.android.youtube.tvmusic"), listOf("youtube music"),
            p(0xFF000000, 0xFF000000, white, 0xFFFF0033), order = LogoOrder.ForegroundFirst),
        rule(listOf("com.netflix."), listOf("netflix"),
            p(0xFF000000, 0xFF000000, 0xFFE50914, 0xFFE50914, WordStyle(WordFamily.Condensed, true, 1.6f, true)),
            order = LogoOrder.ForegroundFirst),
        rule(listOf("com.google.android.youtube.tvunplugged", "com.google.android.youtube.tv", "com.google.android.youtube"),
            listOf("youtube", "youtube tv"),
            p(0xFFFFFFFF, 0xFFFFFFFF, 0xFF0F0F0F, 0xFFFF1A1A), order = LogoOrder.ForegroundFirst),
        // Prime Video: su icono es azul marino casi negro con el logo claro; la tarjeta hace lo mismo.
        rule(listOf("com.amazon.amazonvideo", "com.amazon.avod"), listOf("prime video", "amazon prime"),
            p(0xFF0F171E, 0xFF0F171E, white, 0xFF00A8E1)),
        rule(listOf("com.disney.disneyplus", "com.disney.starplus"), listOf("disney+", "disney plus"),
            p(0xFF0B1B4F, 0xFF1A3C8F, white, 0xFF2A5BFF)),
        rule(listOf("com.wbd.stream", "com.hbo."), listOf("hbo max", "max", "hbo"),
            p(0xFF5822B4, 0xFF0C0420, white, 0xFF8B3DFF)),
        rule(listOf("com.spotify."), listOf("spotify"),
            p(0xFF000000, 0xFF000000, 0xFF1ED760, 0xFF1DB954)),
        rule(listOf("com.apple.atve"), listOf("apple tv"),
            p(0xFF000000, 0xFF000000, 0xFFE9E9EE, 0xFF9A9AA6)),
        rule(listOf("com.apple.android.music"), listOf("apple music"),
            p(0xFFFA2D48, 0xFFFC3C44, white, 0xFFFA2D48), glyph = GlyphKind.Note, order = LogoOrder.ForegroundFirst),
        rule(listOf("com.plexapp."), listOf("plex"),
            p(0xFF2A2A2D, 0xFF111113, 0xFFE5A00D, 0xFFE5A00D)),
        rule(listOf("com.crunchyroll."), listOf("crunchyroll"),
            p(0xFFFF8A3D, 0xFFC24A00, white, 0xFFF47521)),
        rule(listOf("com.cbs.", "com.paramount"), listOf("paramount+", "paramount plus"),
            p(0xFF1B6BFF, 0xFF08245E, white, 0xFF0064FF)),
        rule(listOf("tv.pluto."), listOf("pluto tv", "pluto"),
            p(0xFF24243A, 0xFF0C0C18, 0xFFFFE600, 0xFFFFD400)),
        rule(listOf("tv.twitch."), listOf("twitch"),
            p(0xFF9146FF, 0xFF9146FF, white, 0xFF9146FF)),
        rule(listOf("com.iqiyi.", "com.qiyi."), listOf("iqiyi"),
            p(0xFF00C853, 0xFF00C853, white, 0xFF00C853)),
        rule(listOf("com.mercadolibre."), listOf("mercado play"),
            p(0xFFFFE600, 0xFFFFE600, 0xFF2D3277, 0xFFFFE600)),
        rule(listOf("com.tiktok."), listOf("tiktok"),
            p(0xFF000000, 0xFF000000, white, 0xFFFE2C55)),
        rule(listOf("com.nvidia.geforcenow"), listOf("geforce now"),
            p(0xFF76B900, 0xFF76B900, 0xFF111111, 0xFF76B900)),
        rule(listOf("com.valvesoftware.steamlink"), listOf("steam link"),
            p(0xFF171A21, 0xFF171A21, white, 0xFF66C0F4)),
        rule(listOf("com.android.vending"), listOf("google play store", "play store"),
            p(0xFFFFFFFF, 0xFFFFFFFF, 0xFF0F0F0F, 0xFF34A853)),
        rule(listOf("com.movistar.", "es.plus.yomvi", "com.telefonica."), listOf("movistar"),
            p(0xFF1AA7FF, 0xFF0A4E94, white, 0xFF019DF4)),
        rule(listOf("com.directv.", "com.dtvla", "com.att.tv"), listOf("directv", "direc tv"),
            p(0xFF11263F, 0xFF050B14, white, 0xFF1E88E5)),
        rule(listOf("com.starplus", "com.hotstar"), listOf("star+", "star plus"),
            p(0xFFE8408C, 0xFF5B0E3A, white, 0xFFE91E78)),
        // Apps del sistema: glifo propio sobre grafito, al estilo tvOS.
        rule(listOf("com.android.tv.settings", "com.android.settings"), listOf("ajustes", "configuración", "configuracion", "settings"),
            graphite, GlyphKind.Gear, always = true),
        rule(listOf("com.smartdevice.livetv"), listOf("tv en vivo", "live tv", "televisión en vivo"),
            graphite, GlyphKind.Tv, always = true),
        rule(listOf("com.smartdevice.duplebluetooth"), listOf("bluetooth"),
            graphite, GlyphKind.Bluetooth, always = true),
        rule(listOf("com.tianci.localmedia"), listOf("multimedia", "archivos", "local media"),
            graphite, GlyphKind.Folder, always = true),
    )

    /** Ficha curada si el paquete o el nombre es de una marca conocida. */
    fun spec(pkg: String, label: String): BrandSpec? {
        val l = label.trim().lowercase()
        for (r in rules) if (r.pkgs.any { pkg.startsWith(it) }) return r.spec
        for (r in rules) if (r.labels.any { l == it }) return r.spec
        return null
    }

    /** Paleta curada si el paquete o el nombre es de una marca conocida. */
    fun match(pkg: String, label: String): CardPalette? = spec(pkg, label)?.palette

    /** Paleta con el color liso [bg] como fondo (capa de fondo del icono adaptable): tonos cercanos para dar volumen. */
    fun fromBackground(bg: Int, pkg: String): CardPalette {
        val r = (bg shr 16) and 0xFF; val g = (bg shr 8) and 0xFF; val b = bg and 0xFF
        val (h, s, v) = Hsv.fromRgb(r, g, b).let { Triple(it[0], it[1], it[2]) }
        val base = derive(bg or (0xFF shl 24), pkg)
        val top = if (s < 0.08f) bg or (0xFF shl 24) else Hsv.toArgb(h, s, min(1f, v * 1.04f))
        val bottom = if (s < 0.08f) bg or (0xFF shl 24) else Hsv.toArgb(h, min(1f, s * 1.02f), v * 0.82f)
        val ink = if (Hsv.luminance(bg) > 0.66f) 0xFF111116.toInt() else 0xFFFFFFFF.toInt()
        return CardPalette(top, bottom, ink, base.glow, Generic, curated = false)
    }

    /** Paleta con [bg] liso (opaco) tal cual, para un banner con fondo propio. */
    fun solid(bg: Int, base: CardPalette): CardPalette {
        val c = bg or (0xFF shl 24)
        val ink = if (Hsv.luminance(c) > 0.66f) 0xFF111116.toInt() else 0xFFFFFFFF.toInt()
        return base.copy(top = c, bottom = c, ink = ink)
    }

    /** Paleta derivada del color dominante [accent] del icono (o neutra si no tiene color). */
    fun derive(accent: Int?, pkg: String): CardPalette {
        val base = accent ?: neutralFor(pkg)
        val r = (base shr 16) and 0xFF; val g = (base shr 8) and 0xFF; val b = base and 0xFF
        val (h, s, v) = Hsv.fromRgb(r, g, b).let { Triple(it[0], it[1], it[2]) }
        val neutral = s < 0.14f
        val top = if (neutral) Hsv.toArgb(h, 0.10f, 0.30f) else Hsv.toArgb(h, min(1f, s * 0.95f), v.coerceIn(0.55f, 0.92f))
        val bottom = if (neutral) Hsv.toArgb(h, 0.12f, 0.12f) else Hsv.toArgb(h, min(1f, s * 1.05f), (v * 0.42f).coerceIn(0.14f, 0.40f))
        val glow = if (neutral) Hsv.toArgb(h, 0.35f, 0.70f) else Hsv.toArgb(h, max(s, 0.65f), max(v, 0.92f))
        val ink = if (Hsv.luminance(top) > 0.66f) 0xFF111116.toInt() else 0xFFFFFFFF.toInt()
        return CardPalette(top, bottom, ink, glow, Generic, curated = false)
    }

    /** Sin icono utilizable: un tono estable por paquete (no al azar entre arranques). */
    private fun neutralFor(pkg: String): Int = Hsv.toArgb((abs(pkg.hashCode()) % 360).toFloat(), 0.12f, 0.45f)
}
