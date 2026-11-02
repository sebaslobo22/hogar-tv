package live.hogar.receiver

/** Metadatos de la canción que llegan por SET_PARAMETER (DMAP). */
internal data class DmapMeta(val title: String? = null, val artist: String? = null, val album: String? = null, val durationMs: Long = 0L)

/** Progreso `progress: inicio/actual/fin` en muestras RTP a 44,1 kHz. */
internal data class AirPlayProgress(val positionMs: Long, val durationMs: Long)

/** Análisis puro (probado en la JVM) de lo que manda el iPhone: DMAP y progreso. */
internal object AirPlayMeta {
    private const val RATE = 44_100L
    private val CONTAINERS = setOf("mlit", "mcon", "msrv", "mdcl", "mccr", "mlcl", "mshl", "mdst", "abro", "abal", "abar", "apso", "aply", "adbs", "casp", "cmpa")

    /** `application/x-dmap-tagged`: etiquetas de 4 letras + longitud de 4 bytes (big endian) + datos. */
    fun parseDmap(b: ByteArray): DmapMeta {
        var title: String? = null; var artist: String? = null; var album: String? = null; var dur = 0L
        fun walk(from: Int, to: Int) {
            var i = from
            while (i + 8 <= to) {
                val tag = String(b, i, 4, Charsets.ISO_8859_1)
                val len = ((b[i + 4].toLong() and 0xFF) shl 24 or ((b[i + 5].toLong() and 0xFF) shl 16) or
                    ((b[i + 6].toLong() and 0xFF) shl 8) or (b[i + 7].toLong() and 0xFF))
                val s = i + 8
                if (len < 0 || s + len > to) return
                val e = (s + len).toInt()
                when (tag) {
                    "minm" -> title = String(b, s, e - s, Charsets.UTF_8).ifBlank { null }
                    "asar" -> artist = String(b, s, e - s, Charsets.UTF_8).ifBlank { null }
                    "asal" -> album = String(b, s, e - s, Charsets.UTF_8).ifBlank { null }
                    "astm" -> { var v = 0L; for (k in s until e) v = (v shl 8) or (b[k].toLong() and 0xFF); dur = v }
                    else -> if (tag in CONTAINERS) walk(s, e)
                }
                i = e
            }
        }
        walk(0, b.size)
        return DmapMeta(title, artist, album, dur)
    }

    /** "progress: 1000/45100/9000000" → posición y duración en ms; null si no es ese formato. */
    fun parseProgress(text: String): AirPlayProgress? {
        val line = text.lineSequence().firstOrNull { it.trimStart().startsWith("progress:") } ?: return null
        val parts = line.substringAfter(':').trim().split('/')
        if (parts.size != 3) return null
        val n = parts.map { it.trim().toLongOrNull() ?: return null }
        val pos = (n[1] - n[0]) * 1000 / RATE
        val dur = (n[2] - n[0]) * 1000 / RATE
        if (pos < 0 || dur < 0) return null
        return AirPlayProgress(pos, dur)
    }
}
