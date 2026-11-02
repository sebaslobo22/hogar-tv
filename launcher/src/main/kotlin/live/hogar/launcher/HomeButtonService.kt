package live.hogar.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import live.hogar.core.TvPower
import live.hogar.launcher.nightlight.NightLightController
import live.hogar.launcher.pointer.PointerController

/**
 * Google TV no deja elegir otra pantalla de inicio: su launcher gana siempre. Este servicio
 * abre Hogar cuando se pulsa Inicio y, por si el sistema se adelanta, cuando aparece el
 * inicio de Google TV. No desactiva nada de fábrica; apagar el servicio lo deja como estaba.
 *
 * También aloja el modo puntero (ver [PointerController]) para usar apps solo táctiles con el
 * control remoto. Solo mira el paquete de la ventana en primer plano y si hay teclado en pantalla.
 */
class HomeButtonService : AccessibilityService() {

    private var pointer: PointerController? = null
    private var nightLight: NightLightController? = null
    private val main = Handler(Looper.getMainLooper())

    // Teclas del mando (medido en este control): TV = GUIDE, perfil = PROFILE_SWITCH.
    private var guideConsumed = false
    private var lastKeyMs = 0L
    private var onHogarHome = false
    private val idleCheck = object : Runnable {
        override fun run() {
            checkIdleNowPlaying()
            main.postDelayed(this, IDLE_CHECK_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        pointer?.release()
        nightLight?.release()
        // La luz nocturna se añade antes que el puntero, que así queda por encima.
        nightLight = NightLightController(this).also { it.start() }
        pointer = PointerController(this, STOCK_LAUNCHERS)
        lastKeyMs = SystemClock.uptimeMillis()
        main.removeCallbacks(idleCheck)
        main.postDelayed(idleCheck, IDLE_CHECK_MS)
        instance = this
        installTvHooks()
    }

    /** Ganchos para HomeKit (módulo `homekit`, que no depende del lanzador): ver [TvPower]. */
    private fun installTvHooks() {
        TvPower.sleeper = { sleepTv() }
        TvPower.homer = { openHogar(); true }
        TvPower.backer = { performGlobalAction(GLOBAL_ACTION_BACK) }
        TvPower.pointerKey = { code -> pointer?.inject(code) ?: false }
        TvPower.starter = { intent -> runCatching { startActivity(intent); true }.getOrDefault(false) }
    }

    private fun removeTvHooks() {
        if (instance === this) instance = null
        TvPower.sleeper = null; TvPower.homer = null; TvPower.backer = null
        TvPower.pointerKey = null; TvPower.starter = null
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        lastKeyMs = SystemClock.uptimeMillis()
        when (event.keyCode) {
            KeyEvent.KEYCODE_HOME -> {
                if (event.action == KeyEvent.ACTION_UP) openHogar()
                return true
            }
            // TV: abre "Reproduciendo ahora" si algo suena; si no, la tecla sigue su camino.
            KeyEvent.KEYCODE_GUIDE -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (event.repeatCount == 0) guideConsumed = NowPlayingLink.playingPackage(this) != null
                } else if (event.action == KeyEvent.ACTION_UP) {
                    val c = guideConsumed
                    guideConsumed = false
                    if (c) NowPlayingLink.open(this)
                    return c
                }
                return guideConsumed
            }
            // Perfil: Centro de control de Hogar.
            KeyEvent.KEYCODE_PROFILE_SWITCH -> {
                if (event.action == KeyEvent.ACTION_UP) NowPlayingLink.openControlCenter(this)
                return true
            }
        }
        return pointer?.onKeyEvent(event) ?: false
    }

    /** Salvapantallas de música: música sonando y 3 min sin tocar el mando en el inicio de Hogar. */
    private fun checkIdleNowPlaying() {
        val now = SystemClock.uptimeMillis()
        if (!AutoMode.due(now, lastKeyMs, onHogarHome)) return
        val playing = NowPlayingLink.playingPackage(this)
        if (!AutoMode.allowed(NowPlayingLink.autoSetting(this), playing)) return
        lastKeyMs = now // no repetir mientras se muestra
        NowPlayingLink.open(this, auto = true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return
                trackHome(pkg, event.className?.toString())
                if (pkg in STOCK_LAUNCHERS) openHogar()
                pointer?.onForeground(pkg)
                TvPower.reportForeground(pointer?.currentPackage ?: pkg)
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> pointer?.onWindowsChanged()
        }
    }

    /** Solo la actividad principal de Hogar cuenta como "inicio"; otras pantallas lo apagan. */
    private fun trackHome(pkg: String, cls: String?) {
        if (pkg != packageName) { onHogarHome = false; return }
        if (cls == NowPlayingLink.MAIN_ACTIVITY) onHogarHome = true
        else if (cls != null && cls.endsWith("Activity")) onHogarHome = false
    }

    override fun onInterrupt() {
        pointer?.onInterrupt()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        removeTvHooks()
        main.removeCallbacks(idleCheck)
        pointer?.release()
        pointer = null
        nightLight?.release()
        nightLight = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        removeTvHooks()
        main.removeCallbacks(idleCheck)
        pointer?.release()
        pointer = null
        nightLight?.release()
        nightLight = null
        super.onDestroy()
    }

    private fun openHogar() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(packageName)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        startActivity(intent)
    }

    companion object {
        @Volatile private var instance: HomeButtonService? = null

        /**
         * Apaga la pantalla del TV. En Android TV, `GLOBAL_ACTION_LOCK_SCREEN` (API 28) hace
         * `PowerManager.goToSleep` además de bloquear. Devuelve false si el servicio no está activo.
         */
        @JvmStatic
        fun sleepTv(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) ?: false

        private const val IDLE_CHECK_MS = 20_000L
        private val STOCK_LAUNCHERS = setOf(
            "com.google.android.apps.tv.launcherx",
            "com.google.android.tvlauncher",
            "com.google.android.leanbacklauncher",
        )
    }
}
