package live.hogar.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class AirPlayMetaTest {
    private fun item(tag: String, data: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(tag.toByteArray(Charsets.ISO_8859_1))
        o.write(byteArrayOf((data.size ushr 24).toByte(), (data.size ushr 16).toByte(), (data.size ushr 8).toByte(), data.size.toByte()))
        o.write(data)
        return o.toByteArray()
    }
    private fun str(tag: String, s: String) = item(tag, s.toByteArray(Charsets.UTF_8))

    @Test fun parsesNestedDmap() {
        val body = str("minm", "Canción ñandú") + str("asar", "Artista") + str("asal", "Álbum") +
            item("astm", byteArrayOf(0, 3, 0x0D, 0x40)) + str("asgn", "Pop") // 200000 ms
        val m = AirPlayMeta.parseDmap(item("mlit", body))
        assertEquals(DmapMeta("Canción ñandú", "Artista", "Álbum", 200_000L), m)
    }

    @Test fun parsesFlatDmapAndSkipsUnknownAndTruncated() {
        val flat = str("minm", "Solo título") + item("zzzz", ByteArray(3))
        assertEquals("Solo título", AirPlayMeta.parseDmap(flat).title)
        val cut = item("mlit", str("minm", "x")).copyOf(10)
        assertNull(AirPlayMeta.parseDmap(cut).title)
        assertEquals(DmapMeta(), AirPlayMeta.parseDmap(ByteArray(0)))
    }

    @Test fun parsesProgress() {
        val p = AirPlayMeta.parseProgress("progress: 1000/45100/89200\r\n")!!
        assertEquals(1_000L, p.positionMs)        // (45100-1000)/44100 s
        assertEquals(2_000L, p.durationMs)         // (89200-1000)/44100 s
    }

    @Test fun rejectsBadProgress() {
        assertNull(AirPlayMeta.parseProgress("volume: -20.0"))
        assertNull(AirPlayMeta.parseProgress("progress: 1/2"))
        assertNull(AirPlayMeta.parseProgress("progress: a/b/c"))
        assertNull(AirPlayMeta.parseProgress("progress: 5000/100/9000"))
    }
}
