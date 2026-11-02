package live.hogar.player

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResamplerTest {
    private fun sine(freq: Double, rate: Int, frames: Int, amp: Double = 0.5): DoubleArray {
        val a = DoubleArray(frames * 2)
        for (i in 0 until frames) {
            val v = amp * sin(2 * PI * freq * i / rate)
            a[2 * i] = v; a[2 * i + 1] = v
        }
        return a
    }

    private fun resample(input: DoubleArray, inRate: Int, outRate: Int, chunkFrames: Int = 1000): DoubleArray {
        val r = PolyphaseResampler(inRate, outRate, 2)
        val out = ArrayList<Double>()
        val frames = input.size / 2
        var pos = 0
        while (pos < frames) {
            val n = minOf(chunkFrames, frames - pos)
            r.process(input.copyOfRange(pos * 2, (pos + n) * 2), n).forEach { out.add(it) }
            pos += n
        }
        r.finish().forEach { out.add(it) }
        return out.toDoubleArray()
    }

    /** SNR (dB) de la señal respecto a una senoide ajustada a [freq], canal izquierdo, sobre un número entero de ciclos. */
    private fun snrDb(y: DoubleArray, freq: Double, rate: Int, from: Int, count: Int): Pair<Double, Double> {
        var sc = 0.0; var ss = 0.0; var e = 0.0
        for (i in 0 until count) {
            val t = 2 * PI * freq * (from + i) / rate
            val v = y[2 * (from + i)]
            sc += v * cos(t); ss += v * sin(t); e += v * v
        }
        val a = 2 * sc / count; val b = 2 * ss / count
        val sig = (a * a + b * b) / 2
        val res = maxOf(e / count - sig, 1e-18 * sig)
        return 10 * log10(sig / res) to sqrt(a * a + b * b)
    }

    @Test fun `44_1 a 44_1 es identico bit a bit`() {
        val rnd = java.util.Random(1)
        val src = ByteArray(44100 * 4).also { rnd.nextBytes(it) }
        val p = PcmPipeline(44100, 2, PcmEncoding.PCM_16, 16)
        assertTrue(p.bitExact)
        var pos = 0
        val out = java.io.ByteArrayOutputStream()
        while (pos < src.size) {
            val n = minOf(4096, src.size - pos)
            out.write(p.process(src.copyOfRange(pos, pos + n), n))
            pos += n
        }
        out.write(p.finish())
        assertTrue(src.contentEquals(out.toByteArray()))
    }

    @Test fun `96k a 44_1k conserva 1 kHz con SNR mayor de 90 dB`() {
        val y = resample(sine(1000.0, 96000, 96000), 96000, 44100)
        assertEquals(44100, y.size / 2)
        // 441 muestras = 10 ciclos exactos de 1 kHz a 44,1 kHz
        val (snr, amp) = snrDb(y, 1000.0, 44100, 2000, 441 * 20)
        assertTrue("SNR $snr dB", snr > 90.0)
        assertEquals(0.5, amp, 0.002)
        // y no hay energía a otra frecuencia (1,1 kHz ajusta mal)
        val (wrong, _) = snrDb(y, 1100.0, 44100, 2000, 441 * 20)
        assertTrue(wrong < 10.0)
    }

    @Test fun `48k a 44_1k conserva 1 kHz y mantiene la duracion`() {
        val y = resample(sine(1000.0, 48000, 48000), 48000, 44100, chunkFrames = 777)
        assertEquals(44100, y.size / 2)
        val (snr, _) = snrDb(y, 1000.0, 44100, 2000, 441 * 20)
        assertTrue("SNR $snr dB", snr > 90.0)
    }

    @Test fun `192k a 44_1k conserva 1 kHz`() {
        val y = resample(sine(1000.0, 192000, 192000), 192000, 44100)
        val (snr, _) = snrDb(y, 1000.0, 44100, 3000, 441 * 20)
        assertTrue("SNR $snr dB", snr > 90.0)
    }

    @Test fun `22_05k a 44_1k sube el doble de muestras`() {
        val y = resample(sine(1000.0, 22050, 22050), 22050, 44100)
        assertEquals(44100, y.size / 2)
        val (snr, _) = snrDb(y, 1000.0, 44100, 2000, 441 * 20)
        assertTrue("SNR $snr dB", snr > 90.0)
    }

    @Test fun `banda eliminada atenua mas de 100 dB`() {
        // 25 kHz a 96 kHz estaria en 19,1 kHz al plegarse si no hubiera filtro
        val x = sine(25000.0, 96000, 96000, amp = 1.0)
        val y = resample(x, 96000, 44100)
        var e = 0.0
        val from = 2000; val n = 20000
        for (i in from until from + n) e += y[2 * i] * y[2 * i]
        val rms = sqrt(e / n)
        val inRms = sqrt(0.5)
        val att = 20 * log10(inRms / rms)
        assertTrue("atenuacion $att dB", att > 100.0)
    }
}
