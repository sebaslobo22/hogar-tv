package live.hogar.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlInputTest {
    private fun isSearch(s: String) = UrlInput.resolve(s).startsWith(UrlInput.SEARCH)

    @Test fun emptyStaysEmpty() {
        assertEquals("", UrlInput.resolve("   "))
    }

    @Test fun bareDomainGetsHttps() {
        assertEquals("https://google.com", UrlInput.resolve("google.com"))
        assertEquals("https://music.apple.com/es", UrlInput.resolve(" music.apple.com/es "))
        assertEquals("https://es.wikipedia.org/wiki/Chile?x=1", UrlInput.resolve("es.wikipedia.org/wiki/Chile?x=1"))
    }

    @Test fun explicitHttpIsKept() {
        assertEquals("http://example.com/a", UrlInput.resolve("http://example.com/a"))
        assertEquals("HTTPS://Example.com", UrlInput.resolve("HTTPS://Example.com"))
    }

    @Test fun localAddressesUseHttp() {
        assertEquals("http://localhost:3000", UrlInput.resolve("localhost:3000"))
        assertEquals("http://192.168.1.5:8080/x", UrlInput.resolve("192.168.1.5:8080/x"))
        assertEquals("http://localhost", UrlInput.resolve("localhost"))
    }

    @Test fun textWithSpacesIsSearch() {
        val r = UrlInput.resolve("películas en cartelera")
        assertTrue(r.startsWith(UrlInput.SEARCH))
        assertEquals(UrlInput.SEARCH + "pel%C3%ADculas+en+cartelera", r)
    }

    @Test fun singleWordIsSearch() {
        assertTrue(isSearch("hola"))
        assertTrue(isSearch("3.14"))
        assertTrue(isSearch("e.g."))
        assertTrue(isSearch("a..b"))
    }

    @Test fun dangerousSchemesAreNeverOpened() {
        assertTrue(isSearch("javascript:alert(1)"))
        assertTrue(isSearch("file:///sdcard/x.html"))
        assertTrue(isSearch("data:text/html,hola"))
        assertTrue(isSearch("intent://x#Intent;end"))
        assertTrue(isSearch("ftp://example.com"))
    }

    @Test fun userInfoTricksAreSearch() {
        assertTrue(isSearch("https://google.com@evil.com"))
        assertTrue(isSearch("google.com@evil.com"))
    }

    @Test fun badIpAndPort() {
        assertTrue(isSearch("999.1.1.1"))
        assertTrue(isSearch("example.com:abc"))
    }

    @Test fun displayStripsSchemeAndWww() {
        assertEquals("example.com/a", UrlInput.display("https://www.example.com/a"))
        assertEquals("example.com", UrlInput.display("https://example.com/"))
        assertEquals("", UrlInput.display(""))
    }

    @Test fun hostOfUrl() {
        assertEquals("youtube.com", UrlInput.hostOfUrl("https://www.youtube.com/watch?v=1"))
    }
}
