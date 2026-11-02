package live.hogar.receiver.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Frames codificados con afconvert (ALAC de Apple, frames de 4096); el decodificador debe devolver el PCM exacto. */
class AlacDecoderTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun decodificaSinPerdida() {
        val lines = javaClass.getResourceAsStream("/alac_vectors.txt")!!.bufferedReader().readLines().filter { it.isNotBlank() }
        assertTrue(lines.size >= 3)
        for ((n, line) in lines.withIndex()) {
            val (pcmHex, encHex) = line.trim().split(" ")
            val pcm = hex(pcmHex)
            val expected = ShortArray(pcm.size / 2) { ((pcm[it * 2].toInt() and 0xFF) or (pcm[it * 2 + 1].toInt() shl 8)).toShort() }
            val decoded = AlacDecoder(frameLength = 4096).decode(hex(encHex))
            assertArrayEquals("frame $n", expected, decoded)
        }
    }
}
