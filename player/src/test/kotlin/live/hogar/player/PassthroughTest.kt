package live.hogar.player

import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import live.hogar.core.AudioOutput
import live.hogar.core.OutputState
import live.hogar.core.PcmFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class RecordingOutput : AudioOutput {
    override val state: StateFlow<OutputState> = MutableStateFlow(OutputState.Streaming)
    override val latencyMs = 2000
    val received = java.io.ByteArrayOutputStream()
    var format: PcmFormat? = null
    override suspend fun connect(format: PcmFormat) { this.format = format }
    override suspend fun write(pcm: ByteArray, offset: Int, length: Int) { received.write(pcm, offset, length) }
    override suspend fun setVolume(level: Float) {}
    override suspend fun flush() {}
    override suspend fun disconnect() {}
}

class PassthroughTest {
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test fun `CD 16 bits 44_1 kHz llega a la salida con el mismo hash`() = runBlocking {
        val rnd = java.util.Random(42)
        val input = ByteArray(44100 * 4 * 3 + 12).also { rnd.nextBytes(it) }
        val out = RecordingOutput()
        out.connect(PcmFormat())
        val pump = PcmPump(PcmPipeline(44100, 2, PcmEncoding.PCM_16, 16), out)
        var pos = 0
        val sizes = intArrayOf(4096, 1000 * 4, 17 * 4, 65536)
        var i = 0
        while (pos < input.size) {
            val n = minOf(sizes[i++ % sizes.size], input.size - pos)
            pump.push(input.copyOfRange(pos, pos + n), n)
            pos += n
        }
        pump.finish()
        assertEquals(sha(input), sha(out.received.toByteArray()))
        assertEquals(PcmFormat(), out.format)
    }

    @Test fun `24 bits y 96 kHz no se marcan bit a bit`() {
        assertTrue(!PcmPipeline(44100, 2, PcmEncoding.PCM_16, 24).bitExact)
        assertTrue(!PcmPipeline(96000, 2, PcmEncoding.PCM_16, 16).bitExact)
        assertTrue(!PcmPipeline(44100, 1, PcmEncoding.PCM_16, 16).bitExact)
        assertTrue(!PcmPipeline(44100, 2, PcmEncoding.FLOAT, 16).bitExact)
    }

    @Test fun `96 kHz convertido produce 16 bits estereo con la duracion correcta`() = runBlocking {
        val frames = 96000
        val src = ByteArray(frames * 4)
        for (i in 0 until frames) {
            val v = (12000 * Math.sin(2 * Math.PI * 440 * i / 96000)).toInt()
            for (c in 0..1) { src[i * 4 + c * 2] = v.toByte(); src[i * 4 + c * 2 + 1] = (v shr 8).toByte() }
        }
        val out = RecordingOutput()
        val pump = PcmPump(PcmPipeline(96000, 2, PcmEncoding.PCM_16, 24), out)
        pump.push(src, src.size)
        pump.finish()
        assertEquals(44100 * 4, out.received.size())
    }

    @Test fun `mono se duplica a estereo`() {
        val mono = ByteArray(200) { (it * 3).toByte() }
        val p = PcmPipeline(44100, 1, PcmEncoding.PCM_16, 16)
        val o = p.process(mono, mono.size)
        assertEquals(400, o.size)
        // con dither el valor puede variar 1 LSB; ambos canales usan muestras distintas de ruido, comparar aproximado
        for (f in 0 until 100) {
            val l = ((o[4 * f].toInt() and 0xFF) or (o[4 * f + 1].toInt() shl 8)).toShort().toInt()
            val r = ((o[4 * f + 2].toInt() and 0xFF) or (o[4 * f + 3].toInt() shl 8)).toShort().toInt()
            assertTrue(Math.abs(l - r) <= 2)
        }
    }
}
