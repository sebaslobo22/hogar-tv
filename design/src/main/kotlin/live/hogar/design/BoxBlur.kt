package live.hogar.design

/**
 * Desenfoque de caja separable (3 pasadas ≈ gaussiano) sobre un arreglo ARGB. Puro: sin
 * Android, así se prueba en la JVM. Los bordes se extienden (clamp), por lo que una imagen
 * de un solo color queda igual. Se usa sobre una instantánea de baja resolución, una vez
 * por color de brillo, en un hilo de fondo.
 */
object BoxBlur {
    fun blur(px: IntArray, w: Int, h: Int, radius: Int, passes: Int = 3) {
        if (radius <= 0 || w <= 0 || h <= 0) return
        require(px.size >= w * h)
        val line = IntArray(maxOf(w, h))
        val out = IntArray(maxOf(w, h))
        repeat(passes) {
            for (y in 0 until h) {
                for (x in 0 until w) line[x] = px[y * w + x]
                blurLine(line, out, w, radius)
                for (x in 0 until w) px[y * w + x] = out[x]
            }
            for (x in 0 until w) {
                for (y in 0 until h) line[y] = px[y * w + x]
                blurLine(line, out, h, radius)
                for (y in 0 until h) px[y * w + x] = out[y]
            }
        }
    }

    private fun blurLine(src: IntArray, dst: IntArray, n: Int, r: Int) {
        val div = 2 * r + 1
        var a = 0; var rr = 0; var g = 0; var b = 0
        for (i in -r..r) {
            val p = src[i.coerceIn(0, n - 1)]
            a += p ushr 24; rr += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
        }
        for (i in 0 until n) {
            dst[i] = ((a + div / 2) / div shl 24) or ((rr + div / 2) / div shl 16) or
                ((g + div / 2) / div shl 8) or ((b + div / 2) / div)
            val add = src[(i + r + 1).coerceAtMost(n - 1)]
            val sub = src[(i - r).coerceAtLeast(0)]
            a += (add ushr 24) - (sub ushr 24)
            rr += ((add shr 16) and 0xFF) - ((sub shr 16) and 0xFF)
            g += ((add shr 8) and 0xFF) - ((sub shr 8) and 0xFF)
            b += (add and 0xFF) - (sub and 0xFF)
        }
    }
}
