package live.hogar.launcher

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction

/**
 * Navegación con el foco para las flechas y Aceptar del mando del iPhone (HomeKit), en cualquier app:
 * toma el nodo con foco de entrada, calcula el siguiente con `focusSearch` y le da el foco; si el
 * sistema no devuelve ninguno, busca el enfocable más cercano por geometría ([FocusMath]).
 * Solo trabaja cuando llega una orden (nada corre en reposo). Siempre en el hilo principal.
 */
internal class RemoteNav(private val service: AccessibilityService) {

    /** Flecha o Aceptar (`KeyEvent.KEYCODE_DPAD_*`). Devuelve si hizo algo. */
    fun key(code: Int): Boolean {
        val dir = FocusDir.ofKeyCode(code)
        val done = try {
            if (dir != null) move(dir) else select()
        } catch (t: Throwable) {
            Log.w(TAG, "navegación: ${t.message}")
            false
        }
        Log.d(TAG, "nav tecla=$code hecho=$done")
        return done
    }

    private fun move(dir: FocusDir): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val cur = currentFocus(root)
        if (cur == null) {
            // Nada tiene el foco: se pone en el primer elemento (arriba a la izquierda).
            val first = focusables(root).minWithOrNull(compareBy({ boundsOf(it).top }, { boundsOf(it).left })) ?: return false
            return giveFocus(first)
        }
        val next = runCatching { cur.focusSearch(dir.viewDirection) }.getOrNull()
        if (next != null && next != cur && next.isVisibleToUser && giveFocus(next)) return true

        // Respaldo geométrico.
        val all = focusables(root).filter { it != cur }
        val idx = FocusMath.nearest(boundsOf(cur), all.map { boundsOf(it) }, dir)
        if (idx != null && giveFocus(all[idx])) return true

        // Último recurso: desplazar la lista que contiene el elemento (puede haber más contenido sin enfocar).
        return scrollAncestor(cur, dir)
    }

    private fun select(): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val cur = currentFocus(root) ?: return false
        var n: AccessibilityNodeInfo? = cur
        var depth = 0
        while (n != null && depth < MAX_CLIMB) {
            if (n.isClickable && n.isEnabled && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = n.parent
            depth++
        }
        return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun currentFocus(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)

    private fun giveFocus(n: AccessibilityNodeInfo): Boolean =
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS) || n.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)

    private fun boundsOf(n: AccessibilityNodeInfo): Box {
        val r = Rect()
        n.getBoundsInScreen(r)
        return Box(r.left, r.top, r.right, r.bottom)
    }

    /** Nodos visibles, activos y enfocables del árbol (con tope para no recorrer interfaces enormes). */
    private fun focusables(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        var visited = 0
        fun visit(n: AccessibilityNodeInfo) {
            if (visited++ > MAX_NODES || !n.isVisibleToUser) return
            if (n.isFocusable && n.isEnabled && !n.isScrollable) out += n
            for (i in 0 until n.childCount) n.getChild(i)?.let { visit(it) }
        }
        visit(root)
        return out
    }

    private fun scrollAncestor(from: AccessibilityNodeInfo, dir: FocusDir): Boolean {
        val specific = when (dir) {
            FocusDir.UP -> AccessibilityAction.ACTION_SCROLL_UP
            FocusDir.DOWN -> AccessibilityAction.ACTION_SCROLL_DOWN
            FocusDir.LEFT -> AccessibilityAction.ACTION_SCROLL_LEFT
            FocusDir.RIGHT -> AccessibilityAction.ACTION_SCROLL_RIGHT
        }
        var n: AccessibilityNodeInfo? = from.parent
        var depth = 0
        while (n != null && depth < MAX_CLIMB * 2) {
            if (n.isScrollable && n.actionList.any { it.id == specific.id } && n.performAction(specific.id)) return true
            n = n.parent
            depth++
        }
        return false
    }

    private companion object {
        const val TAG = "HogarHK"
        const val MAX_NODES = 1500
        const val MAX_CLIMB = 8
    }
}
