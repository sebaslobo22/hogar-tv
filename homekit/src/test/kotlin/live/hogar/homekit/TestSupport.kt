package live.hogar.homekit

internal fun hex(s: String): ByteArray = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/** Televisión de mentira: registra lo que se le pide. */
internal class FakeTv : TvBackend {
    var awake = true
    var foreground: String? = null
    var vol = 40
    var mutedNow = false
    val calls = ArrayList<String>()
    var launchResult = true

    override fun isAwake() = awake
    override fun sleep(): Boolean { calls += "sleep"; awake = false; return true }
    override fun wake() { calls += "wake"; awake = true }
    override fun foregroundKey() = foreground
    override fun launch(key: String): Boolean { calls += "launch:$key"; return launchResult }
    override fun remoteKey(code: Int): Boolean { calls += "key:$code"; return true }
    override fun volume() = vol
    override fun setVolume(percent: Int) { calls += "vol:$percent"; vol = percent }
    override fun isMuted() = mutedNow
    override fun setMuted(muted: Boolean) { calls += "mute:$muted"; mutedNow = muted }
    override fun volumeStep(up: Boolean) { calls += if (up) "up" else "down" }
    override fun openSettings() { calls += "settings" }

    var nightOn = false
    var nightIntensity = 50
    var light = 0
    var nowPlaying = false
    var saver = true
    override fun nightLightOn() = nightOn
    override fun setNightLightOn(on: Boolean) { calls += "night:$on"; nightOn = on }
    override fun nightLightIntensity() = nightIntensity
    override fun setNightLightIntensity(v: Int) { calls += "nightI:$v"; nightIntensity = v }
    override fun lightMode() = light
    override fun setLightMode(mode: Int) { calls += "light:$mode"; light = mode }
    override fun nowPlayingOpen() = nowPlaying
    override fun setNowPlayingOpen(open: Boolean) { calls += "np:$open" }
    override fun screensaverOn() = saver
    override fun setScreensaverOn(on: Boolean) { calls += "saver:$on"; saver = on }
}

internal val TEST_INPUTS = listOf(
    InputDef(1, "live.hogar", "Hogar", InputDef.TYPE_APPLICATION),
    InputDef(2, "com.netflix.ninja", "Netflix", InputDef.TYPE_APPLICATION),
    InputDef(3, "com.google.android.youtube.tv", "YouTube", InputDef.TYPE_APPLICATION),
    InputDef(4, "hdmi:HW1", "HDMI 1", InputDef.TYPE_HDMI),
)
