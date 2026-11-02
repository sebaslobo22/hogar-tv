package live.hogar.receiver.net

import java.io.ByteArrayOutputStream

/**
 * Listas de propiedades de Apple: lectura/escritura de plist binario (bplist00) y escritura XML.
 * Tipos: Map<String, Any?>, List<Any?>, String, Long, Double, Boolean, ByteArray, null.
 */
object Plist {
    // ---------------------------------------------------------------- lectura
    fun parse(data: ByteArray): Any? {
        require(data.size >= 40 && String(data, 0, 8, Charsets.US_ASCII) == "bplist00") { "no es bplist00" }
        val t = data.size - 32
        val offSize = data[t + 6].toInt() and 0xFF
        val refSize = data[t + 7].toInt() and 0xFF
        val count = readBE(data, t + 8, 8).toInt()
        val top = readBE(data, t + 16, 8).toInt()
        val tableOff = readBE(data, t + 24, 8).toInt()
        val offsets = IntArray(count) { readBE(data, tableOff + it * offSize, offSize).toInt() }
        val cache = HashMap<Int, Any?>()
        fun obj(ref: Int, depth: Int): Any? {
            require(depth < 64) { "plist demasiado profundo" }
            if (cache.containsKey(ref)) return cache[ref]
            var p = offsets[ref]
            val marker = data[p].toInt() and 0xFF
            val hi = marker ushr 4
            val lo = marker and 0xF
            p++
            fun lenOf(): Pair<Int, Int> {
                if (lo != 0xF) return lo to p
                val ib = data[p].toInt() and 0xFF
                require(ib ushr 4 == 1)
                val n = 1 shl (ib and 0xF)
                return readBE(data, p + 1, n).toInt() to (p + 1 + n)
            }
            val v: Any? = when (hi) {
                0x0 -> when (lo) { 0x8 -> false; 0x9 -> true; else -> null }
                0x1 -> {
                    val n = 1 shl lo
                    if (n == 8) readBE(data, p, 8) else if (n == 16) readBE(data, p + 8, 8) else readBE(data, p, n)
                }
                0x2 -> {
                    val n = 1 shl lo
                    if (n == 4) java.lang.Float.intBitsToFloat(readBE(data, p, 4).toInt()).toDouble()
                    else java.lang.Double.longBitsToDouble(readBE(data, p, 8))
                }
                0x3 -> java.lang.Double.longBitsToDouble(readBE(data, p, 8)) // fecha
                0x4 -> { val (n, s) = lenOf(); data.copyOfRange(s, s + n) }
                0x5 -> { val (n, s) = lenOf(); String(data, s, n, Charsets.US_ASCII) }
                0x6 -> { val (n, s) = lenOf(); String(data, s, n * 2, Charsets.UTF_16BE) }
                0x8 -> readBE(data, p, lo + 1)
                0xA -> {
                    val (n, s) = lenOf()
                    List(n) { obj(readBE(data, s + it * refSize, refSize).toInt(), depth + 1) }
                }
                0xD -> {
                    val (n, s) = lenOf()
                    val m = LinkedHashMap<String, Any?>()
                    for (i in 0 until n) {
                        val k = obj(readBE(data, s + i * refSize, refSize).toInt(), depth + 1)
                        val vv = obj(readBE(data, s + (n + i) * refSize, refSize).toInt(), depth + 1)
                        m[k.toString()] = vv
                    }
                    m
                }
                else -> null
            }
            cache[ref] = v
            return v
        }
        return obj(top, 0)
    }

    @Suppress("UNCHECKED_CAST")
    fun parseDict(data: ByteArray): Map<String, Any?> = parse(data) as Map<String, Any?>

    private fun readBE(b: ByteArray, off: Int, n: Int): Long {
        var v = 0L
        for (i in 0 until n) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }

    // ---------------------------------------------------------------- escritura binaria
    fun encode(root: Any?): ByteArray {
        val objs = ArrayList<Any?>()
        val refsOf = ArrayList<IntArray>() // para colecciones: referencias de hijos
        fun add(v: Any?): Int {
            val id = objs.size
            objs.add(v)
            refsOf.add(IntArray(0))
            when (v) {
                is List<*> -> refsOf[id] = IntArray(v.size).also { r -> v.forEachIndexed { i, x -> r[i] = add(x) } }
                is Map<*, *> -> {
                    val r = IntArray(v.size * 2)
                    var i = 0
                    for (k in v.keys) r[i++] = add(k.toString())
                    for (x in v.values) r[i++] = add(x)
                    refsOf[id] = r
                }
            }
            return id
        }
        add(root)
        val refSize = if (objs.size < 256) 1 else 2
        val out = ByteArrayOutputStream()
        out.write("bplist00".toByteArray(Charsets.US_ASCII))
        val offsets = IntArray(objs.size)
        fun writeLen(hi: Int, n: Int) {
            if (n < 15) out.write((hi shl 4) or n)
            else {
                out.write((hi shl 4) or 0xF)
                if (n < 256) { out.write(0x10); out.write(n) }
                else { out.write(0x11); out.write(n ushr 8); out.write(n and 0xFF) }
            }
        }
        for (i in objs.indices) {
            offsets[i] = out.size()
            when (val v = objs[i]) {
                null -> out.write(0x00)
                is Boolean -> out.write(if (v) 0x09 else 0x08)
                is Int -> writeInt(out, v.toLong())
                is Long -> writeInt(out, v)
                is Double -> { out.write(0x23); writeBE(out, java.lang.Double.doubleToLongBits(v), 8) }
                is Float -> { out.write(0x23); writeBE(out, java.lang.Double.doubleToLongBits(v.toDouble()), 8) }
                is ByteArray -> { writeLen(0x4, v.size); out.write(v) }
                is String -> {
                    if (v.all { it.code < 128 }) { writeLen(0x5, v.length); out.write(v.toByteArray(Charsets.US_ASCII)) }
                    else { writeLen(0x6, v.length); out.write(v.toByteArray(Charsets.UTF_16BE)) }
                }
                is List<*> -> { writeLen(0xA, v.size); refsOf[i].forEach { writeBE(out, it.toLong(), refSize) } }
                is Map<*, *> -> { writeLen(0xD, v.size); refsOf[i].forEach { writeBE(out, it.toLong(), refSize) } }
                else -> throw IllegalArgumentException("tipo no soportado: ${v::class}")
            }
        }
        val tableOff = out.size()
        val offSize = if (tableOff < 256) 1 else if (tableOff < 65536) 2 else 4
        offsets.forEach { writeBE(out, it.toLong(), offSize) }
        repeat(6) { out.write(0) }
        out.write(offSize); out.write(refSize)
        writeBE(out, objs.size.toLong(), 8)
        writeBE(out, 0, 8)
        writeBE(out, tableOff.toLong(), 8)
        return out.toByteArray()
    }

    private fun writeBE(o: ByteArrayOutputStream, v: Long, n: Int) {
        for (i in n - 1 downTo 0) o.write(((v ushr (8 * i)) and 0xFF).toInt())
    }

    private fun writeInt(o: ByteArrayOutputStream, v: Long) {
        when {
            v in 0..0xFF -> { o.write(0x10); writeBE(o, v, 1) }
            v in 0..0xFFFF -> { o.write(0x11); writeBE(o, v, 2) }
            v in 0..0xFFFFFFFFL -> { o.write(0x12); writeBE(o, v, 4) }
            else -> { o.write(0x13); writeBE(o, v, 8) }
        }
    }

    // ---------------------------------------------------------------- escritura XML
    fun toXml(root: Any?): ByteArray {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n")
        sb.append("<plist version=\"1.0\">\n")
        xml(sb, root)
        sb.append("</plist>\n")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun xml(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("<string></string>\n")
            is Boolean -> sb.append(if (v) "<true/>\n" else "<false/>\n")
            is Int, is Long -> sb.append("<integer>").append(v.toString()).append("</integer>\n")
            is Double, is Float -> sb.append("<real>").append(v.toString()).append("</real>\n")
            is String -> sb.append("<string>").append(esc(v)).append("</string>\n")
            is ByteArray -> sb.append("<data>").append(java.util.Base64.getEncoder().encodeToString(v)).append("</data>\n")
            is List<*> -> { sb.append("<array>\n"); v.forEach { xml(sb, it) }; sb.append("</array>\n") }
            is Map<*, *> -> {
                sb.append("<dict>\n")
                for ((k, x) in v) { sb.append("<key>").append(esc(k.toString())).append("</key>\n"); xml(sb, x) }
                sb.append("</dict>\n")
            }
            else -> sb.append("<string>").append(esc(v.toString())).append("</string>\n")
        }
    }

    /** Lee un plist XML sencillo (dict/array/string/integer/real/true/false/data) — para /play en texto. */
    fun parseXml(text: String): Any? {
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            try { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) } catch (_: Exception) {}
        }.newDocumentBuilder().apply { setEntityResolver { _, _ -> org.xml.sax.InputSource(java.io.StringReader("")) } }
            .parse(org.xml.sax.InputSource(java.io.StringReader(text)))
        val root = doc.documentElement
        val first = (0 until root.childNodes.length).map { root.childNodes.item(it) }.firstOrNull { it.nodeType == org.w3c.dom.Node.ELEMENT_NODE }
        return first?.let { node(it as org.w3c.dom.Element) }
    }

    private fun node(e: org.w3c.dom.Element): Any? {
        val kids = (0 until e.childNodes.length).map { e.childNodes.item(it) }.filterIsInstance<org.w3c.dom.Element>()
        return when (e.tagName) {
            "dict" -> {
                val m = LinkedHashMap<String, Any?>()
                var i = 0
                while (i + 1 < kids.size) { m[kids[i].textContent] = node(kids[i + 1]); i += 2 }
                m
            }
            "array" -> kids.map { node(it) }
            "string" -> e.textContent
            "integer" -> e.textContent.trim().toLong()
            "real" -> e.textContent.trim().toDouble()
            "true" -> true
            "false" -> false
            "data" -> java.util.Base64.getMimeDecoder().decode(e.textContent.trim())
            else -> null
        }
    }
}
