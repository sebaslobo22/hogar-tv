package live.hogar.launcher.nightlight

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.tan

/* Lógica pura de la luz nocturna (sin Android): fácil de probar en la JVM. */

internal enum class NightMode(val id: String, val label: String) {
    OFF("off", "Apagado"),
    ALWAYS("always", "Siempre encendido"),
    FIXED("fixed", "Horario fijo"),
    SUN("sun", "Del atardecer al amanecer");

    companion object {
        fun of(id: String?): NightMode = entries.firstOrNull { it.id == id } ?: OFF
    }
}

internal data class NightCity(val id: String, val label: String, val lat: Double, val lon: Double, val zoneId: String) {
    companion object {
        val ALL = listOf(
            NightCity("caracas", "Caracas", 10.4806, -66.9036, "America/Caracas"),
            NightCity("maracaibo", "Maracaibo", 10.6544, -71.6406, "America/Caracas"),
            NightCity("valencia", "Valencia", 10.1620, -68.0077, "America/Caracas"),
            NightCity("bogota", "Bogotá", 4.7110, -74.0721, "America/Bogota"),
            NightCity("madrid", "Madrid", 40.4168, -3.7038, "Europe/Madrid"),
            NightCity("cdmx", "Ciudad de México", 19.4326, -99.1332, "America/Mexico_City"),
            NightCity("miami", "Miami", 25.7617, -80.1918, "America/New_York"),
        )
        val DEFAULT = ALL[0]
        fun of(id: String?): NightCity = ALL.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** Ajustes completos. Horas en minutos desde la medianoche. */
internal data class NightConfig(
    val mode: NightMode = NightMode.OFF,
    val intensity: Int = 50,
    val dim: Int = 0,
    val startMin: Int = 20 * 60,
    val endMin: Int = 7 * 60,
    val cityId: String = NightCity.DEFAULT.id,
    val offsetMin: Int = 0,
) {
    val city: NightCity get() = NightCity.of(cityId)

    fun sanitized() = copy(
        intensity = intensity.coerceIn(0, 100),
        dim = dim.coerceIn(0, NightColor.MAX_DIM_PERCENT),
        startMin = Math.floorMod(startMin, 1440),
        endMin = Math.floorMod(endMin, 1440),
        offsetMin = offsetMin.coerceIn(-30, 30),
    )
}

/** Color temperatura -> RGB y color único del filtro. */
internal object NightColor {
    const val MAX_TINT_ALPHA = 0.45
    const val MAX_DIM_PERCENT = 40
    const val K_OFF = 6500.0
    const val K_MAX = 2700.0

    /** Temperatura de color objetivo para una intensidad 0..100. */
    fun kelvin(intensity: Int): Double = K_OFF - (K_OFF - K_MAX) * intensity.coerceIn(0, 100) / 100.0

    /** Aproximación de Tanner Helland (blanco del cuerpo negro a [kelvin]). */
    fun kelvinToRgb(kelvin: Double): IntArray {
        val t = kelvin.coerceIn(1000.0, 40000.0) / 100.0
        val r = if (t <= 66) 255.0 else 329.698727446 * (t - 60).pow(-0.1332047592)
        val g = if (t <= 66) 99.4708025861 * ln(t) - 161.1195681661 else 288.1221695283 * (t - 60).pow(-0.0755148492)
        val b = when {
            t >= 66 -> 255.0
            t <= 19 -> 0.0
            else -> 138.5177312231 * ln(t - 10) - 305.0447927307
        }
        return intArrayOf(c(r), c(g), c(b))
    }

    fun tintAlpha(intensity: Int): Double = MAX_TINT_ALPHA * intensity.coerceIn(0, 100) / 100.0

    /**
     * Color del velo: se resuelve para que un blanco, con velo de alfa [tintAlpha], se vea como
     * el blanco de [kelvin]: C = (objetivo - 255·(1-a)) / a. Es la contribución "inversa": el azul
     * se recorta a 0 (el velo no puede quitar más) y queda un ámbar estable.
     */
    fun tintRgb(intensity: Int): IntArray {
        val a = tintAlpha(intensity)
        if (a <= 0.0) return intArrayOf(255, 147, 41)
        val target = kelvinToRgb(kelvin(intensity))
        return IntArray(3) { i -> c((target[i] - 255.0 * (1 - a)) / a) }
    }

    /** ARGB único: velo ámbar (intensidad) con un negro encima (atenuar), compuestos en un solo color. */
    fun argb(intensity: Int, dimPercent: Int): Int {
        val a = tintAlpha(intensity)
        val d = dimPercent.coerceIn(0, MAX_DIM_PERCENT) / 100.0
        val outA = d + a * (1 - d)
        if (outA <= 0.0) return 0
        val rgb = tintRgb(intensity)
        val k = a * (1 - d) / outA
        return (c(outA * 255.0) shl 24) or (c(rgb[0] * k) shl 16) or (c(rgb[1] * k) shl 8) or c(rgb[2] * k)
    }

    private fun c(v: Double) = v.roundToInt().coerceIn(0, 255)
}

/** Lógica del fundido. */
internal object FadeMath {
    const val FADE_MS = 2000L
    const val PREVIEW_FADE_MS = 300L

    /** ¿Hay que tener la ventana puesta? Con intensidad 0 y sin atenuar no queda ninguna. */
    fun shouldShow(active: Boolean, intensity: Int, dim: Int): Boolean = active && (intensity > 0 || dim > 0)

    fun targetAlpha(visible: Boolean): Float = if (visible) 1f else 0f

    /** Duración proporcional al tramo que falta por recorrer (un fundido a medias dura la mitad). */
    fun durationMs(from: Float, to: Float, fullMs: Long = FADE_MS): Long =
        (fullMs * kotlin.math.abs(to - from)).roundToLong()
}

/** Salida y puesta del Sol (algoritmo de la NOAA, hoja de cálculo "Solar Calculator"). */
internal object Solar {
    /** Minutos desde la medianoche local (en el huso dado); null si no hay salida/puesta ese día. */
    data class Times(val sunriseMin: Double?, val sunsetMin: Double?)

    private fun rad(d: Double) = d * PI / 180.0
    private fun deg(r: Double) = r * 180.0 / PI

    fun julianDay(year: Int, month: Int, day: Int): Double {
        var y = year
        var m = month
        if (m <= 2) { y -= 1; m += 12 }
        val a = floor(y / 100.0)
        val b = 2 - a + floor(a / 4)
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + day + b - 1524.5
    }

    private fun cent(jd: Double) = (jd - 2451545.0) / 36525.0

    private fun eqTime(t: Double): Double {
        val eps = obliqCorr(t)
        val l0 = rad(meanLong(t))
        val e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val m = rad(357.52911 + t * (35999.05029 - 0.0001537 * t))
        var y = tan(rad(eps) / 2.0)
        y *= y
        val et = y * sin(2 * l0) - 2 * e * sin(m) + 4 * e * y * sin(m) * cos(2 * l0) -
            0.5 * y * y * sin(4 * l0) - 1.25 * e * e * sin(2 * m)
        return deg(et) * 4.0
    }

    private fun meanLong(t: Double): Double {
        val l = 280.46646 + t * (36000.76983 + t * 0.0003032)
        return ((l % 360) + 360) % 360
    }

    private fun obliqCorr(t: Double): Double {
        val e0 = 23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
        val omega = 125.04 - 1934.136 * t
        return e0 + 0.00256 * cos(rad(omega))
    }

    private fun declination(t: Double): Double {
        val m = rad(357.52911 + t * (35999.05029 - 0.0001537 * t))
        val c = sin(m) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            sin(2 * m) * (0.019993 - 0.000101 * t) + sin(3 * m) * 0.000289
        val trueLong = meanLong(t) + c
        val omega = 125.04 - 1934.136 * t
        val lambda = trueLong - 0.00569 - 0.00478 * sin(rad(omega))
        return deg(asin(sin(rad(obliqCorr(t))) * sin(rad(lambda))))
    }

    /** Minutos UTC del evento (con la fecha juliana a 0 h UT); null en día polar. */
    private fun eventUtc(rise: Boolean, jd: Double, lat: Double, lon: Double): Double? {
        val t = cent(jd)
        val decl = rad(declination(t))
        val latR = rad(lat)
        val cosHa = cos(rad(90.833)) / (cos(latR) * cos(decl)) - tan(latR) * tan(decl)
        if (cosHa > 1.0 || cosHa < -1.0) return null
        var ha = acos(cosHa)
        if (!rise) ha = -ha
        val delta = lon + deg(ha)
        return 720.0 - 4.0 * delta - eqTime(t)
    }

    fun times(lat: Double, lon: Double, year: Int, month: Int, day: Int, utcOffsetMin: Int): Times {
        val jd = julianDay(year, month, day)
        fun one(rise: Boolean): Double? {
            val first = eventUtc(rise, jd, lat, lon) ?: return null
            // Segunda pasada con la hora aproximada del evento (como hace la NOAA).
            val refined = eventUtc(rise, jd + first / 1440.0, lat, lon) ?: first
            return refined + utcOffsetMin
        }
        return Times(one(true), one(false))
    }

    /** Salida/puesta de [date] en la ciudad (huso propio, con su horario de verano si lo hay). */
    fun cityTimes(city: NightCity, date: LocalDate): Times {
        val zone = ZoneId.of(city.zoneId)
        val offset = zone.rules.getOffset(date.atTime(12, 0).atZone(zone).toInstant()).totalSeconds / 60
        return times(city.lat, city.lon, date.year, date.monthValue, date.dayOfMonth, offset)
    }
}

/** Qué toca ahora y cuándo cambia. Todo en milisegundos de época. */
internal object NightSchedule {
    data class Window(val start: Long, val end: Long)

    /**
     * [activeUntil]: fin de la ventana en curso. [nextStart]: inicio de la siguiente.
     * [nextChange]: el próximo borde de cualquier tipo (null si no habrá más: apagado/siempre).
     */
    data class Eval(val active: Boolean, val activeUntil: Long?, val nextStart: Long?, val nextChange: Long?)

    private fun at(date: LocalDate, minute: Int, zone: ZoneId): Long =
        date.atTime(LocalTime.of(minute / 60, minute % 60)).atZone(zone).toInstant().toEpochMilli()

    private fun fixedWindows(cfg: NightConfig, today: LocalDate, zone: ZoneId): List<Window> {
        if (cfg.startMin == cfg.endMin) return emptyList() // ventana vacía: nunca
        return (-1L..2L).map { i ->
            val d = today.plusDays(i)
            val endDate = if (cfg.endMin > cfg.startMin) d else d.plusDays(1)
            Window(at(d, cfg.startMin, zone), at(endDate, cfg.endMin, zone))
        }
    }

    private fun sunWindows(cfg: NightConfig, nowMs: Long): List<Window> {
        val city = cfg.city
        val zone = ZoneId.of(city.zoneId)
        val today = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        fun epoch(date: LocalDate, minutes: Double) =
            date.atStartOfDay(zone).toInstant().toEpochMilli() + (minutes * 60_000.0).roundToLong()
        val off = cfg.offsetMin * 60_000L
        val out = ArrayList<Window>()
        for (i in -1L..2L) {
            val d = today.plusDays(i)
            val set = Solar.cityTimes(city, d).sunsetMin ?: continue
            val rise = Solar.cityTimes(city, d.plusDays(1)).sunriseMin ?: continue
            out += Window(epoch(d, set) + off, epoch(d.plusDays(1), rise) + off)
        }
        return out
    }

    fun windows(cfg: NightConfig, nowMs: Long, zone: ZoneId): List<Window> = when (cfg.mode) {
        NightMode.FIXED -> fixedWindows(cfg, java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate(), zone)
        NightMode.SUN -> sunWindows(cfg, nowMs)
        else -> emptyList()
    }

    fun evaluate(cfg: NightConfig, nowMs: Long, zone: ZoneId): Eval {
        when (cfg.mode) {
            NightMode.OFF -> return Eval(false, null, null, null)
            NightMode.ALWAYS -> return Eval(true, null, null, null)
            else -> Unit
        }
        val ws = windows(cfg, nowMs, zone)
        val cur = ws.firstOrNull { nowMs >= it.start && nowMs < it.end }
        val nextStart = ws.filter { it.start > nowMs }.minOfOrNull { it.start }
        val nextChange = ws.flatMap { listOf(it.start, it.end) }.filter { it > nowMs }.minOrNull()
        return Eval(cur != null, cur?.end, nextStart, nextChange)
    }

    /** "6:12 a. m." */
    fun clock12(minuteOfDay: Int): String {
        val m = Math.floorMod(minuteOfDay, 1440)
        val h = m / 60
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "$h12:${(m % 60).toString().padStart(2, '0')} ${if (h < 12) "a. m." else "p. m."}"
    }

    fun clock12(epochMs: Long, zone: ZoneId): String {
        val t = java.time.Instant.ofEpochMilli(epochMs).atZone(zone)
        return clock12(t.hour * 60 + t.minute)
    }

    /** Texto de estado para el Centro de control. */
    fun statusText(cfg: NightConfig, nowMs: Long, zone: ZoneId): String {
        val ev = evaluate(cfg, nowMs, zone)
        return when (cfg.mode) {
            NightMode.OFF -> "Apagada"
            NightMode.ALWAYS -> "Siempre activa"
            else -> when {
                ev.active && ev.activeUntil != null -> "Activa hasta ${clock12(ev.activeUntil, zone)}"
                ev.nextStart != null -> {
                    val t = java.time.Instant.ofEpochMilli(ev.nextStart).atZone(zone)
                    val today = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
                    val day = if (t.toLocalDate() != today) "mañana " else ""
                    val art = if (t.hour % 12 == 1) "a la" else "a las"
                    "Se activa $day$art ${clock12(ev.nextStart, zone)}"
                }
                else -> "Sin horario"
            }
        }
    }
}
