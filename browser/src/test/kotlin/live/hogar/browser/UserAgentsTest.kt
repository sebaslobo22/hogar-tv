package live.hogar.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserAgentsTest {
    private val webView = "Mozilla/5.0 (Linux; Android 11; TV) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/131.0.6778.39 Safari/537.36"

    @Test fun desktopUsesInstalledChromeMajor() {
        val ua = UserAgents.desktop(webView)
        assertTrue(ua.contains("Chrome/131.0.0.0"))
        assertTrue(ua.contains("X11; Linux x86_64"))
        assertFalse(ua.contains("Android"))
        assertFalse(ua.contains("Mobile"))
    }

    @Test fun mobileHasMobileToken() {
        val ua = UserAgents.mobile(webView)
        assertTrue(ua.contains("Mobile Safari"))
        assertTrue(ua.contains("Chrome/131"))
    }

    @Test fun fallbackWhenNoChromeInDefault() {
        assertEquals("120", UserAgents.chromeMajor(""))
    }

    @Test fun zoomSteps() {
        assertEquals(125, ZoomSteps.more(100))
        assertEquals(175, ZoomSteps.more(175))
        assertEquals(100, ZoomSteps.less(100))
        assertEquals(150, ZoomSteps.less(175))
        assertEquals(125, ZoomSteps.clamp(130))
    }
}
