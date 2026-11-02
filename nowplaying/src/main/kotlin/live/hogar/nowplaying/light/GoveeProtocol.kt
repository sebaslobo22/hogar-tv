package live.hogar.nowplaying.light

import java.util.Base64

/** Una lámpara Govee encontrada por la API LAN. */
data class LampInfo(val ip: String, val device: String, val sku: String)

/** Respuesta de `devStatus`. [kelvin] > 0 = modo blanco; 0 = color. */
data class LampStatus(val on: Boolean, val brightness: Int, val r: Int, val g: Int, val b: Int, val kelvin: Int)

/** Mensajes de la API LAN de Govee (UDP/JSON). Puro: se prueba en la JVM. */
object GoveeProtocol {
    const val MULTICAST_GROUP = "239.255.255.250"
    const val SCAN_PORT = 4001
    const val REPLY_PORT = 4002
    const val CONTROL_PORT = 4003

    fun scanRequest() = """{"msg":{"cmd":"scan","data":{"account_topic":"reserve"}}}"""
    fun devStatusRequest() = """{"msg":{"cmd":"devStatus","data":{}}}"""
    fun turn(on: Boolean) = """{"msg":{"cmd":"turn","data":{"value":${if (on) 1 else 0}}}}"""
    fun brightness(percent: Int) = """{"msg":{"cmd":"brightness","data":{"value":${percent.coerceIn(1, 100)}}}}"""

    /** [kelvin] = 0 → color RGB; > 0 → temperatura de color (con rgb en 0). */
    fun color(r: Int, g: Int, b: Int, kelvin: Int) =
        """{"msg":{"cmd":"colorwc","data":{"color":{"r":${r.coerceIn(0, 255)},"g":${g.coerceIn(0, 255)},"b":${b.coerceIn(0, 255)}},"colorTemInKelvin":${kelvin.coerceAtLeast(0)}}}}"""

    fun razer(packet: ByteArray) =
        """{"msg":{"cmd":"razer","data":{"pt":"${Base64.getEncoder().encodeToString(packet)}"}}}"""

    fun parseScan(json: String): LampInfo? {
        val root = MiniJson.parse(json)
        if (MiniJson.get(root, "msg", "cmd") != "scan") return null
        val d = MiniJson.get(root, "msg", "data") as? Map<*, *> ?: return null
        val ip = d["ip"] as? String ?: return null
        val device = d["device"] as? String ?: return null
        return LampInfo(ip, device, d["sku"] as? String ?: "")
    }

    fun parseStatus(json: String): LampStatus? {
        val root = MiniJson.parse(json)
        if (MiniJson.get(root, "msg", "cmd") != "devStatus") return null
        val d = MiniJson.get(root, "msg", "data") as? Map<*, *> ?: return null
        fun n(v: Any?) = (v as? Double)?.toInt() ?: 0
        val c = d["color"] as? Map<*, *>
        return LampStatus(
            on = n(d["onOff"]) == 1, brightness = n(d["brightness"]),
            r = n(c?.get("r")), g = n(c?.get("g")), b = n(c?.get("b")), kelvin = n(d["colorTemInKelvin"]),
        )
    }
}

/**
 * Protocolo "razer" (control en tiempo real por segmentos) que aceptan algunos modelos Govee.
 * Experimental: no hay forma de saber por software si la lámpara lo aceptó.
 */
object Razer {
    fun xor(bytes: ByteArray, len: Int = bytes.size): Byte {
        var x = 0
        for (i in 0 until len) x = x xor (bytes[i].toInt() and 0xFF)
        return x.toByte()
    }

    private fun withChecksum(body: IntArray): ByteArray {
        val out = ByteArray(body.size + 1)
        for (i in body.indices) out[i] = body[i].toByte()
        out[body.size] = xor(out, body.size)
        return out
    }

    fun enable() = withChecksum(intArrayOf(0xBB, 0x00, 0x01, 0xB1, 0x01))
    fun disable() = withChecksum(intArrayOf(0xBB, 0x00, 0x01, 0xB1, 0x00))

    /** Un color por segmento; [rgb] = 0xRRGGBB. */
    fun segments(rgb: IntArray): ByteArray {
        val n = rgb.size
        val body = IntArray(6 + 3 * n)
        body[0] = 0xBB; body[1] = 0x00; body[2] = 2 + 3 * n; body[3] = 0xB0; body[4] = 0x01; body[5] = n
        for (i in 0 until n) {
            body[6 + 3 * i] = (rgb[i] shr 16) and 0xFF
            body[7 + 3 * i] = (rgb[i] shr 8) and 0xFF
            body[8 + 3 * i] = rgb[i] and 0xFF
        }
        return withChecksum(body)
    }
}
