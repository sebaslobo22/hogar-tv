package live.hogar.nowplaying.light

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SegmentsTest {
    private fun lamp(rgb: Int) = LampColor(((rgb shr 16) and 0xFF).toFloat(), ((rgb shr 8) and 0xFF).toFloat(), (rgb and 0xFF).toFloat(), 0f, 1f)
    private val red = lamp(0xFF0000); private val green = lamp(0x00FF00); private val blue = lamp(0x0000FF)

    @Test fun razerGradientAndDiscreteFlag() {
        val g = Razer.segments(intArrayOf(0xFF0000, 0x0000FF), gradient = true)
        val d = Razer.segments(intArrayOf(0xFF0000, 0x0000FF), gradient = false)
        assertEquals(1.toByte(), g[4]); assertEquals(0.toByte(), d[4])
        assertEquals(0, Razer.xor(g).toInt()); assertEquals(0, Razer.xor(d).toInt())
        assertEquals("BB0008B00102FF00000000FF" , g.take(11).joinToString("") { "%02X".format(it) } + "FF")
    }

    @Test fun razer12SegmentFrameSizeAndLen() {
        val p = Razer.segments(IntArray(12) { 0x112233 })
        assertEquals(6 + 36 + 1, p.size)
        assertEquals((2 + 36).toByte(), p[2]); assertEquals(12.toByte(), p[5])
        assertEquals(0, Razer.xor(p).toInt())
    }

    @Test fun ptRealPacketLayoutAndChecksum() {
        val p = PtReal.segmentColor(0x12, 0x34, 0x56, 0x7FFF)
        assertEquals(20, p.size)
        assertEquals("330515011234560000000000FF7F0000000000" , p.take(19).joinToString("") { "%02X".format(it) }.let { it.substring(0, 38) })
        assertEquals(Razer.xor(p, 19), p[19])
        assertEquals(0, Razer.xor(p).toInt())
    }

    @Test fun ptRealGroupsSegmentsByColor() {
        val pk = PtReal.packets(intArrayOf(0xFF0000, 0x00FF00, 0xFF0000, 0x00FF00))
        assertEquals(2, pk.size)
        assertEquals(0b0101, (pk[0][12].toInt() and 0xFF) or ((pk[0][13].toInt() and 0xFF) shl 8))
        assertEquals(0b1010, (pk[1][12].toInt() and 0xFF) or ((pk[1][13].toInt() and 0xFF) shl 8))
    }

    @Test fun ptRealJsonCarriesBase64Commands() {
        val pk = PtReal.packets(intArrayOf(0xFF0000))
        val json = GoveeProtocol.ptReal(pk)
        val cmds = MiniJson.get(MiniJson.parse(json), "msg", "data", "command") as List<*>
        assertEquals(1, cmds.size)
        assertEquals(pk[0].toList(), Base64.getDecoder().decode(cmds[0] as String).toList())
        assertEquals("ptReal", MiniJson.get(MiniJson.parse(json), "msg", "cmd"))
    }

    @Test fun razerJsonIsValid() {
        val j = GoveeProtocol.razer(Razer.segments(IntArray(12) { 0xABCDEF }))
        assertNotNull(MiniJson.parse(j))
        assertEquals("razer", MiniJson.get(MiniJson.parse(j), "msg", "cmd"))
    }

    @Test fun gradientSpreadsPaletteAcrossSegments() {
        val g = SegmentMath.gradient(listOf(red, green, blue), 12, 0f)
        assertEquals(12, g.size)
        assertEquals(0xFF0000, g[0])             // empieza en el primer color
        assertEquals(0x00FF00, g[4])             // un tercio: segundo color
        assertEquals(0x0000FF, g[8])
        assertTrue("tramos intermedios mezclados", (g[2] shr 16) and 0xFF in 1..254)
    }

    @Test fun rotationShiftsColorsAlongTheLampAndWraps() {
        val pal = listOf(red, green, blue)
        val a = SegmentMath.gradient(pal, 12, 0f)
        val b = SegmentMath.gradient(pal, 12, 1f) // un color de desplazamiento = 4 tramos
        for (i in 0 until 12) assertEquals(a[(i + 4) % 12], b[i])
        assertEquals(a.toList(), SegmentMath.gradient(pal, 12, 3f).toList()) // anillo completo
        assertNotEquals(a.toList(), SegmentMath.gradient(pal, 12, 0.5f).toList())
    }

    @Test fun gainBakesBrightnessAndSingleColorIsSolid() {
        val half = SegmentMath.gradient(listOf(red), 5, 0f, 0.5f)
        assertTrue(half.all { it == half[0] })
        assertEquals(127, (half[0] shr 16) and 0xFF)
    }

    @Test fun rainbowIsFixedAndFullRange() {
        val r = SegmentMath.rainbow(12)
        assertEquals(0xFF0000, r[0]); assertEquals(0xAA00FF, r[11])
        assertEquals(r.toList(), SegmentMath.rainbow(12).toList())
    }

    @Test fun framesPerMethod() {
        val rgb = SegmentMath.rainbow(12)
        assertEquals(1, SegmentMath.frame(LightMethod.GRADIENT, rgb).size)
        assertTrue(SegmentMath.frame(LightMethod.PTREAL, rgb)[0].contains("ptReal"))
        assertTrue(SegmentMath.frame(LightMethod.RAZER, rgb)[0].contains("razer"))
        assertTrue(SegmentMath.frame(LightMethod.COLORS, rgb).isEmpty())
        assertEquals(LightMethod.GRADIENT, LightMethod.parse(null))
        assertEquals(LightMethod.COLORS, LightMethod.PTREAL.next())
    }
}
