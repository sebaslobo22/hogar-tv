package live.hogar.nowplaying.light

import android.content.Context

/** Modo de iluminación y lámpara elegida (persistentes). */
class LightPrefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("nowplaying_light", Context.MODE_PRIVATE)

    /** 0 = apagado, 1 = paleta, 2 = paleta + ritmo. */
    var mode: Int
        get() = sp.getInt("mode", MODE_OFF).coerceIn(0, 2)
        set(v) { sp.edit().putInt("mode", v).apply() }

    var segments: Boolean
        get() = sp.getBoolean("segments", false)
        set(v) { sp.edit().putBoolean("segments", v).apply() }

    var lamp: LampInfo?
        get() {
            val ip = sp.getString("ip", null) ?: return null
            val dev = sp.getString("device", null) ?: return null
            return LampInfo(ip, dev, sp.getString("sku", "") ?: "")
        }
        set(v) {
            sp.edit().apply {
                if (v == null) { remove("ip"); remove("device"); remove("sku") }
                else { putString("ip", v.ip); putString("device", v.device); putString("sku", v.sku) }
            }.apply()
        }

    companion object {
        const val MODE_OFF = 0
        const val MODE_PALETTE = 1
        const val MODE_RHYTHM = 2
    }
}
