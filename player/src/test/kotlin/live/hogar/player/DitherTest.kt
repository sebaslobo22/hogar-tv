package live.hogar.player

import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DitherTest {
    @Test fun `24 a 16 bits con dither queda dentro de 1,5 LSB y sin sesgo`() {
        val rnd = java.util.Random(7)
        val frames = 50_000
        val bytes = ByteArray(frames * 2 * 3)
        val exact = DoubleArray(frames * 2)   // valor ideal en unidades de LSB de 16 bits
        for (i in 0 until frames * 2) {
            val v = rnd.nextInt(1 shl 23) - (1 shl 22)   // 24 bits, mitad de escala
            bytes[3 * i] = v.toByte(); bytes[3 * i + 1] = (v shr 8).toByte(); bytes[3 * i + 2] = (v shr 16).toByte()
            exact[i] = v / 256.0
        }
        val p = PcmPipeline(44100, 2, PcmEncoding.PCM_24, 24)
        assertTrue(!p.bitExact)
        val out = p.process(bytes, bytes.size)
        assertEquals(frames * 4, out.size)
        var sum = 0.0; var sq = 0.0; var max = 0.0
        for (i in 0 until frames * 2) {
            val q = ((out[2 * i].toInt() and 0xFF) or (out[2 * i + 1].toInt() shl 8)).toShort().toInt()
            val err = q - exact[i]
            sum += err; sq += err * err; max = maxOf(max, abs(err))
        }
        val n = frames * 2
        assertTrue("max $max", max < 1.5)
        assertTrue("sesgo ${sum / n}", abs(sum / n) < 0.02)
        val rms = sqrt(sq / n)
        assertTrue("rms $rms", rms > 0.4 && rms < 0.6)   // TPDF + redondeo: 0,5 LSB teóricos
    }

    @Test fun `tpdf tiene media cero y rango menor que 1`() {
        val d = TpdfDither(99)
        var s = 0.0; var m = 0.0
        repeat(100_000) { val x = d.next(); s += x; m = maxOf(m, abs(x)) }
        assertTrue(abs(s / 100_000) < 0.01)
        assertTrue(m < 1.0)
    }

    @Test fun `16 bits en float se cuantiza sin error`() {
        for (s in intArrayOf(-32768, -1, 0, 1, 12345, 32767)) {
            assertEquals(s, Quantizer.toInt16(s / 32768.0, null))
        }
    }

    @Test fun `recorta fuera de escala`() {
        assertEquals(32767, Quantizer.toInt16(1.5, null))
        assertEquals(-32768, Quantizer.toInt16(-1.5, null))
    }
}
