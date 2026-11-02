package live.hogar.player

/** Formatos PCM que puede entregar MediaCodec (mismos valores que android.media.AudioFormat.ENCODING_*). */
object PcmEncoding {
    const val PCM_8 = 3
    const val PCM_16 = 2
    const val FLOAT = 4
    const val PCM_24 = 21
    const val PCM_32 = 22

    fun bytesPerSample(encoding: Int): Int = when (encoding) {
        PCM_8 -> 1
        PCM_16 -> 2
        PCM_24 -> 3
        PCM_32, FLOAT -> 4
        else -> throw IllegalArgumentException("Codificación PCM no soportada: $encoding")
    }

    fun bits(encoding: Int): Int = when (encoding) {
        PCM_8 -> 8
        PCM_16 -> 16
        PCM_24 -> 24
        PCM_32, FLOAT -> 32
        else -> throw IllegalArgumentException("Codificación PCM no soportada: $encoding")
    }

    /** Convierte a [-1, 1). Devuelve cuántas muestras escribió en [dst]. */
    fun toDouble(src: ByteArray, length: Int, encoding: Int, dst: DoubleArray): Int {
        val bps = bytesPerSample(encoding)
        val n = length / bps
        require(dst.size >= n) { "dst demasiado pequeño" }
        var p = 0
        for (i in 0 until n) {
            dst[i] = when (encoding) {
                PCM_8 -> ((src[p].toInt() and 0xFF) - 128) / 128.0
                PCM_16 -> ((src[p].toInt() and 0xFF) or (src[p + 1].toInt() shl 8)).toShort() / 32768.0
                PCM_24 -> {
                    val v = (src[p].toInt() and 0xFF) or ((src[p + 1].toInt() and 0xFF) shl 8) or (src[p + 2].toInt() shl 16)
                    v / 8388608.0
                }
                PCM_32 -> {
                    val v = (src[p].toInt() and 0xFF) or ((src[p + 1].toInt() and 0xFF) shl 8) or
                        ((src[p + 2].toInt() and 0xFF) shl 16) or (src[p + 3].toInt() shl 24)
                    v / 2147483648.0
                }
                else -> {
                    val v = (src[p].toInt() and 0xFF) or ((src[p + 1].toInt() and 0xFF) shl 8) or
                        ((src[p + 2].toInt() and 0xFF) shl 16) or (src[p + 3].toInt() shl 24)
                    Float.fromBits(v).toDouble()
                }
            }
            p += bps
        }
        return n
    }
}

/** Ruido de dither triangular (TPDF) en unidades de LSB, rango (-1, 1). Determinista por semilla. */
class TpdfDither(seed: Long = 0x2545F4914F6CDD1DL) {
    private var s = if (seed == 0L) 1L else seed

    private fun uniform(): Double {
        s = s xor (s shl 13)
        s = s xor (s ushr 7)
        s = s xor (s shl 17)
        return (s ushr 11).toDouble() * (1.0 / 9007199254740992.0)
    }

    fun next(): Double = uniform() - uniform()
}

object Quantizer {
    /** [x] en [-1, 1) a entero de 16 bits, con dither opcional y recorte. */
    fun toInt16(x: Double, dither: TpdfDither?): Int {
        val v = x * 32768.0 + (dither?.next() ?: 0.0)
        val r = Math.floor(v + 0.5)
        return when {
            r > 32767.0 -> 32767
            r < -32768.0 -> -32768
            else -> r.toInt()
        }
    }
}

/** Lectura de la profundidad de bits desde las cabeceras de los formatos. */
object AudioFormats {
    /** STREAMINFO de FLAC (con o sin la marca "fLaC" y la cabecera de bloque). Null si no se entiende. */
    fun parseFlacBitDepth(csd: ByteArray): Int? {
        val start = when {
            csd.size >= 8 + 14 && csd[0] == 'f'.code.toByte() && csd[1] == 'L'.code.toByte() &&
                csd[2] == 'a'.code.toByte() && csd[3] == 'C'.code.toByte() -> 8
            csd.size >= 34 -> 0
            else -> return null
        }
        if (csd.size < start + 14) return null
        val b12 = csd[start + 12].toInt() and 0xFF
        val b13 = csd[start + 13].toInt() and 0xFF
        val bits = (((b12 and 1) shl 4) or (b13 ushr 4)) + 1
        return if (bits in 4..32) bits else null
    }

    /** Cookie ALAC (24 bytes pelados o 36 con el átomo "alac"). */
    fun parseAlacBitDepth(csd: ByteArray): Int? {
        val bits = when {
            csd.size >= 36 -> csd[17].toInt() and 0xFF
            csd.size >= 24 -> csd[5].toInt() and 0xFF
            else -> return null
        }
        return if (bits in intArrayOf(16, 20, 24, 32)) bits else null
    }
}
