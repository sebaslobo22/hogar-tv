package live.hogar.launcher.nightlight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class NightLightTest {
    private val caracas = ZoneId.of("America/Caracas")

    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int, zone: ZoneId = caracas) =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    private fun min(h: Int, m: Int) = (h * 60 + m).toDouble()

    // ---- Sol: referencia calculada aparte con la ecuación de salida del Sol (Meeus/Wikipedia,
    // formulación distinta de la NOAA). Miami 21-jun coincide con la tabla publicada (6:31 / 20:16).

    @Test fun caracasJune21() {
        val t = Solar.cityTimes(NightCity.DEFAULT, LocalDate.of(2026, 6, 21))
        assertEquals(min(6, 8), t.sunriseMin!!, 2.0)
        assertEquals(min(18, 53), t.sunsetMin!!, 2.0)
    }

    @Test fun caracasDecember21() {
        val t = Solar.cityTimes(NightCity.DEFAULT, LocalDate.of(2026, 12, 21))
        assertEquals(min(6, 41), t.sunriseMin!!, 2.0)
        assertEquals(min(18, 12), t.sunsetMin!!, 2.0)
    }

    @Test fun miamiJune21UsesDaylightSavingOffset() {
        val t = Solar.cityTimes(NightCity.of("miami"), LocalDate.of(2026, 6, 21))
        assertEquals(min(6, 31), t.sunriseMin!!, 2.0)
        assertEquals(min(20, 16), t.sunsetMin!!, 2.0)
    }

    @Test fun polarDayHasNoSunset() {
        val t = Solar.times(78.0, 15.0, 2026, 6, 21, 120)
        assertNull(t.sunsetMin)
        assertNull(t.sunriseMin)
    }

    @Test fun julianDayKnownValue() {
        assertEquals(2451544.5, Solar.julianDay(2000, 1, 1), 1e-9)
    }

    // ---- Kelvin -> RGB

    @Test fun kelvinToRgb() {
        val w = NightColor.kelvinToRgb(6500.0)
        assertEquals(255, w[0]); assertEquals(254, w[1], 2); assertEquals(250, w[2], 3)
        val warm = NightColor.kelvinToRgb(2700.0)
        assertEquals(255, warm[0]); assertEquals(167, warm[1], 2); assertEquals(87, warm[2], 2)
        assertTrue(NightColor.kelvinToRgb(1900.0)[2] < 20)
    }

    private fun assertEquals(e: Int, a: Int, delta: Int): Unit = assertTrue("$a vs $e", kotlin.math.abs(e - a) <= delta)

    @Test fun kelvinMapsIntensity() {
        assertEquals(6500.0, NightColor.kelvin(0), 1e-9)
        assertEquals(2700.0, NightColor.kelvin(100), 1e-9)
        assertEquals(4600.0, NightColor.kelvin(50), 1e-9)
    }

    @Test fun overlayColorAndAlpha() {
        assertEquals(0, NightColor.argb(0, 0))
        val full = NightColor.argb(100, 0)
        assertEquals(115, (full ushr 24) and 0xFF) // 0,45 · 255
        assertEquals(255, (full shr 16) and 0xFF)  // rojo pleno
        assertEquals(0, full and 0xFF)             // sin azul
        val g = (full shr 8) and 0xFF
        assertTrue("verde ámbar $g", g in 30..90)
        // Más intensidad = más opaco.
        assertTrue(((NightColor.argb(60, 0) ushr 24) and 0xFF) < ((NightColor.argb(80, 0) ushr 24) and 0xFF))
    }

    @Test fun dimOnlyIsBlackAndCaps() {
        val d = NightColor.argb(0, 40)
        assertEquals(102, (d ushr 24) and 0xFF) // 0,40 · 255
        assertEquals(0, d and 0x00FFFFFF)
        assertEquals(NightColor.argb(0, 40), NightColor.argb(0, 99)) // tope de atenuación
        // Con ambos, el negro oscurece el velo: más opaco y con menos rojo.
        val both = NightColor.argb(100, 40)
        assertTrue(((both ushr 24) and 0xFF) > 115)
    }

    // ---- Horario

    private fun fixed(start: Int, end: Int) =
        NightConfig(mode = NightMode.FIXED, startMin = start, endMin = end)

    @Test fun fixedScheduleCrossingMidnight() {
        val c = fixed(20 * 60, 7 * 60)
        assertTrue(NightSchedule.evaluate(c, ms(2026, 11, 1, 21, 0), caracas).active)
        assertTrue(NightSchedule.evaluate(c, ms(2026, 11, 1, 3, 0), caracas).active)
        assertFalse(NightSchedule.evaluate(c, ms(2026, 11, 1, 12, 0), caracas).active)
        assertTrue(NightSchedule.evaluate(c, ms(2026, 11, 1, 20, 0), caracas).active)  // borde inicial incluido
        assertFalse(NightSchedule.evaluate(c, ms(2026, 11, 1, 7, 0), caracas).active)  // borde final excluido
        val at3 = NightSchedule.evaluate(c, ms(2026, 11, 1, 3, 0), caracas)
        assertEquals(ms(2026, 11, 1, 7, 0), at3.activeUntil)
        val noon = NightSchedule.evaluate(c, ms(2026, 11, 1, 12, 0), caracas)
        assertEquals(ms(2026, 11, 1, 20, 0), noon.nextStart)
        assertEquals(ms(2026, 11, 1, 20, 0), noon.nextChange)
    }

    @Test fun fixedScheduleSameDayAndEmpty() {
        val c = fixed(9 * 60, 17 * 60)
        assertTrue(NightSchedule.evaluate(c, ms(2026, 11, 1, 10, 0), caracas).active)
        assertFalse(NightSchedule.evaluate(c, ms(2026, 11, 1, 18, 0), caracas).active)
        assertFalse(NightSchedule.evaluate(fixed(600, 600), ms(2026, 11, 1, 10, 0), caracas).active)
    }

    @Test fun offAndAlways() {
        val off = NightSchedule.evaluate(NightConfig(mode = NightMode.OFF), 0, caracas)
        assertFalse(off.active); assertNull(off.nextChange)
        val on = NightSchedule.evaluate(NightConfig(mode = NightMode.ALWAYS), 0, caracas)
        assertTrue(on.active); assertNull(on.nextChange)
    }

    @Test fun sunScheduleFollowsSunsetAndSunrise() {
        val c = NightConfig(mode = NightMode.SUN)
        assertFalse(NightSchedule.evaluate(c, ms(2026, 6, 21, 12, 0), caracas).active)
        assertFalse(NightSchedule.evaluate(c, ms(2026, 6, 21, 18, 40), caracas).active) // antes de 18:52
        assertTrue(NightSchedule.evaluate(c, ms(2026, 6, 21, 19, 10), caracas).active)
        assertTrue(NightSchedule.evaluate(c, ms(2026, 6, 22, 5, 30), caracas).active)
        assertFalse(NightSchedule.evaluate(c, ms(2026, 6, 22, 6, 30), caracas).active)
        val ev = NightSchedule.evaluate(c, ms(2026, 6, 21, 23, 0), caracas)
        val until = ev.activeUntil!!
        assertEquals(ms(2026, 6, 22, 6, 8).toDouble(), until.toDouble(), 150_000.0)
    }

    @Test fun sunOffsetShiftsBothEdges() {
        val base = NightSchedule.evaluate(NightConfig(mode = NightMode.SUN), ms(2026, 6, 21, 12, 0), caracas).nextStart!!
        val early = NightSchedule.evaluate(NightConfig(mode = NightMode.SUN, offsetMin = -30), ms(2026, 6, 21, 12, 0), caracas).nextStart!!
        assertEquals(30 * 60_000L, base - early)
    }

    @Test fun statusTexts() {
        val c = fixed(20 * 60, 7 * 60)
        assertEquals("Activa hasta 7:00 a. m.", NightSchedule.statusText(c, ms(2026, 11, 1, 23, 0), caracas))
        assertEquals("Se activa a las 8:00 p. m.", NightSchedule.statusText(c, ms(2026, 11, 1, 12, 0), caracas))
        assertEquals("Se activa a la 1:05 p. m.", NightSchedule.statusText(fixed(13 * 60 + 5, 15 * 60), ms(2026, 11, 1, 10, 0), caracas))
        assertEquals("Apagada", NightSchedule.statusText(NightConfig(), 0, caracas))
        assertEquals("12:00 a. m.", NightSchedule.clock12(0))
        assertEquals("12:30 p. m.", NightSchedule.clock12(12 * 60 + 30))
        assertEquals("6:12 a. m.", NightSchedule.clock12(6 * 60 + 12))
    }

    // ---- Fundido

    @Test fun fadeTargets() {
        assertTrue(FadeMath.shouldShow(true, 40, 0))
        assertTrue(FadeMath.shouldShow(true, 0, 10))
        assertFalse(FadeMath.shouldShow(true, 0, 0))   // intensidad 0: sin ventana
        assertFalse(FadeMath.shouldShow(false, 80, 20)) // apagado: sin ventana
        assertEquals(1f, FadeMath.targetAlpha(true), 0f)
        assertEquals(0f, FadeMath.targetAlpha(false), 0f)
        assertEquals(2000L, FadeMath.durationMs(0f, 1f))
        assertEquals(1000L, FadeMath.durationMs(0.5f, 1f))
        assertEquals(0L, FadeMath.durationMs(1f, 1f))
        assertEquals(300L, FadeMath.durationMs(0f, 1f, FadeMath.PREVIEW_FADE_MS))
    }

    @Test fun configSanitizesAndCityFallback() {
        val c = NightConfig(intensity = 500, dim = 90, startMin = -60, endMin = 1500, offsetMin = 99, cityId = "nada").sanitized()
        assertEquals(100, c.intensity); assertEquals(40, c.dim)
        assertEquals(23 * 60, c.startMin); assertEquals(60, c.endMin); assertEquals(30, c.offsetMin)
        assertEquals("caracas", c.city.id)
        assertNotNull(NightCity.of("madrid"))
        assertEquals(NightMode.OFF, NightMode.of("raro"))
    }
}
