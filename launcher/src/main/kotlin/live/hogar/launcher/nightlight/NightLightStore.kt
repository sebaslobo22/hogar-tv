package live.hogar.launcher.nightlight

import android.content.Context
import android.content.SharedPreferences

/** Ajustes de la luz nocturna en SharedPreferences "nightlight" (misma app: el servicio los escucha). */
internal object NightLightStore {
    const val FILE = "nightlight"
    const val K_MODE = "mode"
    const val K_LAST_MODE = "last_mode"
    const val K_INTENSITY = "intensity"
    const val K_DIM = "dim"
    const val K_START = "start_min"
    const val K_END = "end_min"
    const val K_CITY = "city"
    const val K_OFFSET = "offset_min"
    /** Marca de tiempo (ms de reloj) hasta la que se fuerza la vista previa; así nunca queda pegada. */
    const val K_PREVIEW_UNTIL = "preview_until"
    const val PREVIEW_MS = 12_000L

    fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun read(sp: SharedPreferences): NightConfig {
        val d = NightConfig()
        return NightConfig(
            mode = NightMode.of(sp.getString(K_MODE, null)),
            intensity = sp.getInt(K_INTENSITY, d.intensity),
            dim = sp.getInt(K_DIM, d.dim),
            startMin = sp.getInt(K_START, d.startMin),
            endMin = sp.getInt(K_END, d.endMin),
            cityId = sp.getString(K_CITY, d.cityId) ?: d.cityId,
            offsetMin = sp.getInt(K_OFFSET, d.offsetMin),
        ).sanitized()
    }

    fun write(sp: SharedPreferences, c: NightConfig) {
        val s = c.sanitized()
        sp.edit()
            .putString(K_MODE, s.mode.id)
            .apply { if (s.mode != NightMode.OFF) putString(K_LAST_MODE, s.mode.id) }
            .putInt(K_INTENSITY, s.intensity)
            .putInt(K_DIM, s.dim)
            .putInt(K_START, s.startMin)
            .putInt(K_END, s.endMin)
            .putString(K_CITY, s.cityId)
            .putInt(K_OFFSET, s.offsetMin)
            .apply()
    }

    /** Interruptor del Centro de control: apaga, o vuelve al último modo (por defecto, siempre encendido). */
    fun toggle(sp: SharedPreferences) {
        val c = read(sp)
        val next = if (c.mode != NightMode.OFF) NightMode.OFF
        else NightMode.of(sp.getString(K_LAST_MODE, null)).takeIf { it != NightMode.OFF } ?: NightMode.ALWAYS
        write(sp, c.copy(mode = next))
    }

    fun touchPreview(sp: SharedPreferences, on: Boolean) {
        sp.edit().putLong(K_PREVIEW_UNTIL, if (on) System.currentTimeMillis() + PREVIEW_MS else 0L).apply()
    }
}
