package live.hogar.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IconLinksTest {
    private val base = "https://example.com/"

    @Test fun largestFirstAppleBeatsEqualSize() {
        val html = """
            <html><head>
            <link rel="icon" href="/favicon-32.png" sizes="32x32">
            <LINK REL="apple-touch-icon" href="/touch-180.png" sizes="180x180">
            <link rel='icon' href='/big.png' sizes='192x192' type='image/png'>
            </head>"""
        val c = IconLinks.candidates(html, base)
        assertEquals("https://example.com/big.png", c[0])
        assertEquals("https://example.com/touch-180.png", c[1])
        assertEquals("https://example.com/favicon-32.png", c[2])
    }

    @Test fun svgAndDataAndMaskAreSkipped() {
        val html = """
            <link rel="icon" href="/a.svg">
            <link rel="icon" type="image/svg+xml" href="/b">
            <link rel="icon" href="data:image/png;base64,AAAA">
            <link rel="mask-icon" href="/mask.png">
        """
        val c = IconLinks.candidates(html, base)
        assertFalse(c.any { it.contains("svg") || it.contains("mask") || it.startsWith("data:") })
        assertEquals(listOf("https://example.com/apple-touch-icon.png", "https://example.com/favicon.ico"), c)
    }

    @Test fun relativeUrlsResolveAgainstBase() {
        val html = """<link rel="shortcut icon" href="img/i.png" sizes="64x64">"""
        assertEquals("https://example.com/a/img/i.png", IconLinks.candidates(html, "https://example.com/a/")[0])
        assertEquals("https://cdn.x.com/i.png", IconLinks.candidates("""<link rel="icon" href="//cdn.x.com/i.png">""", base)[0])
    }

    @Test fun appleDefaultsTo180() {
        val html = """<link rel="icon" href="/p.png" sizes="128x128"><link rel="apple-touch-icon" href="/t.png">"""
        assertEquals("https://example.com/t.png", IconLinks.candidates(html, base)[0])
    }

    @Test fun sizesParsing() {
        assertEquals(32, IconLinks.sizeOf("16x16 32x32"))
        assertEquals(null, IconLinks.sizeOf("any"))
        assertEquals(null, IconLinks.sizeOf(null))
    }

    @Test fun noHtmlStillGivesFallbacks() {
        val c = IconLinks.candidates("", base)
        assertTrue(c.size == 2 && c[1].endsWith("favicon.ico"))
    }
}
