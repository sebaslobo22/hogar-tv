package live.hogar.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackMathTest {
    @Test fun extrapolatesWhilePlaying() {
        assertEquals(15_000L, PlaybackMath.position(10_000, 1_000, 6_000, 1f, true, 200_000))
        assertEquals(20_000L, PlaybackMath.position(10_000, 1_000, 6_000, 2f, true, 200_000))
    }

    @Test fun staysPutWhenPaused() {
        assertEquals(10_000L, PlaybackMath.position(10_000, 1_000, 99_000, 1f, false, 200_000))
    }

    @Test fun clampsToDurationAndZero() {
        assertEquals(30_000L, PlaybackMath.position(29_000, 0, 5_000, 1f, true, 30_000))
        assertEquals(0L, PlaybackMath.position(-1, 0, 0, 1f, false, 0))
        assertEquals(5_000L, PlaybackMath.position(5_000, 9_000, 3_000, 1f, true, 0)) // reloj hacia atrás
    }

    @Test fun clockFormat() {
        assertEquals("0:05", PlaybackMath.clock(5_000))
        assertEquals("3:07", PlaybackMath.clock(187_000))
        assertEquals("1:01:01", PlaybackMath.clock(3_661_000))
        assertEquals("0:00", PlaybackMath.clock(-5))
    }

    @Test fun autoModeDefaultsToMusicApps() {
        assertEquals(true, AutoModePolicy.allowed(AutoModePolicy.DEFAULT, "com.apple.android.music"))
        assertEquals(true, AutoModePolicy.allowed(AutoModePolicy.DEFAULT, "com.spotify.music"))
        assertEquals(false, AutoModePolicy.allowed(AutoModePolicy.DEFAULT, "com.netflix.ninja"))
        assertEquals(true, AutoModePolicy.allowed(AutoModePolicy.ALWAYS, "com.netflix.ninja"))
        assertEquals(false, AutoModePolicy.allowed(AutoModePolicy.NEVER, "com.spotify.music"))
        assertEquals(false, AutoModePolicy.allowed(AutoModePolicy.ALWAYS, null))
    }
}
