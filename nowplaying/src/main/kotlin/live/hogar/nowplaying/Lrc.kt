package live.hogar.nowplaying

/** Palabra con su instante de inicio (LRC "enhanced"). [text] conserva el espacio que la separa de la siguiente. */
data class LyricWord(val startMs: Long, val text: String)

/**
 * Una línea de letra. [timeMs] = -1 en letras sin sincronizar. [words] viene vacía salvo en LRC
 * "enhanced" (marcas `<mm:ss.xx>` dentro de la línea); entonces `words.joinToString("") { it.text } == text`.
 * [endMs] = fin explícito (marca final de la línea) o -1.
 */
data class LyricLine(val timeMs: Long, val text: String, val words: List<LyricWord> = emptyList(), val endMs: Long = -1L)

/** Letra de una canción. Si [synced], las líneas van ordenadas por tiempo. */
class Lyrics(val lines: List<LyricLine>, val synced: Boolean) {
    val isEmpty: Boolean get() = lines.isEmpty()

    /**
     * Índice de la línea vigente en [positionMs] (+ [leadMs] de adelanto), o -1 si todavía no
     * empieza la primera. Búsqueda binaria: se llama pocas veces por segundo.
     */
    fun indexAt(positionMs: Long, leadMs: Long = 0L): Int {
        if (!synced || lines.isEmpty()) return -1
        val t = positionMs + leadMs
        var lo = 0
        var hi = lines.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= t) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }
}

/**
 * Lector de formato LRC: `[mm:ss.xx]texto`, varias marcas por línea (`[00:10.00][01:20.00]coro`),
 * `[offset:+ms]` (positivo = la letra aparece antes), marcas de palabra `<mm:ss.xx>` (LRC enhanced, se guardan en [LyricLine.words]) y
 * etiquetas de metadatos (`[ar:..]`, `[ti:..]`) descartadas.
 */
object LrcParser {
    private const val BOM = '\uFEFF'
    private val STAMP = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val OFFSET = Regex("""^\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)
    private val WORD_TAG = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")

    fun parse(raw: String): Lyrics {
        var offset = 0L
        val timed = ArrayList<LyricLine>()
        val plain = ArrayList<String>()
        for (rawLine in raw.lineSequence()) {
            val line = rawLine.trim().trimStart(BOM)
            if (line.isEmpty()) continue
            val off = OFFSET.find(line)
            if (off != null) { offset = off.groupValues[1].toLongOrNull() ?: 0L; continue }
            val times = ArrayList<Long>()
            var pos = 0
            while (true) {
                val m = STAMP.matchAt(line, pos) ?: break
                times += toMs(m.groupValues[1], m.groupValues[2], m.groupValues[3])
                pos = m.range.last + 1
            }
            val rest = line.substring(pos)
            if (times.isEmpty()) {
                // Etiqueta de metadatos o texto suelto.
                val text = WORD_TAG.replace(rest, "").trim()
                if (!(line.startsWith("[") && line.contains(':') && line.endsWith("]"))) plain += text
                continue
            }
            if (times.size == 1 && WORD_TAG.containsMatchIn(rest)) {
                val (words, end) = parseWords(rest, times[0])
                val text = words.joinToString("") { it.text }
                timed += LyricLine(times[0], text, words, end)
            } else {
                val text = WORD_TAG.replace(rest, "").trim()
                for (t in times) timed += LyricLine(t, text)
            }
        }
        if (timed.isNotEmpty()) {
            // El desfase vale para todo el archivo, esté donde esté la etiqueta.
            fun sh(t: Long) = (t - offset).coerceAtLeast(0L)
            return Lyrics(
                timed.map { l -> LyricLine(sh(l.timeMs), l.text, l.words.map { LyricWord(sh(it.startMs), it.text) }, if (l.endMs >= 0) sh(l.endMs) else -1L) }
                    .sortedBy { it.timeMs },
                synced = true,
            )
        }
        return Lyrics(plain.map { LyricLine(-1L, it) }, synced = false)
    }

    /** Palabras de una línea enhanced y el fin explícito (marca al final de la línea) o -1. */
    private fun parseWords(rest: String, lineStart: Long): Pair<List<LyricWord>, Long> {
        val words = ArrayList<LyricWord>()
        var curStart = lineStart
        var last = 0
        for (m in WORD_TAG.findAll(rest)) {
            addSegment(words, curStart, rest.substring(last, m.range.first))
            curStart = toMs(m.groupValues[1], m.groupValues[2], m.groupValues[3])
            last = m.range.last + 1
        }
        val tail = rest.substring(last)
        var end = -1L
        if (tail.isBlank()) { if (last > 0) end = curStart; addSegment(words, curStart, tail) } else addSegment(words, curStart, tail)
        if (words.isNotEmpty()) {
            words[0] = words[0].copy(text = words[0].text.trimStart())
            words[words.size - 1] = words[words.size - 1].copy(text = words[words.size - 1].text.trimEnd())
        }
        return words to end
    }

    /** Un segmento en blanco se pega a la palabra anterior (el espacio), no es palabra. */
    private fun addSegment(words: MutableList<LyricWord>, start: Long, seg: String) {
        if (seg.isEmpty()) return
        if (seg.isBlank()) {
            if (words.isNotEmpty()) words[words.size - 1] = words.last().copy(text = words.last().text + seg)
            return
        }
        words += LyricWord(start, seg)
    }

    private fun toMs(min: String, sec: String, frac: String): Long {
        val f = when (frac.length) {
            0 -> 0L
            1 -> frac.toLong() * 100
            2 -> frac.toLong() * 10
            else -> frac.take(3).toLong()
        }
        return min.toLong() * 60_000 + sec.toLong() * 1000 + f
    }
}

/** Un elemento de la lista de letra: una línea o un hueco instrumental (tres puntos). */
class LyricItem(val startMs: Long, val endMs: Long, val text: String, val words: List<LyricWord>, val gap: Boolean)

/** Convierte [Lyrics] sincronizada en la lista que se dibuja, con huecos instrumentales detectados. */
object LyricTimeline {
    const val MIN_GAP_MS = 5_000L

    /** Fin estimado de una línea: marca explícita, o inicio + lo que tarda en cantarse (tope 7 s). */
    fun lineEnd(l: LyricLine, nextStartMs: Long): Long {
        val est = when {
            l.endMs > l.timeMs -> l.endMs
            l.words.isNotEmpty() -> l.words.last().let { it.startMs + (it.text.length * 90L).coerceAtLeast(600L) }
            else -> l.timeMs + (800L + l.text.length * 75L).coerceAtMost(7_000L)
        }
        return est.coerceAtMost(nextStartMs).coerceAtLeast(l.timeMs)
    }

    fun build(lyrics: Lyrics, minGapMs: Long = MIN_GAP_MS): List<LyricItem> {
        val src = lyrics.lines
        val out = ArrayList<LyricItem>()
        if (!lyrics.synced || src.isEmpty()) return out
        if (src[0].text.isNotBlank() && src[0].timeMs >= minGapMs) out += gap(0L, src[0].timeMs)
        for (i in src.indices) {
            val l = src[i]
            val next = src.getOrNull(i + 1)
            if (l.text.isBlank()) {
                if (next != null && next.timeMs - l.timeMs >= minGapMs) out += gap(l.timeMs, next.timeMs)
                continue
            }
            val end = lineEnd(l, next?.timeMs ?: Long.MAX_VALUE)
            out += LyricItem(l.timeMs, end, l.text, l.words, gap = false)
            if (next != null && next.text.isNotBlank() && next.timeMs - end >= minGapMs) out += gap(end, next.timeMs)
        }
        // Un hueco marcado con línea vacía justo tras otro: se funden.
        val merged = ArrayList<LyricItem>(out.size)
        for (it in out) {
            val prev = merged.lastOrNull()
            if (it.gap && prev != null && prev.gap) merged[merged.size - 1] = gap(prev.startMs, maxOf(prev.endMs, it.endMs))
            else merged += it
        }
        return merged
    }

    private fun gap(a: Long, b: Long) = LyricItem(a, b, "", emptyList(), gap = true)

    /** Índice del elemento vigente en [tMs] (último con inicio <= tMs), o -1. */
    fun indexAt(items: List<LyricItem>, tMs: Long): Int {
        var lo = 0; var hi = items.size - 1; var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (items[mid].startMs <= tMs) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }
}

/** Avance del barrido palabra por palabra. */
object WordSweep {
    /**
     * Posición fraccionaria en palabras: parte entera = palabra activa, fracción = avance dentro de
     * ella. 0 antes de la primera; words.size cuando terminó la línea. Sin asignaciones.
     */
    fun position(words: List<LyricWord>, lineEndMs: Long, tMs: Long): Float {
        val n = words.size
        if (n == 0 || tMs < words[0].startMs) return 0f
        if (tMs >= lineEndMs.coerceAtLeast(words[n - 1].startMs + 1)) return n.toFloat()
        var lo = 0; var hi = n - 1; var k = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (words[mid].startMs <= tMs) { k = mid; lo = mid + 1 } else hi = mid - 1
        }
        val s = words[k].startMs
        val e = if (k + 1 < n) words[k + 1].startMs else lineEndMs
        val f = if (e > s) ((tMs - s).toFloat() / (e - s)).coerceIn(0f, 1f) else 1f
        return k + f
    }
}
