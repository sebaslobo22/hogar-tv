package live.hogar.player

import live.hogar.core.AudioOutput

/**
 * Lleva el PCM decodificado al formato del HomePod (44,1 kHz, 2 canales, 16 bits).
 *
 * - 44,1 kHz / 16 bits / estéreo: los bytes pasan SIN TOCAR (bit a bit).
 * - Otra frecuencia: re-muestreo sinc polifásico (≥ 100 dB) y luego 16 bits con dither TPDF.
 * - 24 bits (o flotante) a 44,1 kHz: solo reducción a 16 bits con dither TPDF.
 * - Mono se duplica; más de 2 canales se queda con los dos frontales (L y R).
 */
class PcmPipeline(
    val inRate: Int,
    val inChannels: Int,
    val encoding: Int,
    /** Profundidad real del archivo de origen (puede ser mayor que la del PCM que entregó el decodificador). */
    val sourceBits: Int,
    val outRate: Int = 44_100,
    ditherSeed: Long = 0x2545F4914F6CDD1DL,
) {
    val bitExact: Boolean =
        inRate == outRate && inChannels == 2 && encoding == PcmEncoding.PCM_16 && sourceBits <= 16

    private val resampler = if (inRate != outRate) PolyphaseResampler(inRate, outRate, 2) else null
    private val dither = TpdfDither(ditherSeed)
    private var work = DoubleArray(0)
    private var stereo = DoubleArray(0)

    fun process(src: ByteArray, length: Int): ByteArray {
        if (bitExact) return src.copyOfRange(0, length)
        val bps = PcmEncoding.bytesPerSample(encoding)
        val samples = length / bps
        val frames = samples / inChannels
        if (frames == 0) return ByteArray(0)
        if (work.size < samples) work = DoubleArray(samples)
        PcmEncoding.toDouble(src, length, encoding, work)
        if (stereo.size < frames * 2) stereo = DoubleArray(frames * 2)
        when (inChannels) {
            1 -> for (f in 0 until frames) { stereo[2 * f] = work[f]; stereo[2 * f + 1] = work[f] }
            2 -> System.arraycopy(work, 0, stereo, 0, frames * 2)
            else -> for (f in 0 until frames) {
                stereo[2 * f] = work[f * inChannels]
                stereo[2 * f + 1] = work[f * inChannels + 1]
            }
        }
        val r = resampler
        return if (r != null) quantize(r.process(stereo, frames), -1) else quantize(stereo, frames * 2)
    }

    /** Cola del filtro al terminar la pista. */
    fun finish(): ByteArray {
        val r = resampler ?: return ByteArray(0)
        return quantize(r.finish(), -1)
    }

    private fun quantize(a: DoubleArray, count: Int): ByteArray {
        val n = if (count < 0) a.size else count
        val out = ByteArray(n * 2)
        var p = 0
        for (i in 0 until n) {
            val v = Quantizer.toInt16(a[i], dither)
            out[p++] = v.toByte()
            out[p++] = (v shr 8).toByte()
        }
        return out
    }
}

/** Empuja el PCM por la tubería hacia una salida (que aplica la contrapresión). */
class PcmPump(private val pipeline: PcmPipeline, private val output: AudioOutput) {
    suspend fun push(src: ByteArray, length: Int) {
        val o = pipeline.process(src, length)
        if (o.isNotEmpty()) output.write(o, 0, o.size)
    }

    suspend fun finish() {
        val o = pipeline.finish()
        if (o.isNotEmpty()) output.write(o, 0, o.size)
    }
}
