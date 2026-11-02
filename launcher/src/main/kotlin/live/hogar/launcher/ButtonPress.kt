package live.hogar.launcher

/** Pulsación detectada en un botón del mando: [code] coincide con ProgrammableSwitchEvent (0 simple, 1 doble, 2 larga). */
internal enum class Press(val code: Int) { SINGLE(0), DOUBLE(1), LONG(2) }

/** Temporizador mínimo (Handler en el servicio; falso en las pruebas). */
internal interface PressTicker {
    fun post(r: Runnable, delayMs: Long)
    fun cancel(r: Runnable)
}

/**
 * Distingue pulsación simple, doble y larga de un botón. Solo retrasa la pulsación simple (hasta
 * [DOUBLE_MS]) cuando [wantsDouble] está activo; si no, la simple sale en el mismo instante de soltar.
 * La larga sale a los [LONG_MS] aunque el botón siga apretado. Todo en el hilo principal.
 */
internal class PressDetector(private val ticker: PressTicker, private val onPress: (Press) -> Unit) {
    var wantsDouble = false
    var wantsLong = false

    /** El botón está apretado ahora mismo. */
    var pressed = false
        private set

    private var longFired = false
    private var awaitingSecond = false

    private val longTask = Runnable {
        if (pressed) { longFired = true; onPress(Press.LONG) }
    }
    private val singleTask = Runnable {
        awaitingSecond = false
        onPress(Press.SINGLE)
    }

    /** Primera bajada (las repeticiones del mando no se pasan aquí). */
    fun onDown() {
        if (pressed) return
        pressed = true
        longFired = false
        if (wantsLong) ticker.post(longTask, LONG_MS)
    }

    /** Suelta el botón. Devuelve true si esa pulsación fue larga y ya se informó (quien llama no debe actuar). */
    fun onUp(): Boolean {
        pressed = false
        ticker.cancel(longTask)
        if (longFired) { longFired = false; return true }
        when {
            awaitingSecond -> { awaitingSecond = false; ticker.cancel(singleTask); onPress(Press.DOUBLE) }
            wantsDouble -> { awaitingSecond = true; ticker.post(singleTask, DOUBLE_MS) }
            else -> onPress(Press.SINGLE)
        }
        return false
    }

    /** Descarta lo pendiente (al desconectar el servicio). */
    fun reset() {
        pressed = false; longFired = false; awaitingSecond = false
        ticker.cancel(longTask); ticker.cancel(singleTask)
    }

    companion object {
        const val LONG_MS = 600L
        const val DOUBLE_MS = 300L
    }
}
