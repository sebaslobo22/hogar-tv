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

    /**
     * Flechas y Aceptar para cualquier app: si el modo puntero está activo en la app de turno mueve el
     * puntero; si no, mueve el foco por accesibilidad. Es asíncrono (devuelve true si se aceptó la orden).
     */
    @Volatile var dpadKey: ((Int) -> Boolean)? = null

    /** Lanza una actividad con el contexto del servicio de accesibilidad (puede hacerlo desde segundo plano). */
    @Volatile var starter: ((android.content.Intent) -> Boolean)? = null

    /** Último paquete en primer plano que vio el servicio de accesibilidad. */
    @Volatile var foreground: String? = null
        private set

    /** Última actividad (clase `...Activity`) vista en primer plano, para saber si es "Reproduciendo ahora". */
    @Volatile var foregroundClass: String? = null
        private set

    @Volatile var onForegroundChanged: ((String) -> Unit)? = null

    fun reportForeground(pkg: String, cls: String? = null) {
        val pkgChanged = pkg != foreground
        val prevCls = foregroundClass
        if (cls != null && cls.endsWith("Activity")) foregroundClass = cls
        else if (pkgChanged) foregroundClass = null
        if (!pkgChanged && foregroundClass == prevCls) return
        foreground = pkg
        onForegroundChanged?.invoke(pkg)
    }

    // ---------------------------------------------------------------- luz nocturna

    /** ¿El filtro de luz nocturna está puesto ahora mismo (según horario)? null = aún no se sabe. */
    @Volatile var nightLightActive: Boolean? = null
        private set

    @Volatile var onNightLightChanged: (() -> Unit)? = null

    fun reportNightLight(active: Boolean) {
        if (nightLightActive == active) return
        nightLightActive = active
        onNightLightChanged?.invoke()
    }

    // ---------------------------------------------------------------- botones del mando

    const val BUTTON_BLUE = "blue"
    const val BUTTON_PROFILE = "profile"
    const val BUTTON_TV = "tv"

    /** Pulsación de un botón con evento HomeKit: 0 = simple, 1 = doble, 2 = larga. */
    const val PRESS_SINGLE = 0
    const val PRESS_DOUBLE = 1
    const val PRESS_LONG = 2

    /** HomeKit se anota aquí para recibir las pulsaciones que detecta el servicio de accesibilidad. */
    @Volatile var buttonListener: ((String, Int) -> Unit)? = null

    /** HomeKit dice si algún controlador espera eventos de ese botón (si no, el botón se comporta como siempre). */
    @Volatile var buttonWanted: ((String) -> Boolean)? = null

    fun wantsButton(button: String): Boolean = runCatching { buttonWanted?.invoke(button) == true }.getOrDefault(false)

    fun reportButton(button: String, press: Int) {
        runCatching { buttonListener?.invoke(button, press) }
    }
}
