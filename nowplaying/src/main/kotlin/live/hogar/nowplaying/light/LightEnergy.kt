package live.hogar.nowplaying.light

import android.media.audiofx.Visualizer
import android.os.SystemClock
import live.hogar.nowplaying.Breath
import live.hogar.nowplaying.EnergyTracker
import kotlin.math.sqrt

/**
 * Energía (0..1) y pulso del audio que suena en el TV (Apple Music, Spotify, AirPlay...) para la lámpara.
 * Visualizer(0) sobre la mezcla global si [audioAllowed] (RECORD_AUDIO); si no, o si el audio llega en
 * silencio exacto, respiración por tiempo. Se llama desde un único hilo (el bucle de la lámpara).
 */
internal class LightEnergy(private val audioAllowed: () -> Boolean) {
    private val tracker = EnergyTracker()
    private var probe: Probe? = null
    private var nextProbeMs = 0L
    private var timeSec = 0f
    private var smoothBreath = 0f

    var energy = 0f
        private set

    /** @return fuerza del pulso (0 si no hubo). */
    fun step(dtSec: Float): Float {
        timeSec += dtSec
        val nowMs = SystemClock.elapsedRealtime()
        if (probe == null && nowMs >= nextProbeMs) {
            nextProbeMs = nowMs + 4_000L
            if (audioAllowed()) probe = Probe.open()
        }
        var beat = 0f
        var live = false
        probe?.let { p ->
            val raw = p.level()
            if (raw < 0f) { p.release(); probe = null }
            else { beat = tracker.push(raw, dtSec); live = tracker.silentSec < 3f }
        }
        if (live) energy = tracker.energy
        else { smoothBreath += (Breath.at(timeSec) - smoothBreath) * 0.1f; energy = smoothBreath }
        return beat
    }

    fun release() { probe?.release(); probe = null }

    private class Probe(private val v: Visualizer, private val buf: ByteArray) {
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
            fun open(): Probe? {
                var v: Visualizer? = null
                return try {
                    val viz = Visualizer(0)
                    v = viz
                    val range = Visualizer.getCaptureSizeRange()
                    viz.setCaptureSize(256.coerceIn(range[0], range[1]))
                    viz.setEnabled(true)
                    Probe(viz, ByteArray(viz.captureSize))
                } catch (_: Throwable) {
                    try { v?.release() } catch (_: Throwable) {}
                    null
                }
            }
        }
    }
}
