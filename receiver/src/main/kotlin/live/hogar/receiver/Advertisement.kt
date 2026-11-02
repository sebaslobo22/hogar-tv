package live.hogar.receiver

/** Registros TXT de mDNS (_airplay._tcp y _raop._tcp) y su version binaria para GET /info. */
internal object Advertisement {
    fun airplayTxt(id: Identity): LinkedHashMap<String, String> = linkedMapOf(
        "deviceid" to id.deviceId,
        "features" to "${Features.HEX},0x0",
        "flags" to "0x4",
        "model" to Features.MODEL,
        "pk" to id.pkHex,
        "pi" to id.pi,
        "srcvers" to Features.SRCVERS,
        "vv" to "2",
    )

    fun raopTxt(id: Identity): LinkedHashMap<String, String> = linkedMapOf(
        "txtvers" to "1",
        "ch" to "2",
        "cn" to "0,1,2,3",
        "da" to "true",
        "et" to "0,3,5",
        "ft" to "${Features.HEX},0x0",
        "md" to "0,1,2",
        "am" to Features.MODEL,
        "pk" to id.pkHex,
        "sf" to "0x4",
        "tp" to "UDP",
        "vn" to "65537",
        "vs" to Features.SRCVERS,
        "vv" to "2",
        "rhd" to "5.6.0.0",
        "sr" to "44100",
        "ss" to "16",
        "sv" to "false",
    )

    /** Formato binario DNS-SD TXT: cadenas "clave=valor" con un byte de largo delante. */
    fun encodeTxt(m: Map<String, String>): ByteArray {
        val o = java.io.ByteArrayOutputStream()
        for ((k, v) in m) {
            val b = "$k=$v".toByteArray(Charsets.UTF_8)
            o.write(minOf(b.size, 255)); o.write(b, 0, minOf(b.size, 255))
        }
        return o.toByteArray()
    }
}
