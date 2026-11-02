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
import android.util.Log
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
        // Flechas y Aceptar: puntero si está activo; si no, foco por accesibilidad (lo decide el servicio).
        is RemoteKeys.Action.Dpad -> (TvPower.dpadKey?.invoke(a.keyCode) ?: TvPower.pointerKey?.invoke(a.keyCode) ?: false).also {
            Log.d(TAG, "mando tecla=${a.keyCode} aceptada=$it")
        }
        RemoteKeys.Action.Back -> (TvPower.backer?.invoke() ?: false).also { Log.d(TAG, "mando atrás=$it") }
        RemoteKeys.Action.Home -> (TvPower.homer?.invoke() ?: false).also { Log.d(TAG, "mando inicio=$it") }
        RemoteKeys.Action.Info -> openNowPlaying().also { Log.d(TAG, "mando info=$it") }
        RemoteKeys.Action.None -> false
    }

    private fun openNowPlaying(): Boolean = start(
        Intent().setClassName(ctx.packageName, NOW_PLAYING_ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
    )

    // ---------------------------------------------------------------- luz nocturna (SharedPreferences "nightlight")

    private val night get() = ctx.applicationContext.getSharedPreferences(NIGHT_FILE, Context.MODE_PRIVATE)
    private val lightSp get() = ctx.applicationContext.getSharedPreferences(LIGHT_FILE, Context.MODE_PRIVATE)
    private val playingSp get() = ctx.applicationContext.getSharedPreferences(PLAYING_FILE, Context.MODE_PRIVATE)

    override fun nightLightOn(): Boolean =
        Mappings.nightOn(night.getString("mode", null), TvPower.nightLightActive)

    /** Encender = "Siempre encendido"; apagar = "Apagado". El último modo con horario se conserva para el interruptor del Centro de control. */
    override fun setNightLightOn(on: Boolean) {
        night.edit().putString("mode", if (on) Mappings.NIGHT_ALWAYS else Mappings.NIGHT_OFF).apply()
        Log.i(TAG, "luz nocturna=${if (on) "encendida" else "apagada"}")
    }

    override fun nightLightIntensity(): Int = night.getInt("intensity", 50)

    override fun setNightLightIntensity(v: Int) {
        night.edit().putInt("intensity", v.coerceIn(0, 100)).apply()
        Log.i(TAG, "luz nocturna intensidad=$v")
    }

    // ---------------------------------------------------------------- luz musical (SharedPreferences "nowplaying_light")

    override fun lightMode(): Int = lightSp.getInt("mode", 0).coerceIn(0, 2)

    override fun setLightMode(mode: Int) {
        lightSp.edit().putInt("mode", mode.coerceIn(0, 2)).apply()
        Log.i(TAG, "luz musical modo=$mode")
    }

    // ---------------------------------------------------------------- Reproduciendo ahora y salvapantallas

    override fun nowPlayingOpen(): Boolean =
        TvPower.foreground == ctx.packageName && TvPower.foregroundClass == NOW_PLAYING_ACTIVITY

    override fun setNowPlayingOpen(open: Boolean) {
        if (open) { openNowPlaying() }
        else if (nowPlayingOpen()) { TvPower.homer?.invoke() }
        Log.i(TAG, "reproduciendo ahora=$open")
    }

    override fun screensaverOn(): Boolean = Mappings.saverOn(playingSp.getInt("auto", 0))

    override fun setScreensaverOn(on: Boolean) {
        playingSp.edit().putInt("auto", Mappings.afterSaver(playingSp.getInt("auto", 0), on)).apply()
        Log.i(TAG, "salvapantallas=$on")
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
        const val TAG = "HogarHK"
        const val NOW_PLAYING_ACTIVITY = "live.hogar.nowplaying.NowPlayingActivity"
        // Mismos archivos que usan el lanzador (NightLightStore), nowplaying (LightPrefs, NowPlayingPrefs).
        const val NIGHT_FILE = "nightlight"
        const val LIGHT_FILE = "nowplaying_light"
        const val PLAYING_FILE = "nowplaying"
    }
}

/** Entradas que se ofrecen en Casa: apps con icono de TV (las conocidas primero) y una HDMI. */
internal object InputScanner {
    private const val MAX_INPUTS = 30
    private const val MAX_HDMI = 3

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

    private const val OWN_PACKAGE = "live.hogar"

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
        // Hogar siempre está, aunque el sistema no lo liste como app de TV.
        if (OWN_PACKAGE !in labels) labels[OWN_PACKAGE] = "Hogar"
        val known = KNOWN.keys.filter { it in labels }.distinctBy { KNOWN[it] }
        val others = labels.keys.filter { it !in KNOWN }.sortedBy { labels[it]!!.lowercase() }
        // Entradas HDMI que informe el sistema (hasta MAX_HDMI), para volver a una consola o decodificador.
        val hdmi = ArrayList<InputDef>()
        runCatching {
            val tv = ctx.getSystemService(Context.TV_INPUT_SERVICE) as TvInputManager
            tv.tvInputList.filter { it.type == TvInputInfo.TYPE_HDMI }.take(MAX_HDMI).forEach { info ->
                val key = AndroidTvBackend.HDMI_PREFIX + info.id
                val label = info.loadLabel(ctx)?.toString()?.ifBlank { null } ?: "HDMI"
                val id = store.identifierFor(key)
                if (id <= Iid.MAX_INPUT_IDENTIFIER) hdmi += InputDef(id, key, label, InputDef.TYPE_HDMI)
            }
        }
        for (pkg in known + others) {
            if (out.size >= MAX_INPUTS - hdmi.size) break
            val id = store.identifierFor(pkg)
            if (id > Iid.MAX_INPUT_IDENTIFIER) continue // su iid chocaría con los servicios añadidos
            out += InputDef(id, pkg, labels[pkg]!!, InputDef.TYPE_APPLICATION)
        }
        out += hdmi
        return out
    }
}
