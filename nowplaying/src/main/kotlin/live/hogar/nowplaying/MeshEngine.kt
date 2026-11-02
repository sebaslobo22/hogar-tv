package live.hogar.nowplaying

import android.graphics.Bitmap
import android.media.audiofx.Visualizer
import android.os.SystemClock
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import live.hogar.design.Hogar
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Fondo animado + reactividad al audio. Una sola corrutina en Dispatchers.Default pinta el mesh
 * en un bitmap de 96×54 (doble búfer, sin asignaciones por cuadro) a ~30 cuadros/s y avanza
 * [frame]; la UI solo lee [frame] y [image] en la fase de dibujo (nada se recompone).
 * [start]/[stop] siguen onStart/onStop de la actividad: oculta no gasta nada.
 *
 * Energía: Visualizer sobre la mezcla global (sesión 0) si hay permiso RECORD_AUDIO y Android lo
 * permite; si no (o si el audio llega en silencio exacto, p. ej. contenido protegido) respira
 * suavemente por tiempo. Se reintenta abrirlo cada 4 s (por si el permiso se concede después).
 */
class MeshEngine(private val audioAllowed: () -> Boolean) {
    val frame = mutableIntStateOf(0)

    private val bitmaps = Array(2) { Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888) }
    private val images: Array<ImageBitmap> = Array(2) { bitmaps[it].asImageBitmap() }
    @Volatile private var front = 0
    val image: ImageBitmap get() = images[front]

    /** Energía suavizada 0..1 y golpe de pulso (para los puntos de la letra). */
    @Volatile var energy = 0f
        private set
    @Volatile var swell = 0f
        private set

    @Volatile private var colors: List<Int> = listOf(0xFF3B4C9B.toInt(), 0xFF1B1F3A.toInt(), 0xFF7A4CC2.toInt(), 0xFF0E2A47.toInt())
    @Volatile private var colorsVersion = 1
    private var job: Job? = null

    fun setColors(list: List<Int>) {
        if (list.isEmpty() || list == colors) return
        colors = list
        colorsVersion++
    }

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.Default) { run() }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun run() {
        val field = MeshField(W, H)
        val px = IntArray(W * H)
        val tracker = EnergyTracker()
        val spring = SpringValue(Hogar.Motion.Snappy.response, Hogar.Motion.Snappy.dampingFraction)
        var probe: AudioProbe? = null
        var nextProbeMs = 0L
        var seenVersion = 0
        var timeSec = 0f
        var smoothBreath = 0f
        var last = SystemClock.elapsedRealtimeNanos()
        var intervalMs = FRAME_MS
        try {
            while (currentCoroutineContext().isActive) {
                val t0 = SystemClock.elapsedRealtimeNanos()
                val dt = ((t0 - last) / 1e9f).coerceIn(0.001f, 0.25f)
                last = t0
                timeSec += dt

                if (seenVersion != colorsVersion) { seenVersion = colorsVersion; field.setTarget(colors) }
                field.step(dt)

                val nowMs = t0 / 1_000_000L
                if (probe == null && nowMs >= nextProbeMs) {
                    nextProbeMs = nowMs + PROBE_RETRY_MS
                    if (audioAllowed()) probe = AudioProbe.open()
                }
                var beat = 0f
                var live = false
                if (probe != null) {
                    val raw = probe.level()
                    if (raw < 0f) { probe.release(); probe = null }
                    else {
                        beat = tracker.push(raw, dt)
                        live = tracker.silentSec < SILENT_SEC
                    }
                }
                val e: Float
                if (live) {
                    e = tracker.energy
                } else {
                    smoothBreath += (Breath.at(timeSec) - smoothBreath) * 0.1f
                    e = smoothBreath
                }
                if (beat > 0f) spring.kick(2.0f * beat)
                spring.step(dt)
                val sw = spring.x.coerceIn(-0.02f, 0.06f)
                energy = e; swell = sw

                field.render(px, timeSec, e, sw)
                val back = 1 - front
                bitmaps[back].setPixels(px, 0, W, 0, 0, W, H)
                front = back
                frame.intValue = frame.intValue + 1

                val cost = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000L
                intervalMs = if (cost > 14) SLOW_FRAME_MS else FRAME_MS
                delay(max(2L, intervalMs - cost))
            }
        } finally {
            probe?.release()
        }
    }

    /** Envoltorio del Visualizer: nivel RMS 0..1 de la mezcla global, o -1 si falla. */
    private class AudioProbe(private val v: Visualizer, private val buf: ByteArray) {
        private var failures = 0

        fun level(): Float {
            val r = try { v.getWaveForm(buf) } catch (_: Throwable) { Visualizer.ERROR }
            if (r != Visualizer.SUCCESS) return if (++failures > 5) -1f else 0.0001f
            failures = 0
            var sum = 0L
            for (b in buf) { val d = (b.toInt() and 0xFF) - 128; sum += d * d }
            return sqrt(sum.toFloat() / buf.size) / 128f
        }

        fun release() {
            try { v.setEnabled(false) } catch (_: Throwable) {}
            try { v.release() } catch (_: Throwable) {}
        }

        companion object {
            fun open(): AudioProbe? {
                var v: Visualizer? = null
                return try {
                    val viz = Visualizer(0)
                    v = viz
                    val range = Visualizer.getCaptureSizeRange()
                    viz.setCaptureSize(256.coerceIn(range[0], range[1]))
                    viz.setEnabled(true)
                    AudioProbe(viz, ByteArray(viz.captureSize))
                } catch (_: Throwable) {
                    try { v?.release() } catch (_: Throwable) {}
                    null
                }
            }
        }
    }

    companion object {
        const val W = 96
        const val H = 54
        private const val FRAME_MS = 33L
        private const val SLOW_FRAME_MS = 50L
        private const val PROBE_RETRY_MS = 4_000L
        private const val SILENT_SEC = 3f
    }
}
