package live.hogar.homekit

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import live.hogar.core.TvPower

/** El TV real: pantalla, apps, volumen y mando, vía servicios de Android y los ganchos de [TvPower]. */
internal class AndroidTvBackend(private val ctx: Context) : TvBackend {
    private val power = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())

    override fun isAwake(): Boolean = power.isInteractive

    override fun sleep(): Boolean = TvPower.sleeper?.invoke() ?: false

    override fun wake() {
        // Una cerradura de pantalla completa con ACQUIRE_CAUSES_WAKEUP es lo único que enciende
        // la pantalla desde segundo plano sin una Activity; está en desuso pero funciona en Android TV.
        @Suppress("DEPRECATION")
        runCatching {
            val flags = PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE
            power.newWakeLock(flags, "hogar:homekit-wake").apply { setReferenceCounted(false); acquire(5_000L) }
        }
        // Y se vuelve al inicio de Hogar una vez encendida.
        main.postDelayed({ TvPower.homer?.invoke() }, 700)
    }

    override fun foregroundKey(): String? = TvPower.foreground

    override fun launch(key: String): Boolean {
        val intent = if (key.startsWith(HDMI_PREFIX)) {
            Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(key.removePrefix(HDMI_PREFIX)))
        } else {
            val pm = ctx.packageManager
            pm.getLeanbackLaunchIntentForPackage(key) ?: pm.getLaunchIntentForPackage(key)
        } ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return start(intent)
    }

    private fun start(intent: Intent): Boolean {
        TvPower.starter?.let { if (it.invoke(intent)) return true }
        return runCatching { ctx.startActivity(intent); true }.getOrDefault(false)
    }

    override fun remoteKey(code: Int): Boolean = when (val a = RemoteKeys.actionFor(code)) {
        is RemoteKeys.Action.Media -> {
            val t = SystemClock.uptimeMillis()
            runCatching {
                audio.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, a.keyCode, 0))
                audio.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, a.keyCode, 0))
            }.isSuccess
        }
        // Flechas y Aceptar: solo cuando el modo puntero de Hogar está activo.
        is RemoteKeys.Action.Pointer -> TvPower.pointerKey?.invoke(a.keyCode) ?: false
        RemoteKeys.Action.Back -> TvPower.backer?.invoke() ?: false
        RemoteKeys.Action.Home -> TvPower.homer?.invoke() ?: false
        RemoteKeys.Action.Info -> start(
            Intent().setClassName(ctx.packageName, "live.hogar.nowplaying.NowPlayingActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        RemoteKeys.Action.None -> false
    }

    private val stream = AudioManager.STREAM_MUSIC

    override fun volume(): Int {
        val max = audio.getStreamMaxVolume(stream)
        return if (max <= 0) 0 else Math.round(audio.getStreamVolume(stream) * 100f / max)
    }

    override fun setVolume(percent: Int) {
        val max = audio.getStreamMaxVolume(stream)
        runCatching { audio.setStreamVolume(stream, Math.round(percent.coerceIn(0, 100) * max / 100f), 0) }
    }

    override fun isMuted(): Boolean = audio.isStreamMute(stream)

    override fun setMuted(muted: Boolean) {
        runCatching { audio.adjustStreamVolume(stream, if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0) }
    }

    override fun volumeStep(up: Boolean) {
        runCatching {
            audio.adjustStreamVolume(stream, if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
        }
    }

    override fun openSettings() {
        start(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        const val HDMI_PREFIX = "hdmi:"
    }
}

/** Entradas que se ofrecen en Casa: apps con icono de TV (las conocidas primero) y una HDMI. */
internal object InputScanner {
    private const val MAX_INPUTS = 15

    // Orden de preferencia de las apps conocidas; el valor es el nombre que se muestra.
    private val KNOWN = linkedMapOf(
        "live.hogar" to "Hogar",
        "com.netflix.ninja" to "Netflix",
        "com.google.android.youtube.tv" to "YouTube",
        "com.amazon.amazonvideo.livingroom" to "Prime Video",
        "com.disney.disneyplus" to "Disney+",
        "com.wbd.stream" to "HBO Max",
        "com.hbo.hbonow" to "HBO Max",
        "com.spotify.tv.android" to "Spotify",
        "com.apple.android.music" to "Apple Music",
        "com.apple.atve.androidtv.appletv" to "Apple TV",
        "com.google.android.youtube.tvmusic" to "YouTube Music",
    )

    // Pantallas del sistema que no son "entradas" para el usuario.
    private val EXCLUDED_PREFIXES = listOf(
        "com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx", "com.google.android.leanbacklauncher",
        "com.android.settings", "com.google.android.tvsettings", "com.android.tv.settings", "com.google.android.katniss",
        "com.google.android.apps.tv.setup", "com.android.vending", "com.google.android.packageinstaller",
    )

    fun scan(ctx: Context, store: HomeKitStore): List<InputDef> {
        val out = ArrayList<InputDef>()
        val pm = ctx.packageManager
        val apps = try {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), PackageManager.MATCH_ALL)
        } catch (e: Exception) { emptyList() }
        val labels = LinkedHashMap<String, String>()
        for (ri in apps) {
            val pkg = ri.activityInfo?.packageName ?: continue
            if (EXCLUDED_PREFIXES.any { pkg.startsWith(it) }) continue
            if (pkg in labels) continue
            labels[pkg] = KNOWN[pkg] ?: runCatching { ri.loadLabel(pm).toString() }.getOrDefault(pkg).ifBlank { pkg }
        }
        val known = KNOWN.keys.filter { it in labels }.distinctBy { KNOWN[it] }
        val others = labels.keys.filter { it !in KNOWN }.sortedBy { labels[it]!!.lowercase() }
        for (pkg in known + others) {
            if (out.size >= MAX_INPUTS - 1) break
            out += InputDef(store.identifierFor(pkg), pkg, labels[pkg]!!, InputDef.TYPE_APPLICATION)
        }
        // Una entrada HDMI (la primera que informe el sistema), para volver a una consola o decodificador.
        runCatching {
            val tv = ctx.getSystemService(Context.TV_INPUT_SERVICE) as TvInputManager
            tv.tvInputList.filter { it.type == TvInputInfo.TYPE_HDMI }.take(1).forEach { info ->
                val key = AndroidTvBackend.HDMI_PREFIX + info.id
                val label = info.loadLabel(ctx)?.toString()?.ifBlank { null } ?: "HDMI"
                out += InputDef(store.identifierFor(key), key, label, InputDef.TYPE_HDMI)
            }
        }
        return out
    }
}
