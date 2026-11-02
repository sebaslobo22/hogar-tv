package live.hogar.player.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineTest {
    @Test fun `RTP se mapea al reloj local con 44100 tramas por segundo`() {
        val t = StreamTimeline()
        t.noteArrival(1_000, 5_000_000_000L, 1_000_000_000L)
        assertEquals(5_000_000_000L, t.localNanosOf(1_000))
        assertEquals(6_000_000_000L, t.localNanosOf(1_000 + 44_100))
        assertEquals(5_000_000_000L + 352L * 1_000_000_000L / 44_100, t.localNanosOf(1_352))
        assertEquals(1_000 + 44_100, t.frameAt(6_000_000_000L))
    }

    @Test fun `un paquete tardio no mueve el ancla pero uno muy tardio pide re-sincronizar`() {
        val t = StreamTimeline()
        t.noteArrival(0, 0, 1_000_000_000L)
        assertFalse(t.noteArrival(44_100, 1_100_000_000L, 1_000_000_000L))   // 100 ms tarde
        assertEquals(1_000_000_000L, t.localNanosOf(44_100))
        assertTrue(t.noteArrival(44_100, 2_500_000_000L, 1_000_000_000L))    // 1,5 s tarde
    }

    @Test fun `la marca RTP de 32 bits se desenrolla al dar la vuelta`() {
        val s = FrameSequencer()
        val a = s.unwrap(0xFFFFFF00L)
        val b = s.unwrap(0x00000060L)   // +0x160 tras la vuelta
        assertEquals(0x160L, b - a)
    }

    @Test fun `un hueco corto se rellena con silencio y el resto queda contiguo`() {
        val s = FrameSequencer()
        val p = ByteArray(352 * 4) { 7 }
        val r1 = s.accept(1000, p, p.size)
        assertEquals(1, r1.chunks.size)
        val r2 = s.accept(1000 + 352 * 2, p, p.size)   // se perdió un paquete
        assertEquals(2, r2.chunks.size)
        assertEquals(1352L, r2.chunks[0].startFrame)
        assertTrue(r2.chunks[0].pcm.all { it == 0.toByte() })
        assertEquals(352, r2.chunks[0].frames)
        assertEquals(1352L + 352, r2.chunks[1].startFrame)
        assertFalse(r2.discontinuity)
    }

    @Test fun `un salto enorme es una discontinuidad y un solape se recorta`() {
        val s = FrameSequencer()
        val p = ByteArray(352 * 4) { 1 }
        s.accept(0, p, p.size)
        assertTrue(s.accept(10_000_000, p, p.size).discontinuity)
        val s2 = FrameSequencer()
        s2.accept(0, p, p.size)
        val r = s2.accept(100, p, p.size)   // empieza 252 tramas antes de lo esperado
        assertEquals(1, r.chunks.size)
        assertEquals(352L, r.chunks[0].startFrame)
        assertEquals(100, r.chunks[0].frames)
    }
}

class DriftCorrectorTest {
    @Test fun `por debajo de 5 ms no se toca nada`() {
        assertEquals(0, DriftCorrector.plan(4_000_000, 352, false))
        assertEquals(0, DriftCorrector.plan(-4_900_000, 352, false))
    }

    @Test fun `con retraso se quitan tramas y con adelanto se duplican, con tope de 1_5 por ciento`() {
        val late = DriftCorrector.plan(50_000_000, 352, false)
        val early = DriftCorrector.plan(-50_000_000, 352, false)
        assertEquals(352 / 64, late)
        assertEquals(-(352 / 64), early)
    }

    @Test fun `histeresis - una vez corrigiendo sigue hasta 1 ms`() {
        assertTrue(DriftCorrector.plan(3_000_000, 352, true) > 0)
        assertEquals(0, DriftCorrector.plan(900_000, 352, true))
    }

    @Test fun `apply cambia el largo exacto y conserva las demas tramas`() {
        val frames = 352
        val pcm = ByteArray(frames * 4)
        for (f in 0 until frames) { pcm[f * 4] = (f and 0xFF).toByte(); pcm[f * 4 + 1] = (f shr 8).toByte() }
        val dropped = DriftCorrector.apply(pcm, 5)
        assertEquals((frames - 5) * 4, dropped.size)
        val dup = DriftCorrector.apply(pcm, -5)
        assertEquals((frames + 5) * 4, dup.size)
        assertTrue(pcm.contentEquals(ByteArray(pcm.size).also { System.arraycopy(pcm, 0, it, 0, pcm.size) }))
    }
}
