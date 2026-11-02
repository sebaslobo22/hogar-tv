package live.hogar.player

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Re-muestreador polifásico de sinc con ventana Kaiser, en streaming.
 *
 * Diseñado para [stopbandDb] (por defecto 110 dB) de atenuación fuera de banda: el borde de
 * la banda de paso queda al 90,7 % de Nyquist (≈ 20 kHz para 44,1 kHz) y la banda eliminada
 * empieza en Nyquist. La relación up/down sale del máximo común divisor de las dos frecuencias,
 * así que 48→44,1 kHz es 147/160, 96→44,1 kHz es 147/320, etc.
 *
 * Compensa el retardo del filtro: la muestra de salida j corresponde al instante j·in/out
 * de la entrada. Llamar [finish] al terminar la pista vacía la cola.
 */
class PolyphaseResampler(
    val inRate: Int,
    val outRate: Int,
    val channels: Int,
    stopbandDb: Double = 110.0,
) {
    val up: Int
    val down: Int
    val tapsPerPhase: Int
    private val coeffs: DoubleArray
    private val center: Long

    private var buf: Array<DoubleArray> = Array(channels) { DoubleArray(8192) }
    private var base = 0L        // índice absoluto de buf[ch][0]
    private var filled = 0
    private var nextOut = 0L

    init {
        require(inRate > 0 && outRate > 0 && channels > 0)
        val g = gcd(inRate, outRate)
        up = outRate / g
        down = inRate / g
        require(up <= MAX_UP) { "Frecuencia de muestreo no soportada: $inRate Hz" }

        val stopEdge = min(inRate, outRate) / 2.0
        val transition = 0.093 * stopEdge
        val cutoff = stopEdge - transition / 2
        val upRate = up.toDouble() * inRate
        val fc = cutoff / upRate
        val dw = 2 * PI * transition / upRate
        val totalTaps = ceil((stopbandDb - 7.95) / (2.285 * dw)).toInt()
        var t = (totalTaps + up - 1) / up
        if (t % 2 == 1) t++
        t = max(t, 8)
        tapsPerPhase = t
        val len = t * up
        val c = len / 2
        center = c.toLong()

        val beta = 0.1102 * (stopbandDb - 8.7)
        val i0Beta = besselI0(beta)
        val h = DoubleArray(len)
        var sum = 0.0
        for (i in 0 until len) {
            val x = (i - c).toDouble()
            val sinc = if (x == 0.0) 2 * fc else sin(2 * PI * fc * x) / (PI * x)
            val r = x / c
            val w = besselI0(beta * sqrt(max(0.0, 1.0 - r * r))) / i0Beta
            h[i] = sinc * w
            sum += h[i]
        }
        val scale = up / sum   // ganancia unidad en continua tras la inserción de ceros
        coeffs = DoubleArray(len)
        for (r in 0 until up) for (k in 0 until t) coeffs[r * t + k] = h[r + up * k] * scale
    }

    /** [input] intercalado, [frames] cuadros. Devuelve las muestras de salida intercaladas que ya se pueden calcular. */
    fun process(input: DoubleArray, frames: Int): DoubleArray {
        append(input, frames)
        return produce(Long.MAX_VALUE)
    }

    /** Vacía la cola del filtro y deja el re-muestreador listo para otra pista. */
    fun finish(): DoubleArray {
        val real = base + filled
        append(null, tapsPerPhase + 2)
        val maxJ = ceilDiv(real * up, down.toLong())
        val out = produce(maxJ)
        reset()
        return out
    }

    fun reset() {
        base = 0
        filled = 0
        nextOut = 0
    }

    private fun append(input: DoubleArray?, frames: Int) {
        val need = filled + frames
        if (buf[0].size < need) {
            val n = max(need, buf[0].size * 2)
            for (ch in 0 until channels) buf[ch] = buf[ch].copyOf(n)
        }
        for (ch in 0 until channels) {
            val b = buf[ch]
            if (input == null) {
                java.util.Arrays.fill(b, filled, filled + frames, 0.0)
            } else {
                var p = ch
                for (f in 0 until frames) { b[filled + f] = input[p]; p += channels }
            }
        }
        filled += frames
    }

    private fun produce(maxJ: Long): DoubleArray {
        val totalIn = base + filled
        val avail = totalIn * up - center
        val endJ = min(if (avail <= 0) 0L else ceilDiv(avail, down.toLong()), maxJ)
        val count = max(0L, endJ - nextOut).toInt()
        val out = DoubleArray(count * channels)
        val taps = tapsPerPhase
        for (o in 0 until count) {
            val q = nextOut * down + center
            val nMax = q / up
            val cb = (q % up).toInt() * taps
            val hi = (nMax - base).toInt()
            val kMax = min(taps - 1, hi)
            for (ch in 0 until channels) {
                val b = buf[ch]
                var acc = 0.0
                for (k in 0..kMax) acc += coeffs[cb + k] * b[hi - k]
                out[o * channels + ch] = acc
            }
            nextOut++
        }
        // Descarta lo que ya no hace falta.
        val minNeeded = (nextOut * down + center) / up - taps + 1
        val drop = (minNeeded - base).toInt()
        if (drop >= 4096 && drop <= filled) {
            for (ch in 0 until channels) System.arraycopy(buf[ch], drop, buf[ch], 0, filled - drop)
            base += drop
            filled -= drop
        }
        return out
    }

    private companion object {
        const val MAX_UP = 1024

        fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
        fun ceilDiv(a: Long, b: Long): Long = (a + b - 1) / b

        fun besselI0(x: Double): Double {
            var term = 1.0
            var sum = 1.0
            val q = x * x / 4
            var k = 1
            while (term > 1e-21 * sum) {
                term *= q / (k.toDouble() * k)
                sum += term
                k++
            }
            return sum
        }
    }
}
