package live.hogar.airplay

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** Identificador UID de un plist binario (aparece en respuestas de algunos receptores). */
internal data class PlistUid(val value: Long)

/**
 * Codificador/decodificador mínimo de plist binario (`bplist00`).
 *
 * Tipos admitidos: `null`, `Boolean`, `Int`/`Long`, `Double`/`Float`, `String`, `ByteArray` (data),
 * `List<*>`, `Map<String, *>`. Al decodificar, las fechas salen como `Double` (segundos desde 2001),
 * los conjuntos como `List`, y los enteros como `Long`.
 */
internal object Plist {
    // ---------------------------------------------------------------- codificación

    private class Node(val kind: Int, val value: Any?, val children: IntArray)

    fun encode(root: Any?): ByteArray {
        val nodes = ArrayList<Node?>()
        fun add(v: Any?): Int {
            val idx = nodes.size
            nodes.add(null)
            when (v) {
                is Map<*, *> -> {
                    val entries = v.entries.toList()
                    val keys = entries.map { add(it.key.toString()) }
                    val vals = entries.map { add(it.value) }
                    nodes[idx] = Node(KIND_DICT, null, (keys + vals).toIntArray())
                }
                is List<*> -> {
                    val kids = v.map { add(it) }
                    nodes[idx] = Node(KIND_ARRAY, null, kids.toIntArray())
                }
                is Array<*> -> {
                    val kids = v.map { add(it) }
                    nodes[idx] = Node(KIND_ARRAY, null, kids.toIntArray())
                }
                else -> nodes[idx] = Node(KIND_LEAF, v, IntArray(0))
            }
            return idx
        }
        add(root)

        val refSize = if (nodes.size < 256) 1 else if (nodes.size < 65536) 2 else 4
        val out = ByteArrayOutputStream()
        out.write("bplist00".toByteArray(Charsets.US_ASCII))
        val offsets = LongArray(nodes.size)

        fun writeCount(marker: Int, count: Int) {
            if (count < 15) out.write(marker or count)
            else {
                out.write(marker or 0xF)
                writeInt(out, count.toLong())
            }
        }
        fun writeRef(i: Int) {
            for (s in refSize - 1 downTo 0) out.write(i ushr (8 * s))
        }

        for ((i, node) in nodes.withIndex()) {
            node!!
            offsets[i] = out.size().toLong()
            when (node.kind) {
                KIND_DICT -> {
                    writeCount(0xD0, node.children.size / 2)
                    node.children.forEach(::writeRef)
                }
                KIND_ARRAY -> {
                    writeCount(0xA0, node.children.size)
                    node.children.forEach(::writeRef)
                }
                else -> writeLeaf(out, node.value, ::writeCount)
            }
        }

        val tableOffset = out.size().toLong()
        val offSize = when {
            tableOffset < 256 -> 1
            tableOffset < 65536 -> 2
            tableOffset < (1L shl 32) -> 4
            else -> 8
        }
        for (o in offsets) for (s in offSize - 1 downTo 0) out.write((o ushr (8 * s)).toInt())
        val trailer = ByteBuffer.allocate(32)
        trailer.position(6)
        trailer.put(offSize.toByte()).put(refSize.toByte())
        trailer.putLong(nodes.size.toLong()).putLong(0L).putLong(tableOffset)
        out.write(trailer.array())
        return out.toByteArray()
    }

    private const val KIND_LEAF = 0
    private const val KIND_ARRAY = 1
    private const val KIND_DICT = 2

    private fun writeInt(out: ByteArrayOutputStream, v: Long) {
        val bytes = when {
            v < 0 -> 8
            v < 256 -> 1
            v < 65536 -> 2
            v < (1L shl 32) -> 4
            else -> 8
        }
        out.write(0x10 or (Integer.numberOfTrailingZeros(bytes)))
        for (s in bytes - 1 downTo 0) out.write((v ushr (8 * s)).toInt())
    }

    private fun writeLeaf(out: ByteArrayOutputStream, v: Any?, count: (Int, Int) -> Unit) {
        when (v) {
            null -> out.write(0x00)
            is Boolean -> out.write(if (v) 0x09 else 0x08)
            is Int -> writeInt(out, v.toLong())
            is Long -> writeInt(out, v)
            is Short -> writeInt(out, v.toLong())
            is Byte -> writeInt(out, v.toLong())
            is Float -> { out.write(0x22); out.write(ByteBuffer.allocate(4).putFloat(v).array()) }
            is Double -> { out.write(0x23); out.write(ByteBuffer.allocate(8).putDouble(v).array()) }
            is ByteArray -> { count(0x40, v.size); out.write(v) }
            is String -> {
                if (v.all { it.code < 128 }) {
                    count(0x50, v.length); out.write(v.toByteArray(Charsets.US_ASCII))
                } else {
                    count(0x60, v.length); out.write(v.toByteArray(Charsets.UTF_16BE))
                }
            }
            is PlistUid -> {
                out.write(0x80 or 7); out.write(ByteBuffer.allocate(8).putLong(v.value).array())
            }
            else -> throw IllegalArgumentException("Tipo no admitido en plist: ${v::class.java.name}")
        }
    }

    // ---------------------------------------------------------------- decodificación

    fun decode(data: ByteArray): Any? {
        require(data.size >= 40 && String(data, 0, 8, Charsets.US_ASCII) == "bplist00") { "No es un plist binario" }
        val t = data.size - 32
        val offSize = data[t + 6].toInt() and 0xFF
        val refSize = data[t + 7].toInt() and 0xFF
        val numObjects = ByteBuffer.wrap(data, t + 8, 8).long.toInt()
        val top = ByteBuffer.wrap(data, t + 16, 8).long.toInt()
        val tableOffset = ByteBuffer.wrap(data, t + 24, 8).long.toInt()

        fun uint(pos: Int, size: Int): Long {
            var r = 0L
            for (i in 0 until size) r = (r shl 8) or (data[pos + i].toLong() and 0xFF)
            return r
        }
        fun offsetOf(i: Int) = uint(tableOffset + i * offSize, offSize).toInt()

        fun lengthAt(pos: Int, nibble: Int): Pair<Int, Int> { // (count, bytes consumed after marker)
            if (nibble != 0xF) return nibble to 0
            val m = data[pos].toInt() and 0xFF
            val size = 1 shl (m and 0xF)
            return uint(pos + 1, size).toInt() to (1 + size)
        }

        fun parse(index: Int, depth: Int): Any? {
            require(depth < 64) { "plist demasiado anidado" }
            require(index in 0 until numObjects) { "referencia fuera de rango" }
            val pos = offsetOf(index)
            val marker = data[pos].toInt() and 0xFF
            val hi = marker shr 4
            val lo = marker and 0xF
            return when (hi) {
                0x0 -> when (lo) { 0x8 -> false; 0x9 -> true; else -> null }
                0x1 -> {
                    val size = 1 shl lo
                    if (size == 8) ByteBuffer.wrap(data, pos + 1, 8).long else uint(pos + 1, size)
                }
                0x2 -> if (lo == 2) ByteBuffer.wrap(data, pos + 1, 4).float.toDouble()
                else ByteBuffer.wrap(data, pos + 1, 8).double
                0x3 -> ByteBuffer.wrap(data, pos + 1, 8).double
                0x4 -> { val (n, c) = lengthAt(pos + 1, lo); data.copyOfRange(pos + 1 + c, pos + 1 + c + n) }
                0x5 -> { val (n, c) = lengthAt(pos + 1, lo); String(data, pos + 1 + c, n, Charsets.US_ASCII) }
                0x6 -> { val (n, c) = lengthAt(pos + 1, lo); String(data, pos + 1 + c, n * 2, Charsets.UTF_16BE) }
                0x8 -> PlistUid(uint(pos + 1, lo + 1))
                0xA, 0xC -> {
                    val (n, c) = lengthAt(pos + 1, lo)
                    val base = pos + 1 + c
                    List(n) { parse(uint(base + it * refSize, refSize).toInt(), depth + 1) }
                }
                0xD -> {
                    val (n, c) = lengthAt(pos + 1, lo)
                    val base = pos + 1 + c
                    val map = LinkedHashMap<String, Any?>()
                    for (i in 0 until n) {
                        val k = parse(uint(base + i * refSize, refSize).toInt(), depth + 1)
                        val v = parse(uint(base + (n + i) * refSize, refSize).toInt(), depth + 1)
                        map[k.toString()] = v
                    }
                    map
                }
                else -> throw IllegalArgumentException("Marcador de plist desconocido: 0x${marker.toString(16)}")
            }
        }
        return parse(top, 0)
    }

    @Suppress("UNCHECKED_CAST")
    fun decodeDict(data: ByteArray): Map<String, Any?> =
        decode(data) as? Map<String, Any?> ?: throw IllegalArgumentException("El plist no es un diccionario")
}
