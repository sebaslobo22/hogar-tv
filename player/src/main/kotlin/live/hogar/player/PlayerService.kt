package live.hogar.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.projection.MediaProjectionManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Servicio en primer plano (mediaPlayback, y mediaProjection mientras se captura el sonido del TV).
 * Mantiene vivo el proceso para que el audio siga al salir de Hogar, expone la MediaSession
 * (play/pausa del mando) y enlaza las teclas de volumen con AudioOutput.setVolume.
 */
class PlayerService : Service() {
    private val scope = MainScope()
    private lateinit var engine: PlayerEngine
    private lateinit var session: MediaSession
    private lateinit var volumeProvider: VolumeProvider
    private var idleJob: Job? = null
    private var lastVolumePct = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        engine = PlayerHub.engine(this)
        createChannel()

        volumeProvider = object : VolumeProvider(VOLUME_CONTROL_ABSOLUTE, 100, (engine.state.value.volume * 100).toInt()) {
            override fun onSetVolumeTo(volume: Int) = engine.setVolume(volume / 100f)
            override fun onAdjustVolume(direction: Int) {
                if (direction != 0) engine.setVolume(engine.state.value.volume + direction * 0.05f)
            }
        }
        session = MediaSession(this, "Hogar").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { if (!engine.state.value.playing) engine.playPause() }
                override fun onPause() { if (engine.state.value.playing) engine.playPause() }
                override fun onSkipToNext() = engine.next()
                override fun onSkipToPrevious() = engine.previous()
                override fun onStop() = engine.stop()
            })
            setPlaybackToRemote(volumeProvider)
            isActive = true
        }
        startForegroundCompat(false)

        scope.launch { engine.state.collect { render(it) } }
        // Si nada suena ni espera, el servicio se retira (con margen por si el estado aún no llegó).
        idleJob = scope.launch {
            engine.state.map { it.active }.distinctUntilChanged().collect { active ->
                if (!active) {
                    delay(4_000)
                    if (!engine.state.value.active) shutDown()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CAPTURE -> handleCapture(intent)
            ACTION_PLAY_PAUSE -> engine.playPause()
            ACTION_NEXT -> engine.next()
            ACTION_PREV -> engine.previous()
            ACTION_STOP -> engine.stop()
            else -> startForegroundCompat(engine.state.value.capturing)
        }
        return START_NOT_STICKY
    }

    private fun handleCapture(intent: Intent) {
        // Android 14: el servicio debe ser de tipo mediaProjection ANTES de pedir la proyección.
        startForegroundCompat(true)
        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
        if (data == null) {
            engine.postMessage("No se concedió el permiso para capturar el sonido")
            return
        }
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            engine.startCapture(mpm.getMediaProjection(code, data))
        } catch (e: Exception) {
            engine.postMessage("No se pudo iniciar la captura: ${e.message ?: "error desconocido"}")
        }
    }

    private fun render(s: PlayerUiState) {
        val pct = (s.volume * 100).toInt()
        if (pct != lastVolumePct) {
            lastVolumePct = pct
            volumeProvider.currentVolume = pct
        }
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, s.title ?: "Hogar")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, s.artist ?: "")
                .putString(MediaMetadata.METADATA_KEY_ALBUM, s.album ?: "")
                .apply { s.cover?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }
                .build(),
        )
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP,
                )
                .setState(
                    if (s.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f,
                )
                .build(),
        )
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(s))
    }

    private fun startForegroundCompat(capturing: Boolean) {
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        if (capturing) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        startForeground(NOTIF_ID, buildNotification(engine.state.value), type)
    }

    private fun action(icon: Int, label: String, action: String, code: Int): Notification.Action {
        val pi = PendingIntent.getService(
            this, code, Intent(this, PlayerService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(Icon.createWithResource(this, icon), label, pi).build()
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(s: PlayerUiState): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(s.title ?: "Hogar")
            .setContentText(s.artist ?: s.album ?: if (s.capturing) "Enviando el sonido del TV" else "Audio")
            .setLargeIcon(s.cover)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(action(android.R.drawable.ic_media_previous, "Anterior", ACTION_PREV, 1))
            .addAction(
                if (s.playing) action(android.R.drawable.ic_media_pause, "Pausa", ACTION_PLAY_PAUSE, 2)
                else action(android.R.drawable.ic_media_play, "Reproducir", ACTION_PLAY_PAUSE, 2),
            )
            .addAction(action(android.R.drawable.ic_media_next, "Siguiente", ACTION_NEXT, 3))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Audio de Hogar", NotificationManager.IMPORTANCE_LOW))
    }

    private fun shutDown() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        idleJob?.cancel()
        scope.cancel()
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "hogar_audio"
        private const val NOTIF_ID = 4410
        const val ACTION_ENSURE = "live.hogar.player.ENSURE"
        const val ACTION_CAPTURE = "live.hogar.player.CAPTURE"
        const val ACTION_PLAY_PAUSE = "live.hogar.player.PLAY_PAUSE"
        const val ACTION_NEXT = "live.hogar.player.NEXT"
        const val ACTION_PREV = "live.hogar.player.PREV"
        const val ACTION_STOP = "live.hogar.player.STOP"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"

        fun ensureRunning(context: Context) {
            try {
                context.startForegroundService(Intent(context, PlayerService::class.java).setAction(ACTION_ENSURE))
            } catch (_: Exception) {
                // Sin permiso para arrancar en segundo plano: la reproducción sigue mientras Hogar esté abierto.
            }
        }

        fun startCapture(context: Context, resultCode: Int, data: Intent) {
            context.startForegroundService(
                Intent(context, PlayerService::class.java)
                    .setAction(ACTION_CAPTURE)
                    .putExtra(EXTRA_CODE, resultCode)
                    .putExtra(EXTRA_DATA, data),
            )
        }
    }
}
