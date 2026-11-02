package live.hogar.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReactivityTest {
    private val dtMs = 1000f / 30f

    /** Serie sintética a 30 Hz: base [low] con un golpe de [high] cada [period] muestras que decae rápido. */
    private fun pulses(seconds: Int, period: Int, low: Float = 0.25f, high: Float = 0.95f): List<Float> =
        List(seconds * 30) { i ->
            val k = i % period
            if (k == 0) high else if (k == 1) (high + low) / 2f else low
        }

    @Test fun detectsEveryPulseOfASteadyBeat() {
        val d = BeatDetector()
        val series = pulses(seconds = 10, period = 15) // 2 pulsos/s => 20
        val beats = series.count { d.push(it, dtMs) > 0f }
        assertTrue("beats=$beats", beats in 17..20) // el primero sirve para calentar la media
    }

    @Test fun constantEnergyNeverBeats() {
        val d = BeatDetector()
        assertEquals(0, List(300) { 0.6f }.count { d.push(it, dtMs) > 0f })
    }

    @Test fun slowRampIsNotABeat() {
        val d = BeatDetector()
        val ramp = List(300) { 0.2f + 0.7f * it / 300f }
        assertEquals(0, ramp.count { d.push(it, dtMs) > 0f })
    }

    @Test fun refractoryPeriodMergesNearbySpikes() {
        val d = BeatDetector()
        val series = MutableList(60) { 0.2f }
        series[30] = 0.95f; series[31] = 0.3f; series[32] = 0.95f // 66 ms entre golpes
        assertEquals(1, series.count { d.push(it, dtMs) > 0f })
    }

    @Test fun lowEnergyBelowFloorIsIgnored() {
        val d = BeatDetector()
        val series = List(300) { if (it % 15 == 0) 0.12f else 0.02f }
        assertEquals(0, series.count { d.push(it, dtMs) > 0f })
    }

    @Test fun trackerNormalizesVolumeAndReportsSilence() {
        val quiet = EnergyTracker()
        val loud = EnergyTracker()
        for (r in pulses(8, 15, 0.02f, 0.06f)) quiet.push(r, 1f / 30f)
        for (r in pulses(8, 15, 0.2f, 0.6f)) loud.push(r, 1f / 30f)
        assertTrue(quiet.energy in 0f..1f && loud.energy in 0f..1f)
        val s = EnergyTracker()
        repeat(120) { s.push(0f, 1f / 30f) }
        assertTrue(s.silentSec > 3.9f)
        s.push(0.3f, 1f / 30f)
        assertEquals(0f, s.silentSec, 0f)
    }

    @Test fun trackerFindsBeatsRegardlessOfVolume() {
        for (scale in listOf(0.05f, 0.5f)) {
            val t = EnergyTracker()
            val beats = pulses(10, 15, 0.25f * scale, 0.95f * scale).count { t.push(it, 1f / 30f) > 0f }
            assertTrue("scale=$scale beats=$beats", beats >= 12)
        }
    }

    @Test fun springSwellsThenSettles() {
        val s = SpringValue(0.5f, 0.85f)
        s.kick(2f)
        var peak = 0f
        repeat(15) { s.step(1f / 30f); peak = maxOf(peak, s.x) }
        assertTrue("peak=$peak", peak in 0.04f..0.09f)
        repeat(60) { s.step(1f / 30f) }
        assertEquals(0f, s.x, 0.003f)
    }

    @Test fun breathStaysGentle() {
        val vs = List(300) { Breath.at(it / 10f) }
        assertTrue(vs.all { it in 0f..0.36f })
    }
}
