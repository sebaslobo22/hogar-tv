package live.hogar.nowplaying

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService

/**
 * Servicio vacío a propósito: tener un NotificationListenerService habilitado es lo que permite
 * a `MediaSessionManager.getActiveSessions(componente)` ver las sesiones de otras apps.
 * No lee ni guarda notificaciones. Es además el dueño del motor de iluminación [live.hogar.nowplaying.light.LightEngine].
 */
class MediaListener : NotificationListenerService() {
    // El motor de la lámpara vive mientras este servicio esté conectado (acceso a notificaciones concedido).
    override fun onListenerConnected() { live.hogar.nowplaying.light.LightEngine.get(this).attach() }
    override fun onListenerDisconnected() { live.hogar.nowplaying.light.LightEngine.get(this).detach() }
    override fun onDestroy() { live.hogar.nowplaying.light.LightEngine.get(this).detach(); super.onDestroy() }
}

object ListenerAccess {
    fun component(ctx: Context) = ComponentName(ctx, MediaListener::class.java)

    /** ¿Está concedido el acceso (Ajustes > Acceso a notificaciones)? */
    fun isGranted(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
        val me = component(ctx)
        return flat.split(':').any {
            val c = ComponentName.unflattenFromString(it)
            c != null && c == me
        }
    }
}

/** Ajuste del modo automático; lo lee también el servicio de accesibilidad del lanzador. */
class NowPlayingPrefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var autoMode: Int
        get() = sp.getInt(KEY_AUTO, AutoModePolicy.DEFAULT)
        set(v) { sp.edit().putInt(KEY_AUTO, v).apply() }

    /** Ya se pidió el permiso de audio (una sola vez). */
    var audioAsked: Boolean
        get() = sp.getBoolean(KEY_AUDIO_ASKED, false)
        set(v) { sp.edit().putBoolean(KEY_AUDIO_ASKED, v).apply() }

    companion object {
        const val FILE = "nowplaying"
        const val KEY_AUDIO_ASKED = "audio_asked"
        const val KEY_AUTO = "auto"
    }
}
