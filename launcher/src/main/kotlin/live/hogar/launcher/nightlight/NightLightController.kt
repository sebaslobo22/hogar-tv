package live.hogar.launcher.nightlight

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import java.time.ZoneId

/**
 * Luz nocturna dentro del servicio de accesibilidad (que vive siempre). Reevalúa el horario al
 * arrancar, al cambiar los ajustes, al cambiar hora o zona horaria, justo en cada cambio de
 * ventana y, como red de seguridad, cada 5 minutos. Todo en el hilo principal.
 */
internal class NightLightController(private val service: AccessibilityService) {
    private val main = Handler(Looper.getMainLooper())
    private val prefs: SharedPreferences = NightLightStore.prefs(service)
    private val overlay = NightLightOverlay(service)
    private var started = false

    private val tick = Runnable { refresh() }

    // Debe quedar en un campo: SharedPreferences solo guarda una referencia débil al oyente.
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            refresh()
            // El huso por defecto del proceso se actualiza un instante después del aviso.
            main.postDelayed(tick, 1_500L)
        }
    }

    fun start() {
        if (started) return
        started = true
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        runCatching { service.registerReceiver(timeReceiver, f) }
        refresh()
    }

    fun release() {
        if (!started) return
        started = false
        main.removeCallbacks(tick)
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        runCatching { service.unregisterReceiver(timeReceiver) }
        overlay.release()
    }

    private fun refresh() {
        if (!started) return
        main.removeCallbacks(tick)
        val cfg = NightLightStore.read(prefs)
        val now = System.currentTimeMillis()
        val ev = NightSchedule.evaluate(cfg, now, ZoneId.systemDefault())
        val previewUntil = prefs.getLong(NightLightStore.K_PREVIEW_UNTIL, 0L)
        val preview = now < previewUntil
        val visible = FadeMath.shouldShow(ev.active || preview, cfg.intensity, cfg.dim)
        val fade = if (preview && !ev.active) FadeMath.PREVIEW_FADE_MS else FadeMath.FADE_MS
        overlay.setTarget(visible, NightColor.argb(cfg.intensity, cfg.dim), fade)

        var wait = SAFETY_MS
        ev.nextChange?.let { wait = minOf(wait, (it - now).coerceAtLeast(0L) + 250L) }
        if (preview) wait = minOf(wait, previewUntil - now + 250L)
        main.postDelayed(tick, wait.coerceAtLeast(250L))
    }

    private companion object {
        const val SAFETY_MS = 5 * 60_000L
    }
}
