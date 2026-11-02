package live.hogar.homekit

/**
 * JSON mínimo (sin org.json, que en las pruebas de JVM no existe). Escribe mapas/listas/cadenas/
 * números/booleanos y lee lo mismo; basta para el cuerpo de GET/PUT /characteristics.
 */
internal object Json {
    fun write(v: Any?): String = StringBuilder().also { write(it, v) }.toString()

    fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(v)
            is Int, is Long -> sb.append(v)
            is Double -> sb.append(if (v == Math.floor(v) && !v.isInfinite() && Math.abs(v) < 1e15) v.toLong().toString() else v.toString())
            is Float -> write(sb, v.toDouble())
            is String -> quote(sb, v)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    quote(sb, k.toString()); sb.append(':'); write(sb, value)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (e in v) {
                    if (!first) sb.append(',')
                    first = false
                    write(sb, e)
                }
                sb.append(']')
            }
            else -> quote(sb, v.toString())
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    /** Devuelve Map<String, Any?>, List<Any?>, String, Long/Double, Boolean o null. Lanza IllegalArgumentException. */
    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.value()
        p.skipWs()
        require(p.pos == text.length) { "JSON: texto sobrante" }
        return v
    }

    private class Parser(val s: String) {
        var pos = 0
        private var depth = 0

        fun skipWs() { while (pos < s.length && s[pos].isWhitespace()) pos++ }

        fun value(): Any? {
            skipWs()
            require(pos < s.length) { "JSON: fin inesperado" }
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw IllegalArgumentException("JSON: carácter $c")
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            require(s.startsWith(word, pos)) { "JSON: literal" }
            pos += word.length
            return v
        }

        private fun obj(): Map<String, Any?> {
            require(++depth < 32) { "JSON: demasiado anidado" }
            pos++
            val m = LinkedHashMap<String, Any?>()
            skipWs()
            if (s[pos] == '}') { pos++; depth--; return m }
            while (true) {
                skipWs()
                val k = str()
                skipWs()
                require(pos < s.length && s[pos] == ':') { "JSON: se esperaba :" }
                pos++
                m[k] = value()
                skipWs()
                require(pos < s.length) { "JSON: objeto sin cerrar" }
                if (s[pos] == ',') { pos++; continue }
                require(s[pos] == '}') { "JSON: se esperaba }" }
                pos++
                depth--
                return m
            }
        }

        private fun arr(): List<Any?> {
            require(++depth < 32) { "JSON: demasiado anidado" }
            pos++
            val l = ArrayList<Any?>()
            skipWs()
            if (s[pos] == ']') { pos++; depth--; return l }
            while (true) {
                l += value()
                skipWs()
                require(pos < s.length) { "JSON: lista sin cerrar" }
                if (s[pos] == ',') { pos++; continue }
                require(s[pos] == ']') { "JSON: se esperaba ]" }
                pos++
                depth--
                return l
            }
        }

        private fun str(): String {
            require(pos < s.length && s[pos] == '"') { "JSON: se esperaba cadena" }
            pos++
            val sb = StringBuilder()
            while (true) {
                require(pos < s.length) { "JSON: cadena sin cerrar" }
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        require(pos < s.length) { "JSON: escape" }
                        when (val e = s[pos++]) {
                            'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                            'u' -> {
                                require(pos + 4 <= s.length) { "JSON: \\u" }
                                sb.append(s.substring(pos, pos + 4).toInt(16).toChar()); pos += 4
                            }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Any {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            val t = s.substring(start, pos)
            return t.toLongOrNull() ?: t.toDouble()
        }
    }
}
