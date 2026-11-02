package live.hogar.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState

/**
 * Puente con el módulo `nowplaying` por nombre de clase (el lanzador no depende de él).
 * El permiso de escucha lo concede `adb shell cmd notification allow_listener ...`.
 */
internal object NowPlayingLink {
    const val ACTIVITY = "live.hogar.nowplaying.NowPlayingActivity"
    const val LISTENER = "live.hogar.nowplaying.MediaListener"
    const val EXTRA_AUTO = "auto"
    const val EXTRA_OPEN_CONTROL_CENTER = "open_control_center"
    const val MAIN_ACTIVITY = "live.hogar.app.MainActivity"
    private const val PREFS = "nowplaying"
    private const val KEY_AUTO = "auto"

    /** Paquete de la primera sesión que está sonando, o null (nada suena / sin permiso). */
    fun playingPackage(ctx: Context): String? = try {
        val m = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        m.getActiveSessions(ComponentName(ctx.packageName, LISTENER))
            .firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }?.packageName
    } catch (_: Exception) { null }

    fun open(ctx: Context, auto: Boolean = false) {
        val i = Intent().setClassName(ctx.packageName, ACTIVITY)
            .putExtra(EXTRA_AUTO, auto)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching { ctx.startActivity(i) }
    }

    fun openControlCenter(ctx: Context) {
        val i = Intent().setClassName(ctx.packageName, MAIN_ACTIVITY)
            .putExtra(EXTRA_OPEN_CONTROL_CENTER, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching { ctx.startActivity(i) }
    }

    fun autoSetting(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_AUTO, AutoMode.DEFAULT)
}

/** Reglas del salvapantallas de música (espejo de `AutoModePolicy` en nowplaying). */
internal object AutoMode {
    const val DEFAULT = 0
    const val ALWAYS = 1
    const val NEVER = 2
    const val IDLE_MS = 3 * 60_000L

    private val MUSIC_APPS = setOf("com.apple.android.music", "com.spotify.music", "com.google.android.apps.youtube.music", "live.hogar")

    fun allowed(setting: Int, playingPackage: String?): Boolean = when (setting) {
        ALWAYS -> playingPackage != null
        NEVER -> false
        else -> playingPackage != null && playingPackage in MUSIC_APPS
    }

    /** ¿Toca abrir el salvapantallas? Solo en el inicio de Hogar, tras [IDLE_MS] sin pulsar nada. */
    fun due(nowMs: Long, lastKeyMs: Long, onHome: Boolean): Boolean = onHome && nowMs - lastKeyMs >= IDLE_MS
}
