package live.hogar.homekit

/**
 * Codificador de QR mínimo y propio: modo de bytes, corrección de errores M, versiones 1 a 6
 * (hasta 106 bytes; la carga de HomeKit mide 20). Devuelve la matriz de módulos (true = oscuro)
 * sin zona de silencio. Estructura de bloques y orden de colocación según ISO/IEC 18004.
 */
internal object Qr {
    private class Ver(val size: Int, val ecPerBlock: Int, val blocks: Int, val dataPerBlock: Int, val align: IntArray)

    // Versión 1..6 con nivel M.
    private val VERSIONS = listOf(
        Ver(21, 10, 1, 16, intArrayOf()),
        Ver(25, 16, 1, 28, intArrayOf(6, 18)),
        Ver(29, 26, 1, 44, intArrayOf(6, 22)),
        Ver(33, 18, 2, 32, intArrayOf(6, 26)),
        Ver(37, 24, 2, 43, intArrayOf(6, 30)),
        Ver(41, 16, 4, 27, intArrayOf(6, 34)),
    )

    val MAX_BYTES: Int = VERSIONS.last().let { it.blocks * it.dataPerBlock } - 2

    fun encode(text: String): Array<BooleanArray> = encode(text.toByteArray(Charsets.UTF_8))

    fun encode(data: ByteArray): Array<BooleanArray> {
        val vi = VERSIONS.indexOfFirst { data.size + 2 <= it.blocks * it.dataPerBlock }
        require(vi >= 0) { "Texto demasiado largo para el QR (${data.size} bytes)" }
        val v = VERSIONS[vi]
        val codewords = interleave(v, dataCodewords(v, data))
        return build(v, vi + 1, codewords)
    }

    // ---------------------------------------------------------------- datos

    private fun dataCodewords(v: Ver, data: ByteArray): ByteArray {
        val capacity = v.blocks * v.dataPerBlock
        val bits = BitBuf()
        bits.put(0b0100, 4) // modo bytes
        bits.put(data.size, 8)
        for (b in data) bits.put(b.toInt() and 0xFF, 8)
        bits.put(0, minOf(4, capacity * 8 - bits.len)) // terminador
        bits.put(0, (8 - bits.len % 8) % 8)
        var pad = 0xEC
        while (bits.len < capacity * 8) {
            bits.put(pad, 8)
            pad = if (pad == 0xEC) 0x11 else 0xEC
        }
        return bits.bytes()
    }

    private class BitBuf {
        private val list = ArrayList<Boolean>()
        val len: Int get() = list.size
        fun put(value: Int, count: Int) { for (i in count - 1 downTo 0) list += ((value ushr i) and 1) != 0 }
        fun bytes(): ByteArray {
            val out = ByteArray(list.size / 8)
            for (i in list.indices) if (list[i]) out[i / 8] = (out[i / 8].toInt() or (0x80 ushr (i % 8))).toByte()
            return out
        }
    }

    private fun interleave(v: Ver, data: ByteArray): ByteArray {
        val blocks = (0 until v.blocks).map { data.copyOfRange(it * v.dataPerBlock, (it + 1) * v.dataPerBlock) }
        val ecs = blocks.map { reedSolomon(it, v.ecPerBlock) }
        val out = java.io.ByteArrayOutputStream()
        for (i in 0 until v.dataPerBlock) for (b in blocks) out.write(b[i].toInt())
        for (i in 0 until v.ecPerBlock) for (e in ecs) out.write(e[i].toInt())
        return out.toByteArray()
    }

    // ---------------------------------------------------------------- Reed-Solomon sobre GF(256), polinomio 0x11D

    private val EXP = IntArray(512)
    private val LOG = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            EXP[i] = x; LOG[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor 0x11D
        }
        for (i in 255 until 512) EXP[i] = EXP[i - 255]
    }

    private fun gmul(a: Int, b: Int) = if (a == 0 || b == 0) 0 else EXP[LOG[a] + LOG[b]]

    /** Bytes de corrección de `data` con un generador de grado `degree`. */
    fun reedSolomon(data: ByteArray, degree: Int): ByteArray {
        var gen = intArrayOf(1)
        for (i in 0 until degree) {
            val next = IntArray(gen.size + 1)
            for (j in gen.indices) {
                next[j + 1] = next[j + 1] xor gen[j]
                next[j] = next[j] xor gmul(gen[j], EXP[i])
            }
            gen = next
        }
        // gen está en orden ascendente (gen[degree] = 1); el divisor va de mayor a menor grado, sin el 1 inicial.
        val rem = IntArray(degree)
        for (b in data) {
            val factor = (b.toInt() and 0xFF) xor rem[0]
            for (i in 0 until degree - 1) rem[i] = rem[i + 1]
            rem[degree - 1] = 0
            for (i in 0 until degree) rem[i] = rem[i] xor gmul(gen[degree - 1 - i], factor)
        }
        return ByteArray(degree) { rem[it].toByte() }
    }

    // ---------------------------------------------------------------- matriz

    private fun build(v: Ver, version: Int, codewords: ByteArray): Array<BooleanArray> {
        val size = v.size
        var best: Array<BooleanArray>? = null
        var bestScore = Int.MAX_VALUE
        for (mask in 0 until 8) {
            val m = Grid(size)
            drawFunctionPatterns(m, v)
            placeData(m, codewords)
            applyMask(m, mask)
            drawFormat(m, mask)
            val score = penalty(m.dark)
            if (score < bestScore) { bestScore = score; best = m.dark }
        }
        return best!!
    }

    private class Grid(val size: Int) {
        val dark = Array(size) { BooleanArray(size) }
        val fn = Array(size) { BooleanArray(size) }
        fun set(x: Int, y: Int, d: Boolean) { dark[y][x] = d; fn[y][x] = true }
    }

    private fun drawFunctionPatterns(m: Grid, v: Ver) {
        val size = m.size
        for (i in 0 until size) { m.set(6, i, i % 2 == 0); m.set(i, 6, i % 2 == 0) }
        finder(m, 3, 3); finder(m, size - 4, 3); finder(m, 3, size - 4)
        for (cy in v.align) for (cx in v.align) {
            val onFinder = (cx == 6 && cy == 6) || (cx == 6 && cy == v.align.last()) || (cx == v.align.last() && cy == 6)
            if (!onFinder) alignment(m, cx, cy)
        }
        // Reserva las zonas de formato (se rellenan después) y el módulo oscuro.
        drawFormat(m, 0)
    }

    private fun finder(m: Grid, cx: Int, cy: Int) {
        for (dy in -4..4) for (dx in -4..4) {
            val x = cx + dx; val y = cy + dy
            if (x < 0 || x >= m.size || y < 0 || y >= m.size) continue
            val dist = maxOf(Math.abs(dx), Math.abs(dy))
            m.set(x, y, dist != 2 && dist != 4)
        }
    }

    private fun alignment(m: Grid, cx: Int, cy: Int) {
        for (dy in -2..2) for (dx in -2..2) m.set(cx + dx, cy + dy, maxOf(Math.abs(dx), Math.abs(dy)) != 1)
    }

    private fun drawFormat(m: Grid, mask: Int) {
        val data = mask // nivel M = 00
        var rem = data
        for (i in 0 until 10) rem = (rem shl 1) xor ((rem ushr 9) * 0x537)
        val bits = ((data shl 10) or rem) xor 0x5412
        fun bit(i: Int) = ((bits ushr i) and 1) != 0
        val size = m.size
        for (i in 0..5) m.set(8, i, bit(i))
        m.set(8, 7, bit(6)); m.set(8, 8, bit(7)); m.set(7, 8, bit(8))
        for (i in 9..14) m.set(14 - i, 8, bit(i))
        for (i in 0..7) m.set(size - 1 - i, 8, bit(i))
        for (i in 8..14) m.set(8, size - 15 + i, bit(i))
        m.set(8, size - 8, true)
    }

    private fun placeData(m: Grid, data: ByteArray) {
        val size = m.size
        var i = 0
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vert in 0 until size) for (j in 0..1) {
                val x = right - j
                val upward = ((right + 1) and 2) == 0
                val y = if (upward) size - 1 - vert else vert
                if (!m.fn[y][x] && i < data.size * 8) {
                    m.dark[y][x] = ((data[i ushr 3].toInt() ushr (7 - (i and 7))) and 1) != 0
                    i++
                }
            }
            right -= 2
        }
    }

    private fun applyMask(m: Grid, mask: Int) {
        for (y in 0 until m.size) for (x in 0 until m.size) {
            val invert = when (mask) {
                0 -> (x + y) % 2 == 0
                1 -> y % 2 == 0
                2 -> x % 3 == 0
                3 -> (x + y) % 3 == 0
                4 -> (x / 3 + y / 2) % 2 == 0
                5 -> x * y % 2 + x * y % 3 == 0
                6 -> (x * y % 2 + x * y % 3) % 2 == 0
                else -> ((x + y) % 2 + x * y % 3) % 2 == 0
            }
            if (invert && !m.fn[y][x]) m.dark[y][x] = !m.dark[y][x]
        }
    }

    private fun penalty(g: Array<BooleanArray>): Int {
        val n = g.size
        var score = 0
        // Regla 1: rachas de 5 o más (filas y columnas).
        for (pass in 0..1) for (a in 0 until n) {
            var run = 1
            for (b in 1 until n) {
                val cur = if (pass == 0) g[a][b] else g[b][a]
                val prev = if (pass == 0) g[a][b - 1] else g[b - 1][a]
                if (cur == prev) {
                    run++
                    if (run == 5) score += 3 else if (run > 5) score++
                } else run = 1
            }
        }
        // Regla 2: bloques 2x2.
        for (y in 0 until n - 1) for (x in 0 until n - 1) {
            val c = g[y][x]
            if (c == g[y][x + 1] && c == g[y + 1][x] && c == g[y + 1][x + 1]) score += 3
        }
        // Regla 3: patrón 1011101 con 4 claros a un lado.
        val pat = booleanArrayOf(true, false, true, true, true, false, true)
        for (pass in 0..1) for (a in 0 until n) for (b in 0..n - 7) {
            var ok = true
            for (k in 0 until 7) {
                val c = if (pass == 0) g[a][b + k] else g[b + k][a]
                if (c != pat[k]) { ok = false; break }
            }
            if (!ok) continue
            fun light(from: Int, to: Int): Boolean {
                for (k in from until to) {
                    if (k < 0 || k >= n) continue
                    val c = if (pass == 0) g[a][k] else g[k][a]
                    if (c) return false
                }
                return true
            }
            if (light(b - 4, b)) score += 40
            if (light(b + 7, b + 11)) score += 40
        }
        // Regla 4: equilibrio de oscuros.
        var dark = 0
        for (row in g) for (c in row) if (c) dark++
        val total = n * n
        val k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1
        score += maxOf(k, 0) * 10
        return score
    }
}
