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

    /**
     * "ptReal": paquetes BLE de 20 bytes tunelados por LAN (base64 en `data.command`). Mismo mecanismo que
     * usan la app de Govee y proyectos como govee-ble-lights / wez/govee-py (`33 05 15 01 r g b … máscara`).
     */
    fun ptReal(packets: List<ByteArray>) =
        """{"msg":{"cmd":"ptReal","data":{"command":[${packets.joinToString(",") { "\"" + Base64.getEncoder().encodeToString(it) + "\"" }}]}}}"""

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

    /**
     * Un color por segmento; [rgb] = 0xRRGGBB. [gradient] = 1 → la lámpara interpola entre los N colores
     * (modo "Degradado"); 0 → N tramos de color plano. Formato `BB 00 LEN B0 <flag> N r g b… xor`, tal como
     * lo describe el volcado de OpenRGB para Govee (merge request 2172: cabecera BB 00, tamaño, comando B0 =
     * datos de LED con indicador de degradado, xor de todo lo anterior). El tamaño exacto no está
     * documentado por Govee: por eso el método es seleccionable y hay "Prueba de tramos".
     */
    fun segments(rgb: IntArray, gradient: Boolean = true): ByteArray {
        val n = rgb.size
        val body = IntArray(6 + 3 * n)
        body[0] = 0xBB; body[1] = 0x00; body[2] = 2 + 3 * n; body[3] = 0xB0; body[4] = if (gradient) 0x01 else 0x00; body[5] = n
        for (i in 0 until n) {
            body[6 + 3 * i] = (rgb[i] shr 16) and 0xFF
            body[7 + 3 * i] = (rgb[i] shr 8) and 0xFF
            body[8 + 3 * i] = rgb[i] and 0xFF
        }
        return withChecksum(body)
    }
}

/**
 * Paquetes BLE de 20 bytes (byte 20 = xor de los 19 anteriores) para ptReal.
 * Color por segmentos: `33 05 15 01 R G B 00 00 00 00 00 mLo mHi 00 00 00 00 00 xor`; la máscara de 16 bits
 * (bit i = segmento i; 0x7FFF = los 15 de las lámparas H60xx) elige a qué tramos aplica el color.
 */
object PtReal {
    const val SEGMENTS = 15

    fun frame(vararg head: Int): ByteArray {
        val b = ByteArray(20)
        for (i in head.indices) b[i] = head[i].toByte()
        b[19] = Razer.xor(b, 19)
        return b
    }

    fun segmentColor(r: Int, g: Int, b: Int, mask: Int): ByteArray =
        frame(0x33, 0x05, 0x15, 0x01, r and 0xFF, g and 0xFF, b and 0xFF, 0, 0, 0, 0, 0, mask and 0xFF, (mask shr 8) and 0xFF)

    /** Un paquete por color distinto (los segmentos con el mismo color comparten máscara). */
    fun packets(rgb: IntArray): List<ByteArray> {
        val groups = LinkedHashMap<Int, Int>()
        for (i in rgb.indices) groups[rgb[i]] = (groups[rgb[i]] ?: 0) or (1 shl i)
        return groups.map { (c, mask) -> segmentColor((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF, mask) }
    }
}
