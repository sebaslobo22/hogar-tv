package live.hogar.core

/**
 * Puente entre módulos sin dependencia directa: el servicio de accesibilidad del lanzador
 * rellena estos ganchos y HomeKit (u otro) los usa. Todo es opcional: si el servicio no está
 * activo, los ganchos son null y quien los llama debe tolerarlo.
 */
object TvPower {
    /** Apaga la pantalla del TV (lo pone el servicio de accesibilidad). Devuelve si se pudo pedir. */
    @Volatile var sleeper: (() -> Boolean)? = null

    /** Lleva al inicio de Hogar. */
    @Volatile var homer: (() -> Boolean)? = null

    /** Equivale a pulsar Atrás. */
    @Volatile var backer: (() -> Boolean)? = null

    /**
     * Mueve/pulsa el puntero de Hogar con una tecla del mando (`KeyEvent.KEYCODE_DPAD_*` o
     * `KEYCODE_DPAD_CENTER`). Devuelve false si el modo puntero no está activo.
     */
    @Volatile var pointerKey: ((Int) -> Boolean)? = null

    /** Lanza una actividad con el contexto del servicio de accesibilidad (puede hacerlo desde segundo plano). */
    @Volatile var starter: ((android.content.Intent) -> Boolean)? = null

    /** Último paquete en primer plano que vio el servicio de accesibilidad. */
    @Volatile var foreground: String? = null
        private set

    @Volatile var onForegroundChanged: ((String) -> Unit)? = null

    fun reportForeground(pkg: String) {
        if (pkg == foreground) return
        foreground = pkg
        onForegroundChanged?.invoke(pkg)
    }
}
