package live.hogar.player.bridge

/** Frecuencia fija de AirPlay a HomePod y del receptor del TV. */
internal const val RATE = 44_100
internal const val BYTES_PER_FRAME = 4

/**
 * Línea de tiempo del flujo entrante: relaciona la marca RTP (tramas, 64 bits ya desenrolladas) con el
 * reloj monótono local (`System.nanoTime()`).
 *
 * Se ancla con la primera trama tras un arranque o un FLUSH; a partir de ahí la hora local de la trama
 * `f` es `anclaNs + (f - anclaTrama) / 44100`. No se desplaza si un paquete llega tarde: el retraso del
 * altavoz y el del TV absorben la gigue. Solo si llega más tarde que [resyncNs] se pide re-sincronizar.
 */
internal class StreamTimeline {
    private var anchored = false
    private var anchorFrame = 0L
    private var anchorNs = 0L

    val isAnchored: Boolean get() = anchored

    fun reset() { anchored = false }

    fun anchorAt(frame: Long, ns: Long) { anchorFrame = frame; anchorNs = ns; anchored = true }

    fun localNanosOf(frame: Long): Long = anchorNs + (frame - anchorFrame) * 1_000_000_000L / RATE

    /** Trama que le toca sonar en el instante [ns] (sin retraso añadido). */
    fun frameAt(ns: Long): Long = anchorFrame + (ns - anchorNs) * RATE / 1_000_000_000L

    /**
     * Registra la llegada de la trama [frame] en [arrivalNs]. Ancla si hace falta. Devuelve true si
     * llegó tan tarde (más de [resyncNs] respecto a su hora) que conviene re-sincronizar todo.
     */
    fun noteArrival(frame: Long, arrivalNs: Long, resyncNs: Long): Boolean {
        if (!anchored) { anchorAt(frame, arrivalNs); return false }
        return arrivalNs - localNanosOf(frame) > resyncNs
    }
}

/** Bloque contiguo de PCM: [startFrame] = marca RTP desenrollada de su primera trama. */
internal class Chunk(val startFrame: Long, val pcm: ByteArray) {
    val frames: Int get() = pcm.size / BYTES_PER_FRAME
    val endFrame: Long get() = startFrame + frames
}

/**
 * Desenrolla la marca RTP de 32 bits y garantiza que el PCM que sale es contiguo: un hueco corto
 * (paquete perdido) se rellena con silencio para no desplazar al HomePod respecto al TV; un solapamiento
 * se recorta; un salto grande (>[maxGapFrames]) se marca como discontinuidad.
 */
internal class FrameSequencer(private val maxGapFrames: Int = RATE * 2) {
    private var last32 = -1L
    private var lastUnwrapped = 0L
    private var expected = -1L

    class Result(val chunks: List<Chunk>, val discontinuity: Boolean)

    fun reset() { last32 = -1; expected = -1 }

    fun unwrap(rtp32: Long): Long {
        val r = rtp32 and 0xFFFFFFFFL
        if (last32 < 0) { last32 = r; lastUnwrapped = r; return r }
        val diff = (r - last32).toInt().toLong()   // diferencia con signo módulo 2^32
        lastUnwrapped += diff
        last32 = r
        return lastUnwrapped
    }

    fun accept(rtp32: Long, pcm: ByteArray, len: Int): Result {
        val start = unwrap(rtp32)
        val bytes = (len / BYTES_PER_FRAME) * BYTES_PER_FRAME
        if (bytes == 0) return Result(emptyList(), false)
        val data = if (bytes == pcm.size) pcm else pcm.copyOfRange(0, bytes)
        val frames = bytes / BYTES_PER_FRAME
        if (expected < 0) {
            expected = start + frames
            return Result(listOf(Chunk(start, data)), false)
        }
        val gap = start - expected
        if (gap > maxGapFrames || gap < -maxGapFrames) {
            expected = start + frames
            return Result(listOf(Chunk(start, data)), true)
        }
        val out = ArrayList<Chunk>(2)
        var chunk = data
        var from = start
        if (gap > 0) {
            out.add(Chunk(expected, ByteArray(gap.toInt() * BYTES_PER_FRAME)))
        } else if (gap < 0) {
            val drop = (-gap).toInt()
            if (drop >= frames) return Result(emptyList(), false)   // repetido o muy atrasado
            chunk = data.copyOfRange(drop * BYTES_PER_FRAME, data.size)
            from = expected
        }
        out.add(Chunk(from, chunk))
        expected = from + chunk.size / BYTES_PER_FRAME
        return Result(out, false)
    }
}

/**
 * Corrección de deriva SOLO para la salida local del TV: quita o duplica tramas sueltas, repartidas
 * por el bloque, y nunca más de ~1,5 % del bloque (inaudible). Jamás se aplica al flujo de los HomePods.
 */
internal object DriftCorrector {
    const val ENGAGE_NS = 5_000_000L    // empieza a corregir con más de 5 ms de desfase
    const val RELEASE_NS = 1_000_000L   // deja de corregir por debajo de 1 ms

    /** Tramas a quitar (>0) o duplicar (<0) en este bloque para un desfase [errNs] (>0 = suena tarde). */
    fun plan(errNs: Long, blockFrames: Int, correcting: Boolean): Int {
        val a = Math.abs(errNs)
        if (a < (if (correcting) RELEASE_NS else ENGAGE_NS)) return 0
        val wanted = (a * RATE / 1_000_000_000L).toInt().coerceAtLeast(1)
        val cap = maxOf(1, blockFrames / 64)
        val n = minOf(wanted, cap)
        return if (errNs > 0) n else -n
    }

    /** [n] > 0 quita n tramas, [n] < 0 duplica |n|. Devuelve un arreglo nuevo (el original no se toca). */
    fun apply(pcm: ByteArray, n: Int): ByteArray {
        if (n == 0) return pcm
        val frames = pcm.size / BYTES_PER_FRAME
        val k = Math.abs(n).coerceAtMost(frames / 2)
        if (k == 0) return pcm
        val step = frames / k
        val out = ByteArray(pcm.size - (if (n > 0) k else -k) * BYTES_PER_FRAME)
        var o = 0
        var f = 0
        var done = 0
        while (f < frames) {
            val mark = done < k && f % step == step / 2 && f / step == done
            if (mark && n > 0) { done++; f++; continue }          // quitar esta trama
            System.arraycopy(pcm, f * BYTES_PER_FRAME, out, o, BYTES_PER_FRAME); o += BYTES_PER_FRAME
            if (mark && n < 0) {                                    // duplicarla
                System.arraycopy(pcm, f * BYTES_PER_FRAME, out, o, BYTES_PER_FRAME); o += BYTES_PER_FRAME
                done++
            }
            f++
        }
        return if (o == out.size) out else out.copyOf(o)
    }
}
