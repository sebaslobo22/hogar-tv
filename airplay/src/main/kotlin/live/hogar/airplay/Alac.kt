package live.hogar.airplay

import java.io.ByteArrayOutputStream

/** Codificador de audio de un paquete RTP: recibe PCM s16le intercalado y devuelve la carga útil. */
internal interface AudioEncoder {
    val name: String

    /** Campo `ct` del SETUP de AirPlay 2 (1 = PCM, 2 = ALAC). */
    val compressionType: Int

    /** Máscara `audioFormat` del SETUP (0x800 = PCM 44,1 kHz/16/2, 0x40000 = ALAC 44,1 kHz/16/2). */
    val audioFormat: Int
    val framesPerPacket: Int get() = Alac.FRAMES_PER_PACKET
    fun encode(pcmLe: ByteArray, offset: Int = 0): ByteArray
}

/** PCM L16 en orden de red (big-endian), como hace pyatv (`audio_source._to_audio_samples`). */
internal object PcmEncoder : AudioEncoder {
    override val name = "PCM"
    override val compressionType = 1
    override val audioFormat = 0x800
    override fun encode(pcmLe: ByteArray, offset: Int): ByteArray {
        val n = framesPerPacket * 4
        val out = ByteArray(n)
        var i = 0
        while (i < n) {
            out[i] = pcmLe[offset + i + 1]
            out[i + 1] = pcmLe[offset + i]
            i += 2
        }
        return out
    }
}

/** ALAC “verbatim”: cada trama se guarda sin comprimir (flag escape), bit a bit idéntica al PCM de origen. */
internal object AlacEncoder : AudioEncoder {
    override val name = "ALAC"
    override val compressionType = 2
    override val audioFormat = 0x40000
    override fun encode(pcmLe: ByteArray, offset: Int): ByteArray =
        Alac.encodeVerbatim(pcmLe, offset, framesPerPacket)
}

/**
 * Trama ALAC de canal estéreo sin compresión (ver `ALACDecoder::Decode`, caso ID_CPE con escapeFlag=1):
 *
 *  - 3 bits: etiqueta de elemento = 1 (par de canales)
 *  - 4 bits: instancia = 0
 *  - 12 bits: sin uso = 0
 *  - 1 bit trama parcial = 0, 2 bits desplazamiento = 0, 1 bit escape = 1
 *  - frames x [L (16 bits), R (16 bits)] en big-endian, sin alinear a byte
 *  - 3 bits: etiqueta de fin = 7, y relleno a byte
 */
internal object Alac {
    const val FRAMES_PER_PACKET = 352

    fun encodeVerbatim(pcmLe: ByteArray, offset: Int, frames: Int): ByteArray {
        require(pcmLe.size - offset >= frames * 4) { "PCM insuficiente para $frames tramas" }
        val bits = BitWriter(frames * 4 + 8)
        bits.write(1, 3)      // ID_CPE
        bits.write(0, 4)      // instancia
        bits.write(0, 12)     // sin uso
        bits.write(0b0001, 4) // parcial=0, shift=0, escape=1
        var p = offset
        repeat(frames * 2) {
            val lo = pcmLe[p].toInt() and 0xFF
            val hi = pcmLe[p + 1].toInt() and 0xFF
            bits.write((hi shl 8) or lo, 16)
            p += 2
        }
        bits.write(7, 3)      // ID_END
        return bits.toByteArray()
    }

    private class BitWriter(capacity: Int) {
        private val out = ByteArrayOutputStream(capacity)
        private var acc = 0
        private var nbits = 0

        fun write(value: Int, count: Int) {
            for (i in count - 1 downTo 0) {
                acc = (acc shl 1) or ((value ushr i) and 1)
                if (++nbits == 8) {
                    out.write(acc); acc = 0; nbits = 0
                }
            }
        }

        fun toByteArray(): ByteArray {
            if (nbits > 0) {
                out.write(acc shl (8 - nbits)); acc = 0; nbits = 0
            }
            return out.toByteArray()
        }
    }
}
