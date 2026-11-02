package live.hogar.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
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
    private var nav: RemoteNav? = null
    private val main = Handler(Looper.getMainLooper())

    private val ticker = object : PressTicker {
        override fun post(r: Runnable, delayMs: Long) { main.postDelayed(r, delayMs) }
        override fun cancel(r: Runnable) { main.removeCallbacks(r) }
    }

    // Botones del mando con evento HomeKit (interruptores programables): simple / doble / larga.
    // Solo se vigilan si algún controlador (Casa) espera sus eventos; si no, todo funciona como siempre.
    private val blueButton = PressDetector(ticker) { TvPower.reportButton(TvPower.BUTTON_BLUE, it.code) }
    private val profileButton = PressDetector(ticker) {
        // La pulsación simple conserva su acción de siempre; doble y larga van a HomeKit.
        if (it == Press.SINGLE) NowPlayingLink.openControlCenter(this) else TvPower.reportButton(TvPower.BUTTON_PROFILE, it.code)
    }
    private val tvButton = PressDetector(ticker) {
        // La simple sigue abriendo "Reproduciendo ahora" (lo resuelve onKeyEvent); solo la larga va a HomeKit.
        if (it == Press.LONG) TvPower.reportButton(TvPower.BUTTON_TV, it.code)
    }

    // Teclas del mando (medido en este control): TV = GUIDE, perfil = PROFILE_SWITCH, azul = KEYCODE_BML
    // (código del fabricante: el TV lo reportó con ese nombre; se resuelve en ejecución) o PROG_BLUE.
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
        nav = RemoteNav(this)
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
        TvPower.dpadKey = { code -> dpad(code) }
        TvPower.starter = { intent -> runCatching { startActivity(intent); true }.getOrDefault(false) }
    }

    private fun removeTvHooks() {
        if (instance === this) instance = null
        TvPower.sleeper = null; TvPower.homer = null; TvPower.backer = null
        TvPower.pointerKey = null; TvPower.starter = null; TvPower.dpadKey = null
        blueButton.reset(); profileButton.reset(); tvButton.reset()
    }

    /**
     * Flecha/Aceptar desde HomeKit: con el modo puntero activo en la app de turno mueve el puntero;
     * si no, mueve el foco por accesibilidad. Asíncrono: la orden se acepta siempre.
     */
    private fun dpad(code: Int): Boolean {
        main.post {
            try {
                val usedPointer = pointer?.inject(code) == true
                if (!usedPointer) nav?.key(code)
                Log.d(TAG_HK, "dpad tecla=$code puntero=$usedPointer")
            } catch (t: Throwable) {
                Log.w(TAG_HK, "dpad: ${t.message}")
            }
        }
        return true
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
                    if (event.repeatCount == 0) {
                        guideConsumed = NowPlayingLink.playingPackage(this) != null
                        tvButton.wantsLong = TvPower.wantsButton(TvPower.BUTTON_TV)
                        tvButton.onDown()
                    }
                } else if (event.action == KeyEvent.ACTION_UP) {
                    val wasLong = tvButton.onUp()   // pulsación larga: ya fue a HomeKit, no abre nada
                    val c = guideConsumed
                    guideConsumed = false
                    if (c && !wasLong) NowPlayingLink.open(this)
                    return c
                }
                return guideConsumed
            }
            // Perfil: Centro de control de Hogar (pulsación simple); doble y larga, a HomeKit si hay quien escuche.
            KeyEvent.KEYCODE_PROFILE_SWITCH -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (event.repeatCount == 0) {
                        val wanted = TvPower.wantsButton(TvPower.BUTTON_PROFILE)
                        profileButton.wantsDouble = wanted
                        profileButton.wantsLong = wanted
                        profileButton.onDown()
                    }
                } else if (event.action == KeyEvent.ACTION_UP) profileButton.onUp()
                return true
            }
            // Azul: no hace nada por sí solo; solo se atiende si HomeKit espera sus eventos.
            KeyEvent.KEYCODE_PROG_BLUE, VENDOR_BLUE -> {
                if (!blueButton.pressed && !TvPower.wantsButton(TvPower.BUTTON_BLUE)) return false
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (event.repeatCount == 0) {
                        blueButton.wantsDouble = true
                        blueButton.wantsLong = true
                        blueButton.onDown()
                    }
                } else if (event.action == KeyEvent.ACTION_UP) blueButton.onUp()
                return true
            }
            else -> logUnknownKey(event)
        }
        return pointer?.onKeyEvent(event) ?: false
    }

    /** Para descubrir qué código manda cada botón del mando: una línea por pulsación, solo teclas poco comunes. */
    private fun logUnknownKey(e: KeyEvent) {
        if (e.action != KeyEvent.ACTION_DOWN || e.repeatCount != 0) return
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK -> Unit
            else -> Log.d(TAG_HK, "tecla del mando=${e.keyCode} (${KeyEvent.keyCodeToString(e.keyCode)})")
        }
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
                val fg = pointer?.currentPackage ?: pkg
                TvPower.reportForeground(fg, if (fg == pkg) event.className?.toString() else null)
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
        nav = null
        nightLight?.release()
        nightLight = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        removeTvHooks()
        main.removeCallbacks(idleCheck)
        pointer?.release()
        pointer = null
        nav = null
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
        /** Código de la tecla azul de los mandos MStar/Skyworth ("KEYCODE_BML"); -1 si este Android no la conoce. */
        private val VENDOR_BLUE: Int = KeyEvent.keyCodeFromString("KEYCODE_BML").takeIf { it > 0 } ?: -1
        @Volatile private var instance: HomeButtonService? = null

        /**
         * Apaga la pantalla del TV. En Android TV, `GLOBAL_ACTION_LOCK_SCREEN` (API 28) hace
         * `PowerManager.goToSleep` además de bloquear. Devuelve false si el servicio no está activo.
         */
        @JvmStatic
        fun sleepTv(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) ?: false

        private const val TAG_HK = "HogarHK"
        private const val IDLE_CHECK_MS = 20_000L
        private val STOCK_LAUNCHERS = setOf(
            "com.google.android.apps.tv.launcherx",
            "com.google.android.tvlauncher",
            "com.google.android.leanbacklauncher",
        )
    }
}
