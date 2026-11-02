package live.hogar.launcher.pointer

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction

/**
 * Acciones sobre el elemento que está bajo el puntero, por accesibilidad (como un lector de
 * pantalla). En este TV Android cancela todos los toques inyectados con dispatchGesture (no hay
 * pantalla táctil), así que el clic y el desplazamiento van por aquí y el gesto queda de respaldo.
 */
internal object NodeActions {

    enum class Dir { UP, DOWN, LEFT, RIGHT }

    /** Pulsa (o mantiene) el elemento accionable más interno bajo (x, y). false si no hay ninguno. */
    fun clickAt(service: AccessibilityService, x: Int, y: Int, long: Boolean): Boolean {
        val target = deepestMatching(service, x, y) { (if (long) it.isLongClickable else it.isClickable) && it.isEnabled }
            ?: return false
        return target.performAction(if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK)
    }

    /** Desplaza el contenedor desplazable más interno que contiene (x, y) en [dir]. */
    fun scrollAt(service: AccessibilityService, x: Int, y: Int, dir: Dir): Boolean {
        val specific = when (dir) {
            Dir.UP -> AccessibilityAction.ACTION_SCROLL_UP
            Dir.DOWN -> AccessibilityAction.ACTION_SCROLL_DOWN
            Dir.LEFT -> AccessibilityAction.ACTION_SCROLL_LEFT
            Dir.RIGHT -> AccessibilityAction.ACTION_SCROLL_RIGHT
        }
        val generic = if (dir == Dir.DOWN || dir == Dir.RIGHT) AccessibilityAction.ACTION_SCROLL_FORWARD
        else AccessibilityAction.ACTION_SCROLL_BACKWARD
        val vertical = dir == Dir.UP || dir == Dir.DOWN
        deepestMatching(service, x, y) { n -> n.isScrollable && n.actionList.any { it.id == specific.id } }
            ?.let { return it.performAction(specific.id) }
        return deepestMatching(service, x, y) { n ->
            n.isScrollable && n.actionList.any { it.id == generic.id } && orientationMatches(n, vertical)
        }?.performAction(generic.id) ?: false
    }

    /** Sin acciones direccionales, adivina la orientación por la forma del contenedor. */
    private fun orientationMatches(n: AccessibilityNodeInfo, vertical: Boolean): Boolean {
        val r = Rect().also { n.getBoundsInScreen(it) }
        return if (vertical) r.height() >= r.width() / 3 else r.width() > r.height()
    }

    /**
     * El nodo más profundo que cumple [ok] y contiene el punto, recorriendo TODAS las ramas (los hijos
     * posteriores, dibujados encima, primero). Así se atraviesan capas vacías a pantalla completa que
     * algunas apps (Apple Music) ponen sobre la interfaz.
     */
    private fun deepestMatching(service: AccessibilityService, x: Int, y: Int, ok: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val roots = buildList {
            service.rootInActiveWindow?.let { add(it) }
            runCatching { service.windows }.getOrNull()?.forEach { w -> w.root?.let { if (it.packageName == firstOrNull()?.packageName) add(it) } }
        }
        val r = Rect()
        fun visit(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            n.getBoundsInScreen(r)
            if (!r.contains(x, y) || !n.isVisibleToUser) return null
            for (i in n.childCount - 1 downTo 0) {
                val c = n.getChild(i) ?: continue
                visit(c)?.let { return it }
            }
            return if (ok(n)) n else null
        }
        for (root in roots) visit(root)?.let { return it }
        return null
    }
}
