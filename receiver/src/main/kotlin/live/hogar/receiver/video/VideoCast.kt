package live.hogar.receiver.video

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import live.hogar.receiver.Log
import live.hogar.receiver.mirror.ScreenHub
import live.hogar.receiver.mirror.SurfaceConsumer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Video enviado desde un iPhone/iPad/Mac (boton AirPlay de Fotos, Safari...): /play con una URL http(s) que
 * ExoPlayer reproduce (MP4 o HLS). HDR / Dolby Vision se dejan al decodificador por defecto de ExoPlayer, con
 * tunneling activado (lo usa solo si el decodificador lo soporta). Contenido con DRM (FairPlay Streaming) no se puede.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class VideoCast(
    private val context: Context,
    private val sendEvent: (state: String) -> Unit,
) : SurfaceConsumer {
    private val main = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var surface: Surface? = null
    @Volatile private var ended = false
    private var startFraction = 0.0

    private fun <T> onMain(default: T, block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return try { block() } catch (t: Throwable) { Log.w("video", t); default }
        var result: T = default
        val latch = CountDownLatch(1)
        main.post {
            try { result = block() } catch (t: Throwable) { Log.w("video", t) } finally { latch.countDown() }
        }
        latch.await(1500, TimeUnit.MILLISECONDS)
        return result
    }

    override fun onSurface(surface: Surface?) {
        this.surface = surface
        main.post { try { player?.setVideoSurface(surface) } catch (t: Throwable) { Log.w("setVideoSurface", t) } }
    }

    fun play(url: String, startSeconds: Double, startFraction: Double) {
        ended = false
        this.startFraction = startFraction
        ScreenHub.showMessage(null)
        ScreenHub.setConsumer(this)
        ScreenHub.show(context)
        main.post {
            try {
                releasePlayer()
                val selector = DefaultTrackSelector(context).apply {
                    parameters = buildUponParameters().setTunnelingEnabled(true).build()
                }
                val http = DefaultHttpDataSource.Factory()
                    .setUserAgent("AppleCoreMedia/1.0.0.20L498 (Apple TV; U; CPU OS 16_5 like Mac OS X; es_es)")
                    .setAllowCrossProtocolRedirects(true)
                val p = ExoPlayer.Builder(context, DefaultRenderersFactory(context).setEnableDecoderFallback(true))
                    .setTrackSelector(selector)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)))
                    .build()
                p.addListener(listener)
                surface?.let { p.setVideoSurface(it) }
                p.setMediaItem(MediaItem.fromUri(url))
                if (startSeconds > 0) p.seekTo((startSeconds * 1000).toLong())
                p.playWhenReady = true
                p.prepare()
                player = p
                sendEvent("loading")
            } catch (t: Throwable) {
                Log.e("no se pudo iniciar la reproduccion", t)
                fail("No se pudo reproducir este video.")
            }
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            val p = player ?: return
            when (state) {
                Player.STATE_READY -> {
                    if (startFraction > 0 && p.duration != C.TIME_UNSET) {
                        p.seekTo((p.duration * startFraction).toLong()); startFraction = 0.0
                    }
                    sendEvent(if (p.playWhenReady) "playing" else "paused")
                }
                Player.STATE_ENDED -> { sendEvent("stopped"); finish() }
                else -> {}
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (player?.playbackState == Player.STATE_READY) sendEvent(if (isPlaying) "playing" else "paused")
        }

        override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) {
            if (size.width > 0 && size.height > 0) {
                val w = (size.width * size.pixelWidthHeightRatio).toInt()
                ScreenHub.setVideoSize(w, size.height)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e("ExoPlayer: ${error.errorCodeName}", error)
            val protectedContent = error.errorCode in PlaybackException.ERROR_CODE_DRM_UNSPECIFIED..PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED ||
                (error.cause?.message?.contains("skd://", true) == true) || (error.message?.contains("SAMPLE-AES", true) == true)
            fail(
                if (protectedContent) "Este contenido está protegido y no se puede ver por AirPlay en esta TV."
                else "No se pudo reproducir este video en la TV.",
            )
        }
    }

    private fun fail(msg: String) {
        ScreenHub.showMessage(msg)
        sendEvent("stopped")
        main.postDelayed({ finish() }, 5000)
    }

    fun setRate(rate: Float) {
        main.post {
            val p = player ?: return@post
            if (rate <= 0f) p.pause()
            else {
                if (rate != 1f) p.playbackParameters = PlaybackParameters(rate)
                p.play()
            }
        }
    }

    fun seek(seconds: Double) {
        main.post { player?.seekTo((seconds * 1000).toLong()) }
    }

    fun setVolumeDb(db: Float) {
        val g = if (db <= -100f) 0f else Math.pow(10.0, db.coerceIn(-30f, 0f) / 20.0).toFloat()
        main.post { player?.volume = g }
    }

    /** Datos para GET /playback-info; null si no hay reproduccion. */
    fun info(): Map<String, Any?>? = onMain(null) {
        val p = player ?: return@onMain null
        val dur = if (p.duration == C.TIME_UNSET) 0.0 else p.duration / 1000.0
        val pos = p.currentPosition / 1000.0
        val ready = p.playbackState == Player.STATE_READY
        mapOf(
            "duration" to dur,
            "position" to pos,
            "rate" to (if (p.isPlaying) p.playbackParameters.speed.toDouble() else 0.0),
            "readyToPlay" to (if (ready) 1L else 0L),
            "playbackBufferEmpty" to (if (p.playbackState == Player.STATE_BUFFERING) 1L else 0L),
            "playbackBufferFull" to 0L,
            "playbackLikelyToKeepUp" to (if (ready) 1L else 0L),
            "loadedTimeRanges" to listOf(mapOf("start" to 0.0, "duration" to maxOf(dur, pos))),
            "seekableTimeRanges" to listOf(mapOf("start" to 0.0, "duration" to dur)),
        )
    }

    fun stop() { finish() }

    private fun finish() {
        if (ended) return
        ended = true
        main.post {
            releasePlayer()
            ScreenHub.setConsumer(null)
            ScreenHub.showMessage(null)
            ScreenHub.finishScreen()
        }
    }

    private fun releasePlayer() {
        player?.let {
            try { it.removeListener(listener); it.release() } catch (t: Throwable) { Log.w("release", t) }
        }
        player = null
    }
}
