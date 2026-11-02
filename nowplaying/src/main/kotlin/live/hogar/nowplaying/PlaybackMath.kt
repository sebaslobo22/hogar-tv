package live.hogar.nowplaying

/** Matemática pura de reproducción (sin Android, para pruebas en la JVM). */
object PlaybackMath {
    /**
     * Posición actual a partir de lo que reporta la sesión: [basePosMs] medida en [updateElapsedMs]
     * (reloj `elapsedRealtime`), extrapolada con [speed] mientras [playing]. -1 = desconocida.
     * Se acota a 0..[durationMs] si la duración se conoce.
     */
    fun position(basePosMs: Long, updateElapsedMs: Long, nowElapsedMs: Long, speed: Float, playing: Boolean, durationMs: Long): Long {
        var p = if (basePosMs < 0) 0L else basePosMs
        if (playing) {
            val dt = (nowElapsedMs - updateElapsedMs).coerceAtLeast(0L)
            p += (dt * speed).toLong()
        }
        if (durationMs > 0) p = p.coerceAtMost(durationMs)
        return p.coerceAtLeast(0L)
    }

    /** `m:ss` (o `h:mm:ss`). */
    fun clock(ms: Long): String {
        val s = (ms.coerceAtLeast(0L) / 1000)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    fun fraction(posMs: Long, durationMs: Long): Float =
        if (durationMs <= 0) 0f else (posMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

/** Reglas del modo automático (salvapantallas). 0 = según la app, 1 = siempre, 2 = nunca. */
object AutoModePolicy {
    const val DEFAULT = 0
    const val ALWAYS = 1
    const val NEVER = 2
    const val IDLE_MS = 3 * 60_000L

    val MUSIC_APPS = setOf("com.apple.android.music", "com.spotify.music", "com.google.android.apps.youtube.music", "live.hogar")

    fun allowed(setting: Int, playingPackage: String?): Boolean = when (setting) {
        ALWAYS -> playingPackage != null
        NEVER -> false
        else -> playingPackage != null && playingPackage in MUSIC_APPS
    }

    fun next(setting: Int): Int = (setting + 1) % 3
}
