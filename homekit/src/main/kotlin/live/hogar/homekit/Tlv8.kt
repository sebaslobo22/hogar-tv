package live.hogar.homekit

import java.io.ByteArrayOutputStream

/** TLV8 de HAP: valores de más de 255 bytes se parten en trozos con la misma etiqueta. */
internal object Tlv8 {
    const val METHOD = 0x00
    const val IDENTIFIER = 0x01
    const val SALT = 0x02
    const val PUBLIC_KEY = 0x03
    const val PROOF = 0x04
    const val ENCRYPTED_DATA = 0x05
    const val STATE = 0x06
    const val ERROR = 0x07
    const val PERMISSIONS = 0x0B
    const val SIGNATURE = 0x0A
    const val FLAGS = 0x13
    /** Separador entre elementos de una lista (ListPairings). */
    const val SEPARATOR = 0xFF

    // Códigos de error de HAP (tabla 5-5 de la especificación).
    const val ERR_UNKNOWN = 1
    const val ERR_AUTHENTICATION = 2
    const val ERR_BACKOFF = 3
    const val ERR_MAX_PEERS = 4
    const val ERR_MAX_TRIES = 5
    const val ERR_UNAVAILABLE = 6
    const val ERR_BUSY = 7

    fun write(items: List<Pair<Int, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((tag, value) in items) {
            var pos = 0
            if (value.isEmpty()) {
                out.write(tag); out.write(0)
            }
            while (pos < value.size) {
                val n = minOf(255, value.size - pos)
                out.write(tag); out.write(n); out.write(value, pos, n)
                pos += n
            }
        }
        return out.toByteArray()
    }

    fun of(vararg items: Pair<Int, ByteArray>): ByteArray = write(items.toList())

    fun byte(v: Int) = byteArrayOf(v.toByte())

    /** Lee a un mapa: las etiquetas repetidas se concatenan (así se reensamblan los trozos). */
    fun read(data: ByteArray): Map<Int, ByteArray> {
        val result = LinkedHashMap<Int, ByteArray>()
        for ((tag, v) in readList(data)) result[tag] = result[tag]?.plus(v) ?: v
        return result
    }

    /** Lee en orden, uniendo solo trozos contiguos de 255 bytes con la misma etiqueta. */
    fun readList(data: ByteArray): List<Pair<Int, ByteArray>> {
        val out = ArrayList<Pair<Int, ByteArray>>()
        var pos = 0
        var lastFull = false
        while (pos + 2 <= data.size) {
            val tag = data[pos].toInt() and 0xFF
            val len = data[pos + 1].toInt() and 0xFF
            val end = minOf(pos + 2 + len, data.size)
            val v = data.copyOfRange(pos + 2, end)
            if (lastFull && out.isNotEmpty() && out.last().first == tag) {
                out[out.lastIndex] = tag to (out.last().second + v)
            } else {
                out += tag to v
            }
            lastFull = len == 255
            pos = end
        }
        return out
    }
}
