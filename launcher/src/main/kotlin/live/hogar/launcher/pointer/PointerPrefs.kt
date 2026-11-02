package live.hogar.launcher.pointer

import android.content.Context

/** Ajuste del puntero por app (true = encendido, false = apagado, ausente = automático). */
internal class PointerPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("pointer_mode", Context.MODE_PRIVATE)

    fun get(pkg: String): Boolean? = if (prefs.contains(pkg)) prefs.getBoolean(pkg, false) else null

    fun set(pkg: String, enabled: Boolean) {
        prefs.edit().putBoolean(pkg, enabled).apply()
    }
}
