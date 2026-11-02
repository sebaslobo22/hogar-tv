package live.hogar.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QualityTest {
    @Test fun `frecuencias en espanol`() {
        assertEquals("44,1 kHz", QualityText.khz(44100))
        assertEquals("96 kHz", QualityText.khz(96000))
        assertEquals("48 kHz", QualityText.khz(48000))
        assertEquals("88,2 kHz", QualityText.khz(88200))
        assertEquals("22,05 kHz", QualityText.khz(22050))
        assertEquals("192 kHz", QualityText.khz(192000))
    }

    @Test fun `textos de calidad`() {
        assertNull(QualityText.badge(Quality.None))
        assertEquals("Sin pérdida · 16 bits/44,1 kHz · bit a bit", QualityText.badge(Quality.BitPerfect))
        assertEquals(
            "Convertido de 24 bits/96 kHz a 16 bits/44,1 kHz (máximo que acepta el HomePod)",
            QualityText.badge(Quality.Converted(24, 96000, 2)),
        )
        assertEquals("Sonido del TV · re-muestreado", QualityText.badge(Quality.TvSound))
    }

    @Test fun `retraso`() {
        assertEquals("2", QualityText.latencySeconds(2000))
        assertEquals("2", QualityText.latencySeconds(1900))
        assertEquals("2,5", QualityText.latencySeconds(2400))
    }

    @Test fun `profundidad FLAC`() {
        // STREAMINFO: 44100 Hz, 2 canales, 24 bits
        val si = ByteArray(34)
        val rate = 44100
        si[10] = (rate shr 12).toByte()
        si[11] = (rate shr 4).toByte()
        val bps = 24 - 1
        si[12] = (((rate and 0xF) shl 4) or ((2 - 1) shl 1) or (bps shr 4)).toByte()
        si[13] = ((bps and 0xF) shl 4).toByte()
        assertEquals(24, AudioFormats.parseFlacBitDepth(si))
        val withMarker = "fLaC".toByteArray() + byteArrayOf(0, 0, 0, 34) + si
        assertEquals(24, AudioFormats.parseFlacBitDepth(withMarker))
        assertNull(AudioFormats.parseFlacBitDepth(ByteArray(5)))
    }

    @Test fun `profundidad ALAC`() {
        val bare = ByteArray(24).also { it[5] = 24 }
        assertEquals(24, AudioFormats.parseAlacBitDepth(bare))
        val boxed = ByteArray(36).also { it[17] = 16 }
        assertEquals(16, AudioFormats.parseAlacBitDepth(boxed))
    }
}
