package live.hogar.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoModeTest {
    @Test fun defaultOnlyForMusicApps() {
        assertEquals(true, AutoMode.allowed(AutoMode.DEFAULT, "com.apple.android.music"))
        assertEquals(true, AutoMode.allowed(AutoMode.DEFAULT, "live.hogar"))
        assertEquals(false, AutoMode.allowed(AutoMode.DEFAULT, "com.netflix.ninja"))
        assertEquals(false, AutoMode.allowed(AutoMode.DEFAULT, null))
        assertEquals(true, AutoMode.allowed(AutoMode.ALWAYS, "com.netflix.ninja"))
        assertEquals(false, AutoMode.allowed(AutoMode.NEVER, "com.spotify.music"))
    }

    @Test fun dueAfterThreeIdleMinutesOnHomeOnly() {
        assertEquals(false, AutoMode.due(179_999, 0, true))
        assertEquals(true, AutoMode.due(180_000, 0, true))
        assertEquals(false, AutoMode.due(500_000, 0, false))
    }
}
