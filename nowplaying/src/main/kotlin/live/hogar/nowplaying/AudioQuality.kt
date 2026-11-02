package live.hogar.nowplaying

/** Insignias que se pueden mostrar. Solo se encienden con evidencia explícita en la sesión. */
data class AudioBadges(val lossless: Boolean = false, val atmos: Boolean = false) {
    val any: Boolean get() = lossless || atmos

    companion object { val None = AudioBadges() }
}

enum class QualityVerdict { Lossless, Lossy, Unknown }

/**
 * Calidad de audio de la sesión, SIN adivinar: lo único que cuenta es lo que la propia sesión
 * declare en claves personalizadas de los metadatos o en los extras del estado de reproducción.
 * Los campos de texto estándar (título, artista, álbum...) se ignoran: una canción llamada
 * "Palace" no es ALAC. Sin evidencia → [QualityVerdict.Unknown] y no hay insignia.
 * La misma regla vale para el reproductor de Hogar (paquete `live.hogar...`): si su sesión no lo
 * declara en los metadatos, tampoco se muestra; por eso el paquete no entra en la decisión.
 */
object AudioQuality {
    private const val STANDARD_PREFIX = "android.media.metadata."

    private val LOSSLESS = Regex("""(?<![a-z0-9])(lossless|alac|flac|hi-?res(?:olution)?|24[- ]?bits?)(?![a-z0-9])""")
    private val LOSSY = Regex("""(?<![a-z0-9])(lossy|aac|he-?aac|mp3|mpeg|vorbis|opus|mp4a)(?![a-z0-9])""")
    private val ATMOS = Regex("""(?<![a-z0-9])(dolby[ _-]?atmos|atmos)(?![a-z0-9])""")
    private val TRUE = setOf("true", "1", "yes", "on")
    private val FALSE = setOf("false", "0", "no", "off")

    private fun entries(metadata: Map<String, String>, extras: Map<String, String>): List<Pair<String, String>> =
        metadata.filterKeys { !it.startsWith(STANDARD_PREFIX) }.map { it.key.lowercase() to it.value.lowercase().trim() } +
            extras.map { it.key.lowercase() to it.value.lowercase().trim() }

    fun verdict(metadata: Map<String, String>, extras: Map<String, String>): QualityVerdict {
        var yes = false
        var no = false
        for ((k, v) in entries(metadata, extras)) {
            if ("lossless" in k) {
                when (v) {
                    in TRUE -> yes = true
                    in FALSE -> no = true
                    else -> if (LOSSLESS.containsMatchIn(v)) yes = true else if (LOSSY.containsMatchIn(v)) no = true
                }
                continue
            }
            val lossless = LOSSLESS.containsMatchIn(v)
            val lossy = LOSSY.containsMatchIn(v)
            if (lossless && !lossy) yes = true
            else if (lossy && !lossless) no = true
        }
        return when {
            no -> QualityVerdict.Lossy
            yes -> QualityVerdict.Lossless
            else -> QualityVerdict.Unknown
        }
    }

    fun atmos(metadata: Map<String, String>, extras: Map<String, String>): Boolean {
        var yes = false
        for ((k, v) in entries(metadata, extras)) {
            if ("atmos" in k) {
                if (v in FALSE) return false
                if (v in TRUE || ATMOS.containsMatchIn(v)) yes = true
            } else if (ATMOS.containsMatchIn(v)) yes = true
        }
        return yes
    }

    fun badges(metadata: Map<String, String>, extras: Map<String, String>): AudioBadges {
        return AudioBadges(
            lossless = verdict(metadata, extras) == QualityVerdict.Lossless,
            atmos = atmos(metadata, extras),
        )
    }
}
