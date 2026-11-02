package live.hogar.launcher.pointer

/**
 * Lógica pura del modo puntero (sin Android, probada en JVM).
 *
 * ESQUEMA DE TECLAS (con el puntero activo en la app en primer plano):
 *  - Flechas: mueven el puntero. Un toque = 18 px; mantenida, acelera hasta ~1400 px/s en 0,8 s.
 *  - OK corto (< 450 ms): toque en el puntero.
 *  - OK largo (450 ms – 1,5 s): pulsación larga en el puntero (se envía al soltar).
 *  - OK mantenido 1,5 s: activa o desactiva el puntero SOLO para esa app (se recuerda) y
 *    avisa con un aviso de cristal. Funciona también con el puntero apagado (la pulsación
 *    pasa a la app sin tocarla).
 *  - Flechas contra un borde: desplazan la lista (deslizamiento de 35 % de la pantalla).
 *  - Atrás, Inicio, volumen, silencio, ajustes, entrada y demás: pasan al sistema.
 */
internal object PointerTuning {
    /** Espera tras soltar OK antes de tocar (ver PointerController.click). */
    const val TAP_DELAY_MS = 140L

    const val STEP_PX = 18f
    const val HOLD_DELAY_MS = 150L
    const val RAMP_MS = 800L
    const val MIN_SPEED = 300f
    const val MAX_SPEED = 1400f

    const val TAP_MAX_MS = 450L
    const val TOGGLE_MS = 1500L
    const val TAP_DURATION_MS = 50L
    const val LONG_PRESS_DURATION_MS = 700L

    const val IDLE_HIDE_MS = 8000L
    const val MAX_HOLD_MS = 15_000L

    const val EDGE_DWELL_MS = 200L
    const val SWIPE_MS = 220L
    const val SWIPE_COOLDOWN_MS = 340L
    const val SWIPE_FRACTION = 0.35f
}

internal enum class PressKind { TAP, LONG_PRESS, TOGGLE }

internal enum class Edge { UP, DOWN, LEFT, RIGHT }

internal data class Swipe(val x0: Float, val y0: Float, val x1: Float, val y1: Float)

internal object PointerMath {

    /** Velocidad (px/s) tras [heldMs] con la flecha apretada: 0 durante el retardo, luego rampa. */
    fun speedPxPerSec(heldMs: Long): Float {
        if (heldMs < PointerTuning.HOLD_DELAY_MS) return 0f
        val t = ((heldMs - PointerTuning.HOLD_DELAY_MS).toFloat() / PointerTuning.RAMP_MS).coerceIn(0f, 1f)
        return PointerTuning.MIN_SPEED + (PointerTuning.MAX_SPEED - PointerTuning.MIN_SPEED) * t * t
    }

    fun classifyPress(heldMs: Long): PressKind = when {
        heldMs >= PointerTuning.TOGGLE_MS -> PressKind.TOGGLE
        heldMs >= PointerTuning.TAP_MAX_MS -> PressKind.LONG_PRESS
        else -> PressKind.TAP
    }

    /** Borde contra el que se empuja: el puntero ya está en él y la flecha apunta hacia fuera. */
    fun pushedEdge(x: Float, y: Float, w: Int, h: Int, dirX: Int, dirY: Int): Edge? {
        val eps = 0.5f
        return when {
            dirY < 0 && y <= eps -> Edge.UP
            dirY > 0 && y >= h - 1 - eps -> Edge.DOWN
            dirX < 0 && x <= eps -> Edge.LEFT
            dirX > 0 && x >= w - 1 - eps -> Edge.RIGHT
            else -> null
        }
    }

    /**
     * ¿Toca lanzar otro deslizamiento? [edgeSinceMs] < 0 = no está en el borde.
     * Exige una espera en el borde y una pausa entre deslizamientos para no solaparlos.
     */
    fun shouldEdgeScroll(edgeSinceMs: Long, nowMs: Long, lastSwipeMs: Long): Boolean {
        if (edgeSinceMs < 0) return false
        if (nowMs - edgeSinceMs < PointerTuning.EDGE_DWELL_MS) return false
        return nowMs - lastSwipeMs >= PointerTuning.SWIPE_COOLDOWN_MS
    }

    /** Dedo que mueve el contenido en sentido contrario al borde empujado (el puntero queda fijo). */
    fun swipeFor(edge: Edge, w: Int, h: Int, x: Float, y: Float): Swipe {
        val half = PointerTuning.SWIPE_FRACTION / 2f
        val cx = x.coerceIn(8f, w - 8f)
        val cy = y.coerceIn(8f, h - 8f)
        return when (edge) {
            Edge.DOWN -> Swipe(cx, h * (0.5f + half), cx, h * (0.5f - half))
            Edge.UP -> Swipe(cx, h * (0.5f - half), cx, h * (0.5f + half))
            Edge.RIGHT -> Swipe(w * (0.5f + half), cy, w * (0.5f - half), cy)
            Edge.LEFT -> Swipe(w * (0.5f - half), cy, w * (0.5f + half), cy)
        }
    }
}

/** Decide en qué apps se enciende el puntero. Sin Android: recibe las categorías ya consultadas. */
internal object PointerPolicy {
    const val CAT_LAUNCHER = "android.intent.category.LAUNCHER"
    const val CAT_LEANBACK = "android.intent.category.LEANBACK_LAUNCHER"

    private val SYSTEM_PACKAGES = setOf("android", "com.android.systemui")

    /** App táctil: tiene icono de teléfono (LAUNCHER) y ninguno de TV (LEANBACK_LAUNCHER). */
    fun isTouchOnly(categories: Set<String>): Boolean =
        CAT_LAUNCHER in categories && CAT_LEANBACK !in categories

    fun isOwn(pkg: String, ownPackage: String): Boolean =
        pkg == ownPackage || pkg.startsWith("$ownPackage.")

    /** Ventanas que no cambian la app en primer plano (barra del sistema, teclado). */
    fun isTransient(pkg: String, imePackages: Set<String>): Boolean =
        pkg in SYSTEM_PACKAGES || pkg in imePackages

    /** Apps donde el puntero nunca se ofrece: Hogar, el inicio de fábrica y el sistema. */
    fun isExcluded(pkg: String, ownPackage: String, stockLaunchers: Set<String>, imePackages: Set<String>): Boolean =
        isOwn(pkg, ownPackage) || pkg in stockLaunchers || isTransient(pkg, imePackages)

    fun defaultEnabled(
        pkg: String,
        ownPackage: String,
        stockLaunchers: Set<String>,
        imePackages: Set<String>,
        categories: Set<String>,
    ): Boolean = !isExcluded(pkg, ownPackage, stockLaunchers, imePackages) && isTouchOnly(categories)

    /** El ajuste guardado por el usuario manda sobre la heurística. */
    fun effective(default: Boolean, override: Boolean?): Boolean = override ?: default
}
