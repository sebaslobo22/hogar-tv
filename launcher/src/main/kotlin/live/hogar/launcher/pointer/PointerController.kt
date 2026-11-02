package live.hogar.launcher.pointer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.KeyEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import live.hogar.launcher.R

/**
 * Modo puntero: ratón virtual para apps solo táctiles. Esquema de teclas en [PointerMath].
 *
 * Solo trabaja mientras se aprieta una flecha (bucle de cuadros) o mientras el aviso de
 * ocultar está pendiente; en reposo no corre nada. Toda la clase vive en el hilo principal.
 */
internal class PointerController(
    private val service: AccessibilityService,
    private val stockLaunchers: Set<String>,
) {
    private val main = Handler(Looper.getMainLooper())
    private val choreographer = Choreographer.getInstance()
    private val overlay = PointerOverlay(service)
    private val prefs = PointerPrefs(service)
    private val ownPackage = service.packageName

    private var screenW = 1920
    private var screenH = 1080
    private var x = 960f
    private var y = 540f

    private var currentPkg: String? = null
    private var appEnabled = false
    private var keyboardVisible = false
    private val defaults = HashMap<String, Boolean>()

    // Flechas
    private var up = false
    private var down = false
    private var left = false
    private var right = false
    private var holdStartMs = 0L
    private var lastFrameNanos = 0L
    private var looping = false
    private var edgeSinceMs = -1L
    private var lastSwipeMs = -1_000_000L

    // OK
    private var selectDown = false
    private var selectDownMs = 0L
    private var selectConsumed = false
    private var selectWakeOnly = false
    private var toggleFired = false

    private var lastActivityMs = 0L
    private var idlePending = false
    private var gestureBusy = false

    private val isActive: Boolean get() = appEnabled && !keyboardVisible

    /** App en primer plano según el puntero (ya filtrada de capas y teclado). */
    val currentPackage: String? get() = currentPkg

    /**
     * Pulsa una tecla del mando por software (HomeKit): flechas mueven el puntero y CENTER hace clic.
     * Devuelve false si el modo puntero no está activo en la app de turno.
     */
    fun inject(keyCode: Int): Boolean {
        if (!isActive) return false
        main.post {
            val t = SystemClock.uptimeMillis()
            onKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0))
            main.postDelayed({
                val t2 = SystemClock.uptimeMillis()
                onKeyEvent(KeyEvent(t, t2, KeyEvent.ACTION_UP, keyCode, 0))
            }, if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER) 60L else 140L)
        }
        return true
    }

    // ---------------------------------------------------------------- eventos de ventanas

    fun onForeground(hint: String) {
        // El paquete del evento puede ser una capa (el propio puntero, avisos del sistema): manda la
        // app de la ventana de aplicación activa.
        val pkg = activeAppPackage() ?: hint
        if (PointerPolicy.isTransient(pkg, imePackages())) return
        if (pkg == currentPkg) return
        currentPkg = pkg
        val enabled = resolveEnabled(pkg)
        android.util.Log.i("HogarPointer", "primer plano=$pkg puntero=$enabled categorias=${categoriesOf(pkg)}")
        applyAppState(enabled)
    }

    fun onWindowsChanged() {
        val visible = keyboardOpen()
        if (visible == keyboardVisible) return
        keyboardVisible = visible
        if (visible) suspendPointer()
    }

    fun onInterrupt() = clearDirections()

    fun release() {
        clearDirections()
        main.removeCallbacksAndMessages(null)
        overlay.release()
    }

    // ---------------------------------------------------------------- teclas

    fun onKeyEvent(e: KeyEvent): Boolean = logKey(e) && when (e.keyCode) {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> onDirection(e)
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> onSelect(e)
        else -> false
    }

    private fun logKey(e: KeyEvent): Boolean {
        return true
    }

    private fun onDirection(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_UP) {
            val had = setDirection(e.keyCode, false)
            if (!up && !down && !left && !right) stopLoop()
            return had && isActive
        }
        if (!isActive) return false
        if (e.action != KeyEvent.ACTION_DOWN) return true
        if (isDirectionHeld(e.keyCode)) return true // repetición: ya está en marcha

        refreshBounds()
        val wasHidden = !overlay.isShown
        if (wasHidden) overlay.show(x, y)
        val none = !up && !down && !left && !right
        setDirection(e.keyCode, true)
        if (none) holdStartMs = SystemClock.uptimeMillis()
        // Primer toque tras ocultarse: solo reaparece en su última posición.
        if (!wasHidden) moveBy(dirX() * PointerTuning.STEP_PX, dirY() * PointerTuning.STEP_PX)
        touchIdle()
        startLoop()
        return true
    }

    private fun onSelect(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN) {
            if (selectDown) return selectConsumed // repetición
            selectDown = true
            selectDownMs = SystemClock.uptimeMillis()
            toggleFired = false
            selectConsumed = isActive
            selectWakeOnly = false
            if (selectConsumed && !overlay.isShown) {
                refreshBounds()
                overlay.show(x, y)
                selectWakeOnly = true
            }
            if (selectConsumed) touchIdle()
            if (canToggle()) main.postDelayed(toggleRunnable, PointerTuning.TOGGLE_MS)
            return selectConsumed
        }
        if (e.action != KeyEvent.ACTION_UP || !selectDown) return false
        selectDown = false
        main.removeCallbacks(toggleRunnable)
        val consumed = selectConsumed
        if (consumed && !toggleFired && !selectWakeOnly && isActive) {
            when (PointerMath.classifyPress(SystemClock.uptimeMillis() - selectDownMs)) {
                PressKind.TAP -> click(PointerTuning.TAP_DURATION_MS)
                PressKind.LONG_PRESS -> click(PointerTuning.LONG_PRESS_DURATION_MS)
                PressKind.TOGGLE -> Unit // ya lo resolvió toggleRunnable
            }
            touchIdle()
        }
        return consumed
    }

    private val toggleRunnable = Runnable {
        val pkg = currentPkg ?: return@Runnable
        toggleFired = true
        val enable = !appEnabled
        prefs.set(pkg, enable)
        clearDirections()
        applyAppState(enable)
        overlay.showToast(
            service.getString(if (enable) R.string.pointer_toast_on else R.string.pointer_toast_off),
        )
    }

    private fun canToggle(): Boolean {
        val pkg = currentPkg ?: return false
        return !keyboardVisible &&
            !PointerPolicy.isExcluded(pkg, ownPackage, stockLaunchers, imePackages())
    }

    // ---------------------------------------------------------------- movimiento

    private fun startLoop() {
        if (looping) return
        looping = true
        lastFrameNanos = 0L
        choreographer.postFrameCallback(frameCallback)
    }

    private fun stopLoop() {
        looping = false
        choreographer.removeFrameCallback(frameCallback)
        edgeSinceMs = -1L
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!looping) return
            val dx = dirX()
            val dy = dirY()
            val now = SystemClock.uptimeMillis()
            if ((dx == 0 && dy == 0) || !isActive || now - holdStartMs > PointerTuning.MAX_HOLD_MS) {
                clearDirections()
                return
            }
            val dt = if (lastFrameNanos == 0L) 0.016f
            else ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
            lastFrameNanos = frameTimeNanos

            val step = PointerMath.speedPxPerSec(now - holdStartMs) * dt
            if (step > 0f) moveBy(dx * step, dy * step)
            lastActivityMs = now

            val edge = PointerMath.pushedEdge(x, y, screenW, screenH, dx, dy)
            if (edge == null) {
                edgeSinceMs = -1L
            } else {
                if (edgeSinceMs < 0) edgeSinceMs = now
                if (PointerMath.shouldEdgeScroll(edgeSinceMs, now, lastSwipeMs) && !gestureBusy) {
                    lastSwipeMs = now
                    edgeScroll(dx, dy)
                }
            }
            choreographer.postFrameCallback(this)
        }
    }

    private fun moveBy(dx: Float, dy: Float) {
        x = (x + dx).coerceIn(0f, (screenW - 1).toFloat())
        y = (y + dy).coerceIn(0f, (screenH - 1).toFloat())
        overlay.moveTo(x, y)
    }

    private fun dirX() = (if (right) 1 else 0) - (if (left) 1 else 0)
    private fun dirY() = (if (down) 1 else 0) - (if (up) 1 else 0)

    private fun isDirectionHeld(code: Int) = when (code) {
        KeyEvent.KEYCODE_DPAD_UP -> up
        KeyEvent.KEYCODE_DPAD_DOWN -> down
        KeyEvent.KEYCODE_DPAD_LEFT -> left
        else -> right
    }

    /** Devuelve si la tecla estaba apretada antes del cambio. */
    private fun setDirection(code: Int, value: Boolean): Boolean {
        val before = isDirectionHeld(code)
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> up = value
            KeyEvent.KEYCODE_DPAD_DOWN -> down = value
            KeyEvent.KEYCODE_DPAD_LEFT -> left = value
            else -> right = value
        }
        return before
    }

    private fun clearDirections() {
        up = false; down = false; left = false; right = false
        stopLoop()
    }

    // ---------------------------------------------------------------- gestos

    private fun click(durationMs: Long) {
        overlay.pulse()
        val px = x
        val py = y
        // Android cancela un gesto inyectado si llega otra señal de entrada mientras dura, y el mando IR
        // suelta pulsos tardíos al soltar OK: se envía el toque cuando el mando ya calló.
        val long = durationMs >= PointerTuning.LONG_PRESS_DURATION_MS
        // Primero, la acción de accesibilidad sobre el elemento (funciona sin pantalla táctil).
        val done = runCatching { NodeActions.clickAt(service, px.toInt(), py.toInt(), long) }.getOrDefault(false)
        android.util.Log.i("HogarPointer", "clic por accesibilidad=$done largo=$long")
        if (done) return
        main.postDelayed({
            val path = Path().apply { moveTo(px, py) }
            dispatch(GestureDescription.StrokeDescription(path, 0, durationMs), busy = false)
        }, PointerTuning.TAP_DELAY_MS)
    }

    /** Desplaza por accesibilidad la lista bajo el puntero; si no hay ninguna, prueba el gesto. */
    private fun edgeScroll(dx: Int, dy: Int) {
        val dir = when {
            dy > 0 -> NodeActions.Dir.DOWN
            dy < 0 -> NodeActions.Dir.UP
            dx > 0 -> NodeActions.Dir.RIGHT
            else -> NodeActions.Dir.LEFT
        }
        // En el borde el puntero suele estar sobre barras fijas: se busca la lista un poco hacia dentro.
        val vertical = dir == NodeActions.Dir.UP || dir == NodeActions.Dir.DOWN
        val px = if (vertical) x.toInt() else x.toInt().coerceIn(screenW / 8, screenW * 7 / 8)
        val py = if (vertical) (screenH / 2) else y.toInt()
        val done = runCatching { NodeActions.scrollAt(service, px, py, dir) }.getOrDefault(false)
        if (!done) {
            val edge = PointerMath.pushedEdge(x, y, screenW, screenH, dx, dy) ?: return
            swipe(PointerMath.swipeFor(edge, screenW, screenH, x, y))
        }
    }

    private fun swipe(s: Swipe) {
        val path = Path().apply { moveTo(s.x0, s.y0); lineTo(s.x1, s.y1) }
        dispatch(GestureDescription.StrokeDescription(path, 0, PointerTuning.SWIPE_MS), busy = true)
    }

    private fun dispatch(stroke: GestureDescription.StrokeDescription, busy: Boolean) {
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        if (busy) gestureBusy = true
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                android.util.Log.i("HogarPointer", "gesto completado")
                if (busy) gestureBusy = false
            }
            override fun onCancelled(g: GestureDescription?) {
                android.util.Log.w("HogarPointer", "gesto cancelado")
                if (busy) gestureBusy = false
            }
        }
        val ok = service.dispatchGesture(gesture, callback, main)
        android.util.Log.i("HogarPointer", "gesto enviado=$ok en ($x, $y)")
        if (!ok && busy) gestureBusy = false
    }

    // ---------------------------------------------------------------- estado

    private fun applyAppState(enabled: Boolean) {
        appEnabled = enabled
        clearDirections()
        if (!enabled) {
            main.removeCallbacks(idleRunnable)
            idlePending = false
            overlay.removeNow()
            return
        }
        keyboardVisible = keyboardOpen()
        if (!keyboardVisible) {
            refreshBounds()
            overlay.show(x, y)
            touchIdle()
        }
    }

    private fun suspendPointer() {
        clearDirections()
        main.removeCallbacks(idleRunnable)
        idlePending = false
        overlay.removeNow()
    }

    private fun touchIdle() {
        lastActivityMs = SystemClock.uptimeMillis()
        if (!idlePending) {
            idlePending = true
            main.postDelayed(idleRunnable, PointerTuning.IDLE_HIDE_MS)
        }
    }

    private val idleRunnable: Runnable = Runnable {
        val remaining = PointerTuning.IDLE_HIDE_MS - (SystemClock.uptimeMillis() - lastActivityMs)
        if (remaining > 0) {
            main.postDelayed(idleRunnable, remaining)
        } else {
            idlePending = false
            overlay.hide()
        }
    }

    private fun resolveEnabled(pkg: String): Boolean {
        val default = defaults.getOrPut(pkg) {
            PointerPolicy.defaultEnabled(pkg, ownPackage, stockLaunchers, imePackages(), categoriesOf(pkg))
        }
        if (PointerPolicy.isExcluded(pkg, ownPackage, stockLaunchers, imePackages())) return false
        return PointerPolicy.effective(default, prefs.get(pkg))
    }

    private fun categoriesOf(pkg: String): Set<String> {
        val pm = service.packageManager
        val out = HashSet<String>(2)
        for (cat in listOf(PointerPolicy.CAT_LAUNCHER, PointerPolicy.CAT_LEANBACK)) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(cat).setPackage(pkg)
            if (runCatching { pm.queryIntentActivities(intent, 0).isNotEmpty() }.getOrDefault(false)) out.add(cat)
        }
        return out
    }

    private fun imePackages(): Set<String> = runCatching {
        val imm = service.getSystemService(InputMethodManager::class.java)
        imm.enabledInputMethodList.mapTo(HashSet()) { it.packageName }
    }.getOrDefault(emptySet())

    private fun keyboardOpen(): Boolean = runCatching {
        service.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
    }.getOrDefault(false)

    private fun activeAppPackage(): String? = runCatching {
        val win = service.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }
            ?: service.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused }
        val root = win?.root
        val pkg = root?.packageName?.toString()
        @Suppress("DEPRECATION") root?.recycle()
        pkg
    }.getOrNull()

    /** Tras reiniciar el servicio no llega ningún evento hasta el siguiente cambio: ver quién está delante. */
    private fun syncInitial() {
        runCatching {
            val win = service.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }
            val root = win?.root
            val pkg = root?.packageName?.toString()
            @Suppress("DEPRECATION") root?.recycle()
            if (pkg != null) onForeground(pkg)
        }
    }

    private fun refreshBounds() {
        val m = service.resources.displayMetrics
        screenW = m.widthPixels
        screenH = m.heightPixels
        x = x.coerceIn(0f, (screenW - 1).toFloat())
        y = y.coerceIn(0f, (screenH - 1).toFloat())
    }

    init {
        refreshBounds()
        x = screenW / 2f
        y = screenH / 2f
        syncInitial()
    }
}
