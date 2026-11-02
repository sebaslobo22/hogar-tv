package live.hogar.player

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import live.hogar.core.AudioOutput
import live.hogar.core.AudioOutputFactory
import live.hogar.core.OutputState
import live.hogar.core.PcmFormat
import live.hogar.core.Speaker

enum class AudioSource { Library, TvSound }

data class PlayerUiState(
    val source: AudioSource = AudioSource.Library,
    val selectedIds: Set<String> = emptySet(),
    val outputState: OutputState = OutputState.Idle,
    val latencyMs: Int = 0,
    val queueIndex: Int = 0,
    val queueSize: Int = 0,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val cover: Bitmap? = null,
    val quality: Quality = Quality.None,
    val playing: Boolean = false,
    val capturing: Boolean = false,
    val volume: Float = 0.5f,
    val message: String? = null,
) {
    /** Mientras sea true el servicio en primer plano sigue vivo. */
    val active: Boolean get() = playing || capturing || queueSize > 0
}

/** Un solo motor por proceso: la pantalla, el servicio y la sesión de medios hablan con el mismo. */
object PlayerHub {
    @Volatile private var instance: PlayerEngine? = null
    fun engine(context: Context): PlayerEngine =
        instance ?: synchronized(this) { instance ?: PlayerEngine(context.applicationContext).also { instance = it } }
}

class PlayerEngine(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()        // un comando a la vez
    private val outMutex = Mutex()    // ciclo de vida de la salida
    private val folderStore = FolderStore(app)

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()
    private val _folders = MutableStateFlow(folderStore.load())
    val folders: StateFlow<List<SavedFolder>> = _folders.asStateFlow()

    private var factory: AudioOutputFactory? = null
    private var selected: List<Speaker> = emptyList()
    private var output: AudioOutput? = null
    private var outputWatcher: Job? = null
    private var playJob: Job? = null
    private var captureJob: Job? = null
    private var projection: MediaProjection? = null
    private var volumeJob: Job? = null
    private val queue = PlayQueue<Track>()
    private var resumeIndex = 0
    private var resumeUs = 0L

    @Volatile private var writtenUs = 0L
    @Volatile private var capturePaused = false
    @Volatile private var metaToken = 0

    // ---------------------------------------------------------------- API pública

    fun attach(outputs: AudioOutputFactory) { factory = outputs }

    fun postMessage(text: String?) = _state.update { it.copy(message = text) }

    fun toggleSpeaker(speaker: Speaker) = command {
        val wasPlaying = _state.value.playing && _state.value.source == AudioSource.Library
        if (wasPlaying) pauseInternal()
        selected = if (selected.any { it.id == speaker.id }) selected.filterNot { it.id == speaker.id } else selected + speaker
        dropOutput()
        _state.update { it.copy(selectedIds = selected.map { s -> s.id }.toSet(), message = null) }
        if (wasPlaying) resumeInternal()
    }

    fun setSource(source: AudioSource) = command {
        if (_state.value.source == source) return@command
        if (source == AudioSource.TvSound && _state.value.playing) pauseInternal()
        if (source == AudioSource.Library && _state.value.capturing) stopCaptureInternal()
        _state.update { it.copy(source = source, message = null) }
    }

    fun playFolder(uri: Uri) = command {
        _folders.value = folderStore.remember(uri)
        _state.update { it.copy(source = AudioSource.Library, message = "Leyendo la carpeta…") }
        val tracks = kotlinx.coroutines.withContext(Dispatchers.IO) {
            runCatching { LibraryScanner.scan(app, uri) }.getOrDefault(emptyList())
        }
        if (tracks.isEmpty()) {
            _state.update { it.copy(message = "No hay música FLAC, ALAC (m4a) o WAV en esa carpeta") }
            return@command
        }
        if (_state.value.capturing) stopCaptureInternal()
        stopPlayJob()
        runCatching { output?.flush() }
        queue.replace(tracks, 0)
        _state.update { it.copy(message = null, queueSize = tracks.size, queueIndex = 0) }
        launchPlayback(0, 0)
    }

    fun playPause() = command {
        val s = _state.value
        if (s.playing) pauseInternal() else resumeInternal()
    }

    fun next() = command {
        if (_state.value.source != AudioSource.Library || queue.size == 0) return@command
        stopPlayJob()
        runCatching { output?.flush() }
        if (queue.next() == null) {
            finishQueue()
            return@command
        }
        launchPlayback(queue.index, 0)
    }

    fun previous() = command {
        if (_state.value.source != AudioSource.Library || queue.size == 0) return@command
        val restart = writtenUs > 3_000_000 || !queue.hasPrevious
        stopPlayJob()
        runCatching { output?.flush() }
        if (!restart) queue.previous()
        launchPlayback(queue.index, 0)
    }

    fun stop() = command {
        stopPlayJob()
        stopCaptureInternal()
        queue.replace(emptyList())
        resumeIndex = 0
        resumeUs = 0
        dropOutput()
        _state.update {
            it.copy(
                playing = false, capturing = false, queueSize = 0, queueIndex = 0, title = null, artist = null,
                album = null, cover = null, quality = Quality.None, message = null,
            )
        }
    }

    fun setVolume(level: Float) {
        val v = level.coerceIn(0f, 1f)
        _state.update { it.copy(volume = v) }
        volumeJob?.cancel()
        volumeJob = scope.launch {
            delay(120)   // no inundar al altavoz con cada pulsación
            runCatching { output?.setVolume(v) }
        }
    }

    /** Llamado por el servicio con el permiso de captura ya concedido. */
    fun startCapture(mp: MediaProjection) = command {
        if (_state.value.playing) pauseInternal()
        stopCaptureInternal()
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                scope.launch { lock.withLock { if (projection === mp) stopCaptureInternal() } }
            }
        }, Handler(Looper.getMainLooper()))
        capturePaused = false
        _state.update {
            it.copy(
                source = AudioSource.TvSound, capturing = true, playing = true, quality = Quality.TvSound,
                title = "Sonido del TV", artist = null, album = null, cover = null, message = null,
            )
        }
        captureJob = scope.launch(Dispatchers.IO) { runCapture(mp) }
    }

    fun stopCapture() = command { stopCaptureInternal() }

    // ---------------------------------------------------------------- internos

    private fun command(block: suspend () -> Unit) {
        scope.launch { lock.withLock { block() } }
    }

    private suspend fun ensureOutput(): AudioOutput? = outMutex.withLock {
        output?.let { return@withLock it }
        val f = factory
        if (f == null || selected.isEmpty()) {
            _state.update { it.copy(message = "Elige un altavoz para escuchar") }
            return@withLock null
        }
        val out = f.create(selected)
        output = out
        outputWatcher?.cancel()
        outputWatcher = scope.launch {
            out.state.collect { s -> _state.update { it.copy(outputState = s, latencyMs = out.latencyMs) } }
        }
        try {
            out.connect(PcmFormat())
            out.setVolume(_state.value.volume)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            output = null
            outputWatcher?.cancel()
            runCatching { out.disconnect() }
            _state.update { it.copy(outputState = OutputState.Idle, message = "No se pudo conectar: ${e.message ?: "error desconocido"}") }
            return@withLock null
        }
        _state.update { it.copy(latencyMs = out.latencyMs) }
        out
    }

    private suspend fun dropOutput() = outMutex.withLock {
        val o = output
        output = null
        outputWatcher?.cancel()
        runCatching { o?.disconnect() }
        _state.update { it.copy(outputState = OutputState.Idle) }
    }

    private suspend fun stopPlayJob() {
        val j = playJob
        playJob = null
        j?.cancel()
        withTimeoutOrNull(2_000) { j?.join() }
    }

    private suspend fun pauseInternal() {
        if (_state.value.source == AudioSource.TvSound) {
            capturePaused = true
            runCatching { output?.flush() }
            _state.update { it.copy(playing = false) }
            return
        }
        stopPlayJob()
        resumeIndex = queue.index
        // Lo escrito va por delante de lo que se oye por el retraso de la salida: retrocedemos esa cantidad.
        resumeUs = maxOf(0L, writtenUs - (output?.latencyMs ?: 0) * 1000L)
        runCatching { output?.flush() }
        _state.update { it.copy(playing = false) }
    }

    private fun resumeInternal() {
        val s = _state.value
        if (s.source == AudioSource.TvSound) {
            if (s.capturing) {
                capturePaused = false
                _state.update { it.copy(playing = true) }
            }
            return
        }
        if (queue.current == null) {
            _state.update { it.copy(message = "Elige una carpeta de música") }
            return
        }
        launchPlayback(resumeIndex.coerceIn(0, queue.size - 1), resumeUs)
    }

    private fun finishQueue() {
        queue.replace(emptyList())
        resumeIndex = 0
        resumeUs = 0
        _state.update { it.copy(playing = false, queueSize = 0, queueIndex = 0) }
    }

    private fun launchPlayback(index: Int, startUs: Long) {
        _state.update { it.copy(playing = true, source = AudioSource.Library, queueSize = queue.size) }
        PlayerService.ensureRunning(app)
        playJob = scope.launch(Dispatchers.IO) {
            try {
                runQueue(index, startUs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(playing = false, message = e.message ?: "Error de reproducción") }
            }
        }
    }

    private suspend fun runQueue(startIndex: Int, startUs: Long) {
        val out = ensureOutput()
        if (out == null) {
            _state.update { it.copy(playing = false) }
            return
        }
        queue.jumpTo(startIndex)
        var seek = startUs
        while (true) {
            val track = queue.current ?: break
            publishTrack(track)
            try {
                playTrack(out, track, seek)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(message = "No se pudo reproducir «${track.title}»: ${e.message ?: "formato no compatible"}") }
            }
            seek = 0
            if (queue.next() == null) break
        }
        delay(out.latencyMs.toLong())   // deja sonar lo que queda en el búfer
        finishQueue()
    }

    private fun publishTrack(track: Track) {
        val token = ++metaToken
        _state.update {
            it.copy(
                title = track.title, artist = null, album = null, cover = null,
                queueIndex = queue.index, queueSize = queue.size,
            )
        }
        scope.launch(Dispatchers.IO) {
            val m = MetaReader.read(app, track)
            if (token == metaToken) {
                _state.update { it.copy(title = m.title ?: it.title, artist = m.artist, album = m.album, cover = m.cover) }
            }
        }
    }

    private suspend fun playTrack(out: AudioOutput, track: Track, startUs: Long) {
        val dec = TrackDecoder(app, track.uri)
        try {
            dec.open()
            if (startUs > 0) dec.seekTo(startUs)
            writtenUs = startUs
            var pump: PcmPump? = null
            while (true) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val chunk = dec.read() ?: break
                if (pump == null) {
                    val pipe = PcmPipeline(dec.sampleRate, dec.channels, dec.encoding, dec.sourceBits)
                    pump = PcmPump(pipe, out)
                    val q = if (pipe.bitExact) Quality.BitPerfect else Quality.Converted(dec.sourceBits, dec.sampleRate, dec.channels)
                    _state.update { it.copy(quality = q, message = null) }
                }
                pump.push(chunk.data, chunk.size)
                val frames = chunk.size / (PcmEncoding.bytesPerSample(dec.encoding) * dec.channels)
                writtenUs += frames * 1_000_000L / dec.sampleRate
            }
            pump?.finish()
        } finally {
            dec.close()
        }
    }

    private suspend fun stopCaptureInternal() {
        val p = projection
        projection = null
        val j = captureJob
        captureJob = null
        j?.cancel()
        withTimeoutOrNull(2_000) { j?.join() }
        runCatching { p?.stop() }
        if (_state.value.capturing) {
            runCatching { output?.flush() }
            _state.update { it.copy(capturing = false, playing = false, quality = Quality.None, title = null) }
        }
    }

    private suspend fun runCapture(mp: MediaProjection) {
        var rec: AudioRecord? = null
        try {
            val cfg = AudioPlaybackCaptureConfiguration.Builder(mp)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val fmt = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(44_100)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val min = AudioRecord.getMinBufferSize(44_100, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            if (app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                throw SecurityException("Falta el permiso de audio para capturar el sonido del TV")
            }
            rec = AudioRecord.Builder()
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(maxOf(min * 4, 44_100 * 4 / 2))
                .setAudioPlaybackCaptureConfig(cfg)
                .build()
            if (rec.state != AudioRecord.STATE_INITIALIZED) error("No se pudo iniciar la captura (¿falta el permiso de micrófono?)")
            if (ensureOutput() == null) return failCapture(null)
            rec.startRecording()
            val buf = ByteArray(44_100 * 4 / 20)   // 50 ms
            while (true) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) error("La captura se interrumpió")
                if (n == 0 || capturePaused) continue
                val out = ensureOutput() ?: break
                try {
                    out.write(buf, 0, n)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (output === out) error(e.message ?: "Error de salida")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failCapture(e.message ?: "No se pudo capturar el sonido del TV")
        } finally {
            runCatching { rec?.stop() }
            runCatching { rec?.release() }
        }
    }

    private fun failCapture(message: String?) {
        if (message != null) _state.update { it.copy(message = message) }
        command { stopCaptureInternal() }
    }
}
