package live.hogar.player.bridge

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/** Salida del TV (altavoces propios). En Android es un AudioTrack; en las pruebas, un doble. */
internal interface LocalSink {
    /** Retraso fijo entre empezar a reproducir y oír la primera trama (ns). */
    val startLatencyNs: Long

    /** Bloquea si el búfer de la salida está lleno. */
    fun write(pcm: ByteArray, off: Int, len: Int)

    /** Tramas escritas y todavía no oídas, en el instante [nowNs]. */
    fun bufferedFrames(nowNs: Long): Long

    fun start()
    fun pauseAndFlush()
    fun setGain(g: Float)
    fun release()
}

/**
 * Línea de retraso del TV. Recibe bloques contiguos (con su trama RTP) y los escribe en la salida local
 * para que se OIGAN a la hora `horaLocal(trama) + retraso`, donde retraso = latencia de los HomePods
 * (+ ajuste fino). Mantiene siempre [BUFFER_NS] de audio por delante en la salida para no quedarse sin datos.
 *
 * Deriva: en cada bloque mide `error = ahora + búfer - horaDeseada`. Si pasa de 5 ms (con histéresis),
 * quita o duplica tramas sueltas de ESTE bloque. El flujo de los HomePods no pasa por aquí.
 * Un error de más de [HARD_NS] reinicia la reproducción local (vacía y vuelve a posicionar).
 */
internal class LocalPlayer(
    private val sink: LocalSink,
    private val timeline: StreamTimeline,
    private val nanos: () -> Long,
    @Volatile var delayNs: Long,
) {
    class Item(val chunk: Chunk, val gen: Int)

    sealed interface Step {
        data class Wait(val ns: Long) : Step
        data object Done : Step
    }

    private val queue = LinkedBlockingQueue<Item>()
    @Volatile private var gen = 0
    @Volatile private var startedGen = -1
    @Volatile var muted = false
        set(v) { field = v; if (v) { gen++; queue.clear(); sink.pauseAndFlush() } }
    @Volatile var errNs = 0L; private set
    @Volatile var inSync = false; private set
    @Volatile var droppedFrames = 0L; private set
    @Volatile var insertedFrames = 0L; private set
    @Volatile var hardResyncs = 0; private set
    private var ema = 0.0
    private var correcting = false

    @Volatile private var running = true
    private var thread: Thread? = null

    fun enqueue(chunk: Chunk) { if (!muted) queue.add(Item(chunk, gen)) }

    /** FLUSH del iPhone o reposicionado: descarta lo pendiente; el siguiente bloque se vuelve a colocar. */
    fun flush() {
        gen++
        queue.clear()
        inSync = false
        sink.pauseAndFlush()
    }

    /** Cambia el retraso (ajuste fino) y recoloca la reproducción. */
    fun setDelay(ns: Long) { delayNs = ns; gen++; inSync = false; sink.pauseAndFlush() }

    fun startThread() {
        val t = Thread(::loop, "hogar-bridge-local").also { it.isDaemon = true; it.priority = Thread.MAX_PRIORITY - 1 }
        thread = t
        t.start()
    }

    fun stop() {
        running = false
        gen++
        queue.clear()
        thread?.interrupt()
        runCatching { sink.pauseAndFlush() }
        runCatching { sink.release() }
    }

    private fun loop() {
        try {
            while (running) {
                val item = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                while (running && item.gen == gen) {
                    when (val s = process(item, nanos())) {
                        is Step.Wait -> LockSupport.parkNanos(minOf(s.ns, 20_000_000L))
                        Step.Done -> break
                    }
                }
            }
        } catch (_: InterruptedException) {
        }
    }

    /** Un paso para [item]: o hay que esperar su hora, o se escribió (o se descartó). Sin hilos: probable. */
    fun process(item: Item, now: Long): Step {
        if (item.gen != gen || muted) return Step.Done
        val chunk = item.chunk
        val frames = chunk.frames
        val desired = timeline.localNanosOf(chunk.startFrame) + delayNs   // cuándo debe oírse
        if (startedGen != gen) return startAt(item, desired, now)

        val writeAt = desired - BUFFER_NS
        if (now < writeAt) return Step.Wait(writeAt - now)
        val bufferedNs = sink.bufferedFrames(now) * 1_000_000_000L / RATE
        val err = now + bufferedNs - desired     // > 0: sonaría tarde; < 0: sonaría pronto
        errNs = err
        if (Math.abs(err) > HARD_NS) {
            hardResyncs++
            sink.pauseAndFlush()
            startedGen = -1
            inSync = false
            return process(item, now)
        }
        ema += 0.25 * (err - ema)
        val n = DriftCorrector.plan(ema.toLong(), frames, correcting)
        correcting = n != 0
        inSync = Math.abs(ema) <= DriftCorrector.ENGAGE_NS
        val out = if (n != 0) DriftCorrector.apply(chunk.pcm, n) else chunk.pcm
        if (n > 0) droppedFrames += n else if (n < 0) insertedFrames -= n
        if (item.gen != gen) return Step.Done
        sink.write(out, 0, out.size)
        return Step.Done
    }

    private fun startAt(item: Item, desired: Long, now: Long): Step {
        val chunk = item.chunk
        val startNs = desired - BUFFER_NS - sink.startLatencyNs
        if (now < startNs) return Step.Wait(startNs - now)
        val late = now - startNs
        var skip = 0
        if (late > DriftCorrector.ENGAGE_NS) {
            skip = (late * RATE / 1_000_000_000L).toInt()
            if (skip >= chunk.frames) return Step.Done   // todo el bloque ya pasó
        }
        val silence = ByteArray((BUFFER_NS * RATE / 1_000_000_000L).toInt() * BYTES_PER_FRAME)
        sink.write(silence, 0, silence.size)
        sink.write(chunk.pcm, skip * BYTES_PER_FRAME, chunk.pcm.size - skip * BYTES_PER_FRAME)
        if (item.gen != gen) return Step.Done
        sink.start()
        startedGen = item.gen
        ema = 0.0
        correcting = false
        errNs = 0
        inSync = true
        return Step.Done
    }

    companion object {
        const val BUFFER_NS = 80_000_000L      // audio que se mantiene por delante en la salida
        const val HARD_NS = 250_000_000L       // desfase a partir del cual se reinicia
    }
}
