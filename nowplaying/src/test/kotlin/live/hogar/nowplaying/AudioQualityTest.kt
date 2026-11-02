package live.hogar.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioQualityTest {
    private val std = mapOf(
        "android.media.metadata.TITLE" to "Palace of Salacious",
        "android.media.metadata.ALBUM" to "Lossless Hits ALAC Edition",
        "android.media.metadata.MEDIA_ID" to "alac-24-bit",
    )

    private fun v(meta: Map<String, String> = std, extras: Map<String, String> = emptyMap()) = AudioQuality.verdict(meta, extras)

    @Test fun losslessFromCustomKeyText() {
        assertEquals(QualityVerdict.Lossless, v(std + ("com.app.audio_quality" to "Lossless 24-bit/48 kHz")))
        assertEquals(QualityVerdict.Lossless, v(extras = mapOf("codec" to "ALAC")))
        assertEquals(QualityVerdict.Lossless, v(extras = mapOf("quality" to "Hi-Res Lossless")))
    }

    @Test fun losslessFromBooleanKey() {
        assertEquals(QualityVerdict.Lossless, v(extras = mapOf("is_lossless" to "true")))
        assertEquals(QualityVerdict.Lossy, v(extras = mapOf("is_lossless" to "false")))
    }

    @Test fun lossyIsNotLossless() {
        assertEquals(QualityVerdict.Lossy, v(extras = mapOf("mime" to "audio/mp4a-latm AAC")))
        assertEquals(QualityVerdict.Lossy, v(extras = mapOf("format" to "MP3 320")))
        // Una señal explícita en contra gana sobre una mención suelta.
        assertEquals(QualityVerdict.Lossy, v(extras = mapOf("lossless" to "false", "note" to "alac")))
    }

    @Test fun standardTextFieldsNeverCount() {
        // Título, álbum e id llevan "alac", "lossless" y "24-bit" y no son evidencia.
        assertEquals(QualityVerdict.Unknown, v())
        assertFalse(AudioQuality.badges(std, emptyMap()).any)
    }

    @Test fun unknownWithoutAnyEvidence() {
        assertEquals(QualityVerdict.Unknown, v(emptyMap(), emptyMap()))
        assertEquals(QualityVerdict.Unknown, v(extras = mapOf("palace" to "balance malacca")))
    }

    @Test fun hogarOwnPlayerFollowsTheSameRule() {
        // Sin que su sesión lo declare no hay insignia; declarado sí.
        assertFalse(AudioQuality.badges(mapOf("android.media.metadata.TITLE" to "x"), emptyMap()).lossless)
        assertTrue(AudioQuality.badges(mapOf("live.hogar.quality" to "lossless"), emptyMap()).lossless)
    }

    @Test fun atmosOnlyWhenDeclared() {
        assertTrue(AudioQuality.atmos(emptyMap(), mapOf("audio_format" to "Dolby Atmos")))
        assertTrue(AudioQuality.atmos(emptyMap(), mapOf("is_atmos" to "true")))
        assertFalse(AudioQuality.atmos(emptyMap(), mapOf("is_atmos" to "false")))
        assertFalse(AudioQuality.atmos(std + ("android.media.metadata.GENRE" to "Atmos"), emptyMap()))
        assertFalse(AudioQuality.atmos(emptyMap(), emptyMap()))
    }
}
