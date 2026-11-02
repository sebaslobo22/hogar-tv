package live.hogar.nowplaying.light

/**
 * Lector JSON mínimo (objetos, listas, textos, números, booleanos, null). Existe porque `org.json`
 * es un esqueleto vacío en las pruebas JVM y los mensajes de Govee son pequeños y planos.
 * Devuelve Map/List/String/Double/Boolean/null, o null si el texto no es JSON válido.
 */
internal object MiniJson {
    fun parse(s: String): Any? = try {
        val p = Reader(s)
        val v = p.value()
        p.ws()
        if (p.i == s.length) v else null
    } catch (_: Exception) {
        null
    }

    /** Recorre claves anidadas: `get(root, "msg", "data")`. */
    fun get(root: Any?, vararg path: String): Any? {
        var cur = root
        for (k in path) cur = (cur as? Map<*, *>)?.get(k) ?: return null
        return cur
    }

    private class Reader(val s: String) {
        var i = 0

        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }

        fun value(): Any? {
            ws()
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw IllegalArgumentException("json")
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw IllegalArgumentException("json")
            i += word.length
            return v
        }

        private fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++; ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws()
                val k = str(); ws()
                if (s[i++] != ':') throw IllegalArgumentException("json")
                m[k] = value(); ws()
                when (s[i++]) { ',' -> Unit; '}' -> return m; else -> throw IllegalArgumentException("json") }
            }
        }

        private fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++; ws()
            if (s[i] == ']') { i++; return l }
            while (true) {
                l += value(); ws()
                when (s[i++]) { ',' -> Unit; ']' -> return l; else -> throw IllegalArgumentException("json") }
            }
        }

        private fun str(): String {
            if (s[i++] != '"') throw IllegalArgumentException("json")
            val sb = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = s[i++]) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                        'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                        'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> sb.append(e)
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Double {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(st, i).toDouble()
        }
    }
}
