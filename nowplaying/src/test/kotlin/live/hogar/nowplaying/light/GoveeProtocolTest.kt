package live.hogar.nowplaying.light

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoveeProtocolTest {
    @Test fun parsesScanResponse() {
        val j = """{"msg":{"cmd":"scan","data":{"ip":"192.168.1.23","device":"1F:80:C5:32:32:36:72:4E","sku":"H607C","bleVersionHard":"3.01.01","bleVersionSoft":"1.03.01","wifiVersionHard":"1.00.10","wifiVersionSoft":"1.02.03"}}}"""
        assertEquals(LampInfo("192.168.1.23", "1F:80:C5:32:32:36:72:4E", "H607C"), GoveeProtocol.parseScan(j))
    }

    @Test fun scanRejectsOtherMessagesAndGarbage() {
        assertNull(GoveeProtocol.parseScan("""{"msg":{"cmd":"devStatus","data":{}}}"""))
        assertNull(GoveeProtocol.parseScan("no es json"))
        assertNull(GoveeProtocol.parseScan("""{"msg":{"cmd":"scan","data":{"sku":"H607C"}}}"""))
    }

    @Test fun parsesDevStatusColor() {
        val j = """{"msg":{"cmd":"devStatus","data":{"onOff":1,"brightness":42,"color":{"r":255,"g":128,"b":0},"colorTemInKelvin":0}}}"""
        assertEquals(LampStatus(true, 42, 255, 128, 0, 0), GoveeProtocol.parseStatus(j))
    }

    @Test fun parsesDevStatusKelvinAndOff() {
        val j = """{ "msg" : { "cmd":"devStatus", "data": {"onOff":0,"brightness":100,"color":{"r":0,"g":0,"b":0},"colorTemInKelvin":2700} } }"""
        val s = GoveeProtocol.parseStatus(j)
        assertNotNull(s)
        assertEquals(false, s!!.on)
        assertEquals(2700, s.kelvin)
    }

    @Test fun buildsCommandsAsValidJson() {
        for (m in listOf(
            GoveeProtocol.scanRequest(), GoveeProtocol.devStatusRequest(), GoveeProtocol.turn(true),
            GoveeProtocol.brightness(150), GoveeProtocol.color(300, -5, 7, 0), GoveeProtocol.razer(Razer.enable()),
        )) assertNotNull(m, MiniJson.parse(m))
        assertEquals(100.0, MiniJson.get(MiniJson.parse(GoveeProtocol.brightness(150)), "msg", "data", "value"))
        assertEquals(255.0, MiniJson.get(MiniJson.parse(GoveeProtocol.color(300, -5, 7, 0)), "msg", "data", "color", "r"))
    }

    @Test fun razerEnablePacketAndChecksum() {
        val p = Razer.enable()
        assertEquals("BB0001B1010A", p.joinToString("") { "%02X".format(it) })
        assertEquals("uwABsQEK", java.util.Base64.getEncoder().encodeToString(p))
        assertEquals("BB0001B1000B", Razer.disable().joinToString("") { "%02X".format(it) })
    }

    @Test fun razerSegmentPacketLayout() {
        val p = Razer.segments(intArrayOf(0xFF0000, 0x00FF00))
        assertEquals(6 + 6 + 1, p.size)
        assertEquals(0xBB.toByte(), p[0]); assertEquals(8.toByte(), p[2]); assertEquals(2.toByte(), p[5])
        assertEquals(0xFF.toByte(), p[6]); assertEquals(0xFF.toByte(), p[10])
        assertEquals(Razer.xor(p, p.size - 1), p.last())
        assertEquals(0, Razer.xor(p).toInt()) // xor de todo, checksum incluido, da 0
        assertTrue(p.size > 0)
    }
}
