package live.hogar.nowplaying

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

data class TrackInfo(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val packageName: String,
) {
    /** Identidad de la canción (para letra y carátula). */
    val key: String get() = "$packageName|$title|$artist|$album|$durationMs"
}

/** Última posición reportada por la sesión; se extrapola con [PlaybackMath.position]. */
data class PlaybackSnapshot(
    val state: Int = PlaybackState.STATE_NONE,
    val positionMs: Long = 0L,
    val updateElapsedMs: Long = 0L,
    val speed: Float = 1f,
    /** Máscara de acciones que la sesión dice aceptar (PlaybackState.getActions). */
    val actions: Long = 0L,
) {
    val playing: Boolean get() = state == PlaybackState.STATE_PLAYING
}

data class NowPlayingState(
    val track: TrackInfo? = null,
    val art: Bitmap? = null,
    val playback: PlaybackSnapshot = PlaybackSnapshot(),
    /** El permiso de escucha falta o falló. */
    val accessDenied: Boolean = false,
    /** Insignias de calidad con evidencia en la sesión (nunca adivinadas). */
    val quality: AudioBadges = AudioBadges.None,
    /** Cola de la sesión (vacía si la app no la comparte). */
    val queue: QueueState = QueueState(),
)

/**
 * Sigue la sesión de medios que suena (Apple Music, Spotify, YouTube Music, Hogar, AirPlay...).
 * Todo ocurre en el hilo principal salvo la carga de carátulas por URI (IO). Una instancia por
 * pantalla: [start] al abrirse, [stop] al cerrarse.
 */
class MediaTracker(context: Context, private val scope: CoroutineScope) {
    private val ctx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val manager = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager

    private val _state = MutableStateFlow(NowPlayingState())
    val state: StateFlow<NowPlayingState> = _state

    private var started = false
    private var controllers: List<MediaController> = emptyList()
    private val callbacks = HashMap<MediaController, MediaController.Callback>()
    private var chosen: MediaController? = null
    private var artTrackKey: String? = null
    private var art: Bitmap? = null
    private var artJob: Job? = null
    private var artTriedKey: String? = null
    private val covers = CoverRepository(context)

    // Cola y calidad: se recalculan solo cuando cambia su origen (la cola viaja por IPC).
    private var rawQueue: List<RawQueueItem> = emptyList()
    private var rawQueueTitle: String? = null
    private var queueDirty = true
    private var queueToken: Any? = null
    private var queueCache: QueueState = QueueState()
    private var queueCacheKey: Triple<List<RawQueueItem>, Long, String?>? = null
    private var metaMap: Map<String, String> = emptyMap()
    private var metaMapKey: String? = null
    private var loggedSig: String? = null

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        setControllers(list.orEmpty())
    }

    fun start() {
        if (started) return
        try {
            val cn = ListenerAccess.component(ctx)
            manager.addOnActiveSessionsChangedListener(sessionsListener, cn, main)
            started = true
            setControllers(manager.getActiveSessions(cn))
        } catch (_: SecurityException) {
            _state.value = NowPlayingState(accessDenied = true)
        } catch (_: RuntimeException) {
            _state.value = NowPlayingState(accessDenied = true)
        }
    }

    fun stop() {
        if (started) runCatching { manager.removeOnActiveSessionsChangedListener(sessionsListener) }
        started = false
        for ((c, cb) in callbacks) runCatching { c.unregisterCallback(cb) }
        callbacks.clear()
        controllers = emptyList()
        chosen = null
        artJob?.cancel()
    }

    // ------------------------------------------------------------------ controles

    fun playPause() {
        val c = chosen ?: return
        val t = c.transportControls
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play()
    }

    fun next() { chosen?.transportControls?.skipToNext() }
    fun previous() { chosen?.transportControls?.skipToPrevious() }
    fun skipToQueueItem(id: Long) { chosen?.transportControls?.skipToQueueItem(id) }

    /** Barajar / repetir: acciones personalizadas del convenio de PlaybackStateCompat (solo si la sesión las anuncia). */
    fun setShuffle(on: Boolean) {
        chosen?.transportControls?.sendCustomAction(SessionCaps.ACTION_SET_SHUFFLE, Bundle().apply { putInt(SessionCaps.ARG_SHUFFLE, if (on) 1 else 0) })
    }

    /** [mode]: 0 ninguna, 1 una, 2 todas. */
    fun setRepeat(mode: Int) {
        chosen?.transportControls?.sendCustomAction(SessionCaps.ACTION_SET_REPEAT, Bundle().apply { putInt(SessionCaps.ARG_REPEAT, mode) })
    }

    // ------------------------------------------------------------------ sesiones

    private fun setControllers(list: List<MediaController>) {
        val gone = callbacks.keys.filter { c -> list.none { it.sessionToken == c.sessionToken } }
        for (c in gone) { callbacks.remove(c)?.let { runCatching { c.unregisterCallback(it) } } }
        for (c in list) {
            if (callbacks.keys.none { it.sessionToken == c.sessionToken }) {
                val cb = object : MediaController.Callback() {
                    override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
                    override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
                    override fun onSessionDestroyed() = publish()
                    override fun onQueueChanged(queue: MutableList<android.media.session.MediaSession.QueueItem>?) { queueDirty = true; publish() }
                    override fun onQueueTitleChanged(title: CharSequence?) { queueDirty = true; publish() }
                }
                c.registerCallback(cb, main)
                callbacks[c] = cb
            }
        }
        controllers = list
        publish()
    }

    private fun pick(): MediaController? {
        controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }?.let { return it }
        controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_BUFFERING }?.let { return it }
        chosen?.let { cur -> controllers.firstOrNull { it.sessionToken == cur.sessionToken }?.let { return it } }
        return controllers.firstOrNull { it.metadata != null } ?: controllers.firstOrNull()
    }

    private fun publish() {
        val c = pick()
        chosen = c
        val md = c?.metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        if (c == null || md == null || title.isNullOrBlank()) {
            artTrackKey = null; art = null; artJob?.cancel()
            _state.value = NowPlayingState()
            return
        }
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
        val album = md.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0L)
        val track = TrackInfo(title.trim(), artist.trim(), album.trim(), duration, c.packageName)

        if (artTrackKey != track.key) {
            artTrackKey = track.key
            art = null
            artTriedKey = null
            artJob?.cancel()
        }
        val inline = bestInlineArt(md)
        if (inline != null && (art == null || side(inline) > side(art!!))) art = inline
        if ((art == null || side(art!!) < SHARP_ART_PX) && artTriedKey != track.key) {
            artTriedKey = track.key
            refineArt(track, md)
        }

        val ps = c.playbackState
        val snap = if (ps == null) PlaybackSnapshot() else PlaybackSnapshot(
            state = ps.state, positionMs = ps.position,
            updateElapsedMs = ps.lastPositionUpdateTime, speed = ps.playbackSpeed.takeIf { it > 0f } ?: 1f,
            actions = ps.actions,
        )

        // Calidad: solo con lo que la sesión declara (metadatos personalizados, extras del estado o de la sesión).
        val mkey = "${track.key}#${md.size()}"
        if (mkey != metaMapKey) { metaMapKey = mkey; metaMap = metadataMap(md) }
        val extras = bundleMap(ps?.extras, "") + bundleMap(c.extras, "session.")
        val quality = AudioQuality.badges(metaMap, extras)
        logSession(c, track, extras, ps)

        _state.value = NowPlayingState(track = track, art = art, playback = snap, quality = quality, queue = queueFor(c, track, ps))
    }

    // ------------------------------------------------------------------ cola

    private fun queueFor(c: MediaController, track: TrackInfo, ps: PlaybackState?): QueueState {
        if (queueDirty || queueToken != c.sessionToken) {
            queueDirty = false
            queueToken = c.sessionToken
            rawQueue = runCatching {
                c.queue.orEmpty().map { q ->
                    val d = q.description
                    RawQueueItem(q.queueId, d.title?.toString().orEmpty(), d.subtitle?.toString().orEmpty(), d.iconUri?.toString(), d.iconBitmap)
                }
            }.getOrDefault(emptyList())
            rawQueueTitle = runCatching { c.queueTitle?.toString() }.getOrNull()
        }
        val active = ps?.activeQueueItemId ?: QueueMapper.UNKNOWN_ID
        val key = Triple(rawQueue, active, track.title)
        if (key != queueCacheKey) {
            queueCacheKey = key
            queueCache = QueueState(rawQueueTitle, QueueMapper.map(rawQueue, active, track.title))
        }
        return queueCache
    }

    // ------------------------------------------------------------------ diagnóstico (HogarNP)

    private fun metadataMap(md: MediaMetadata): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (k in md.keySet()) {
            val v = md.getString(k) ?: md.getText(k)?.toString()
                ?: md.getLong(k).takeIf { it != 0L }?.toString()
                ?: runCatching { md.getBitmap(k) }.getOrNull()?.let { "bitmap ${it.width}x${it.height}" }
                ?: continue
            out[k] = v
        }
        return out
    }

    @Suppress("DEPRECATION")
    private fun bundleMap(b: Bundle?, prefix: String): Map<String, String> {
        if (b == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        runCatching { for (k in b.keySet()) out[prefix + k] = b.get(k)?.toString().orEmpty() }
        return out
    }

    /** Vuelca una vez por canción (y si cambia el conjunto de claves) todo lo que expone la sesión. */
    private fun logSession(c: MediaController, track: TrackInfo, extras: Map<String, String>, ps: PlaybackState?) {
        val sig = track.key + "|" + metaMap.keys.joinToString(",") + "|" + extras.keys.joinToString(",")
        if (sig == loggedSig) return
        loggedSig = sig
        val pkg = track.packageName
        fun cut(v: String) = if (v.length > 160) v.take(160) + "…" else v
        Log.i(TAG, "[$pkg] pista=\"${track.title}\" — ${metaMap.size} claves de metadatos, ${extras.size} extras")
        for ((k, v) in metaMap) Log.i(TAG, "[$pkg] meta $k = ${cut(v)}")
        for ((k, v) in extras) Log.i(TAG, "[$pkg] extra $k = ${cut(v)}")
        if (ps != null) {
            Log.i(TAG, "[$pkg] acciones=0x${ps.actions.toString(16)} estado=${ps.state} activeQueueItemId=${ps.activeQueueItemId} error=${ps.errorMessage}")
            for (a in ps.customActions) Log.i(TAG, "[$pkg] accion personalizada ${a.action} (${a.name})")
        }
        runCatching {
            val pi = c.playbackInfo
            Log.i(TAG, "[$pkg] playbackInfo tipo=${pi?.playbackType} audio=${pi?.audioAttributes}")
            Log.i(TAG, "[$pkg] cola=${rawQueue.size} titulo=${rawQueueTitle}")
        }
        val q = AudioQuality.verdict(metaMap, extras)
        Log.i(TAG, "[$pkg] veredicto calidad=$q atmos=${AudioQuality.atmos(metaMap, extras)}")
    }

    // ------------------------------------------------------------------ carátula

    private fun side(b: Bitmap) = minOf(b.width, b.height)

    private fun bestInlineArt(md: MediaMetadata): Bitmap? =
        listOf(MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            .mapNotNull { runCatching { md.getBitmap(it) }.getOrNull() }
            .maxByOrNull { side(it) }

    /** Busca una carátula mayor: primero las URI de la sesión, luego la API pública de iTunes (1200 px). */
    private fun refineArt(track: TrackInfo, md: MediaMetadata) {
        val uris = listOf(MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
            .mapNotNull { md.getString(it) }.filter { it.isNotBlank() }.distinct()
        artJob?.cancel()
        artJob = scope.launch {
            if (uris.isNotEmpty()) offer(track.key, withContext(Dispatchers.IO) { uris.firstNotNullOfOrNull { loadUri(it) } })
            if (artTrackKey == track.key && (art == null || side(art!!) < SHARP_ART_PX)) {
                offer(track.key, withContext(Dispatchers.IO) { covers.fetch(track) })
            }
        }
    }

    private fun offer(trackKey: String, bmp: Bitmap?) {
        if (bmp != null && artTrackKey == trackKey && (art == null || side(bmp) > side(art!!))) {
            art = bmp
            _state.value = _state.value.copy(art = bmp)
        }
    }

    private fun loadUri(uriStr: String): Bitmap? = runCatching {
        val uri = Uri.parse(uriStr)
        fun open() = if (uri.scheme == "http" || uri.scheme == "https") {
            (URL(uriStr).openConnection() as HttpURLConnection).apply { connectTimeout = 6000; readTimeout = 8000 }.inputStream
        } else ctx.contentResolver.openInputStream(uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return@runCatching null
        val opts = BitmapFactory.Options().apply { inSampleSize = ArtworkMath.sampleSize(bounds.outWidth, bounds.outHeight, MAX_ART_PX) }
        open()?.use { BitmapFactory.decodeStream(it, null, opts) }
    }.getOrNull()

    private companion object {
        const val TAG = "HogarNP"
        /** Debajo de esto se intenta una versión mayor por URI. */
        const val SHARP_ART_PX = 880
        const val MAX_ART_PX = 1600
    }
}
