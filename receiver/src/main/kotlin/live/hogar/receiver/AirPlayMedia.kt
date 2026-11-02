package live.hogar.receiver

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Publica lo que llega por AirPlay como una MediaSession ("AirPlay · <dispositivo>") para que
 * "Reproduciendo ahora" y la luz Govee lo vean como a cualquier app (vía MediaTracker).
 * Los metadatos vienen de SET_PARAMETER: progreso (text/parameters), DMAP (título, artista, álbum,
 * duración) e imagen JPEG/PNG. Todo se ejecuta en el hilo principal. Nunca lanza.
 */
internal class AirPlayMedia(context: Context) {
    private val ctx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var session: MediaSession? = null
    private var label = "AirPlay"
    private var meta = DmapMeta()
    private var art: Bitmap? = null
    private var state = PlaybackState.STATE_PAUSED
    private var positionMs = 0L
    private var durationMs = 0L

    /** Nueva sesión de música AirPlay desde [deviceName]. */
    fun begin(deviceName: String?) = post {
        label = if (deviceName.isNullOrBlank()) "AirPlay" else "AirPlay · $deviceName"
        meta = DmapMeta(); art = null; positionMs = 0; durationMs = 0; state = PlaybackState.STATE_PAUSED
        if (session == null) session = MediaSession(ctx, label).also { it.setActive(true) }
        publishMeta(); publishState()
        Log.i("MediaSession: $label")
    }

    fun setPlaying(playing: Boolean) = post {
        state = if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        publishState()
    }

    fun setDmap(m: DmapMeta) = post {
        meta = m
        if (m.durationMs > 0) durationMs = m.durationMs
        publishMeta(); publishState()
    }

    fun setProgress(p: AirPlayProgress) = post {
        positionMs = p.positionMs
        if (p.durationMs > 0) durationMs = p.durationMs
        publishMeta(); publishState()
    }

    fun setArtwork(bytes: ByteArray) = post {
        val b = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull() ?: return@post
        art = b
        publishMeta()
    }

    /** Volumen de AirPlay en dB, en los extras de la sesión (no es un metadato de calidad). */
    fun setVolumeDb(db: Float) = post {
        session?.setExtras(Bundle().apply { putFloat("hogar.airplay.volumeDb", db) })
    }

    /** TEARDOWN / fin de la sesión: la sesión desaparece. */
    fun end() = post {
        session?.let { runCatching { it.setActive(false); it.release() } }
        session = null; art = null
    }

    private fun publishMeta() {
        val s = session ?: return
        val b = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, meta.title ?: label)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION, label)
        meta.artist?.let { b.putString(MediaMetadata.METADATA_KEY_ARTIST, it) }
        meta.album?.let { b.putString(MediaMetadata.METADATA_KEY_ALBUM, it) }
        if (durationMs > 0) b.putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
        art?.let { b.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
        runCatching { s.setMetadata(b.build()) }
    }

    private fun publishState() {
        val s = session ?: return
        val speed = if (state == PlaybackState.STATE_PLAYING) 1f else 0f
        runCatching { s.setPlaybackState(PlaybackState.Builder().setState(state, positionMs, speed, SystemClock.elapsedRealtime()).build()) }
    }

    private fun post(block: () -> Unit) {
        main.post { runCatching(block).onFailure { Log.w("AirPlayMedia", it) } }
    }
}
