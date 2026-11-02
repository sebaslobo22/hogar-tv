package live.hogar.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrclibNamesTest {
    @Test fun singleArtistHasOneVariant() {
        assertEquals(listOf("Bad Bunny"), LrclibNames.artistVariants("Bad Bunny"))
    }

    @Test fun collaborationsAlsoTryFirstArtist() {
        assertEquals(listOf("Shakira & Bizarrap", "Shakira"), LrclibNames.artistVariants("Shakira & Bizarrap"))
        assertEquals("Karol G", LrclibNames.artistVariants("Karol G, Romeo Santos").last())
        assertEquals("Drake", LrclibNames.artistVariants("Drake feat. Rihanna").last())
        assertEquals("Drake", LrclibNames.artistVariants("Drake ft. Rihanna").last())
        assertEquals("Bad Bunny", LrclibNames.artistVariants("Bad Bunny x Jhay Cortez").last())
    }

    @Test fun artistsContainingXOrSlashInsideWordsAreNotSplit() {
        assertEquals(listOf("Xzibit"), LrclibNames.artistVariants("Xzibit"))
        assertEquals(listOf("Max Pezzali"), LrclibNames.artistVariants("Max Pezzali"))
    }

    @Test fun stripsFeaturingAndEditionNoise() {
        assertEquals("Song", LrclibNames.cleanTitle("Song (feat. Someone)"))
        assertEquals("Song", LrclibNames.cleanTitle("Song [ft. A & B]"))
        assertEquals("Song", LrclibNames.cleanTitle("Song - Remastered 2011"))
        assertEquals("Song", LrclibNames.cleanTitle("Song - 2009 Remaster"))
        assertEquals("Song", LrclibNames.cleanTitle("Song (Deluxe Edition)"))
        assertEquals("Song", LrclibNames.cleanTitle("Song - Single Version"))
        assertEquals("Song", LrclibNames.cleanTitle("Song (Remastered)  (feat. X)"))
    }

    @Test fun keepsPartsOfTheRealTitle() {
        assertEquals("Mi Gente (Remix)", LrclibNames.cleanTitle("Mi Gente (Remix)"))
        assertEquals("Un-Break My Heart", LrclibNames.cleanTitle("Un-Break My Heart"))
        assertEquals("Casa - Hogar", LrclibNames.cleanTitle("Casa - Hogar"))
    }

    @Test fun titleVariantsHandleHash() {
        val v = LrclibNames.titleVariants("#1 Crush (feat. Y)")
        assertEquals("#1 Crush (feat. Y)", v.first())
        assertTrue("1 Crush" in v)
        assertEquals(v.size, v.toSet().size)
    }

    @Test fun encodingEscapesReservedCharacters() {
        assertEquals("Rock%20%26%20Roll%20%231", LrclibNames.enc("Rock & Roll #1"))
        assertEquals("Ni%C3%B1a%20%C3%81ngel", LrclibNames.enc("Niña Ángel"))
        assertEquals("a%2Bb%3Dc%3Fd", LrclibNames.enc("a+b=c?d"))
    }

    @Test fun durationWindowIsThreeSeconds() {
        fun c(d: Double) = LrclibCandidate("[00:01.00]x", null, d, false)
        assertEquals(c(202.9), LrclibPick.best(listOf(c(202.9), c(209.0)), 200))
        // Fuera de ±3 s la sincronizada se descarta (desfasaría la letra).
        assertEquals(null, LrclibPick.best(listOf(c(204.5)), 200))
        // Texto plano no depende del tiempo.
        val plain = LrclibCandidate(null, "uno", 240.0, false)
        assertEquals(plain, LrclibPick.best(listOf(c(204.5), plain), 200))
    }
}
