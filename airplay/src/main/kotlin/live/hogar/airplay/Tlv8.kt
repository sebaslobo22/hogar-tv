package live.hogar.airplay

import java.io.ByteArrayOutputStream

/** TLV8 de HomeKit (HAP): valores de más de 255 bytes se parten en trozos con la misma etiqueta. */
internal object Tlv8 {
    const val METHOD = 0x00
    const val IDENTIFIER = 0x01
    const val SALT = 0x02
    const val PUBLIC_KEY = 0x03
    const val PROOF = 0x04
    const val ENCRYPTED_DATA = 0x05
    const val SEQ_NO = 0x06
    const val ERROR = 0x07
    const val FLAGS = 0x13

    /** Bit de “emparejamiento transitorio” dentro de FLAGS. */
    const val FLAG_TRANSIENT = 0x10

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

    fun read(data: ByteArray): Map<Int, ByteArray> {
        val result = LinkedHashMap<Int, ByteArray>()
        var pos = 0
        while (pos + 2 <= data.size) {
            val tag = data[pos].toInt() and 0xFF
            val len = data[pos + 1].toInt() and 0xFF
            val end = minOf(pos + 2 + len, data.size)
            val v = data.copyOfRange(pos + 2, end)
            result[tag] = result[tag]?.plus(v) ?: v
            pos = end
        }
        return result
    }
}
