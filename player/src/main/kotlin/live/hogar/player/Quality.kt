package live.hogar.player

/** Calidad con la que sale el sonido hacia el altavoz. */
sealed interface Quality {
    data object None : Quality
    /** 16 bits/44,1 kHz estéreo entregado sin tocar. */
    data object BitPerfect : Quality
    data class Converted(val fromBits: Int, val fromRate: Int, val fromChannels: Int) : Quality
    data object TvSound : Quality
}

object QualityText {
    /** 44100 -> "44,1 kHz"; 96000 -> "96 kHz"; 22050 -> "22,05 kHz". */
    fun khz(rate: Int): String {
        val whole = rate / 1000
        val frac = (rate % 1000).toString().padStart(3, '0').trimEnd('0')
        return if (frac.isEmpty()) "$whole kHz" else "$whole,$frac kHz"
    }

    fun badge(q: Quality): String? = when (q) {
        Quality.None -> null
        Quality.BitPerfect -> "Sin pérdida · 16 bits/44,1 kHz · bit a bit"
        is Quality.Converted -> {
            val ch = when (q.fromChannels) {
                1 -> " mono"
                2 -> ""
                else -> " ${q.fromChannels} canales"
            }
            "Convertido de ${q.fromBits} bits/${khz(q.fromRate)}$ch a 16 bits/44,1 kHz (máximo que acepta el HomePod)"
        }
        Quality.TvSound -> "Sonido del TV · re-muestreado"
    }

    /** Retraso en segundos redondeado hacia arriba al medio segundo más cercano, para mostrar "≈ 2 s". */
    fun latencySeconds(ms: Int): String {
        val tenths = ((ms + 250) / 500) * 5   // décimas de segundo, en pasos de 0,5 s
        val s = tenths / 10
        return if (tenths % 10 == 0) "$s" else "$s,5"
    }
}
