package live.hogar.player.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import live.hogar.core.AudioOutput
import live.hogar.core.PcmTap

/** Estado del puente para la pantalla. Los textos ya van en español. */
data class BridgeStatus(
    val phase: Phase = Phase.Off,
    /** Retraso aplicado al TV (= latencia de los altavoces + ajuste), en ms. */
    val delayMs: Int = 0,
    val driftMs: Float = 0f,
    val message: String? = null,
) {
    enum class Phase { Off, Waiting, Syncing, Synced, LocalOnly }
}

/**
 * Motor del puente. Recibe el PCM del receptor AirPlay ([PcmTap]) y lo reparte:
 *  - a los altavoces AirPlay, bit a bit, por [RemoteForwarder];
 *  - al TV, por [LocalPlayer], retrasado exactamente la latencia de la salida AirPlay.
 * Ambos cuelgan de la misma [StreamTimeline] (RTP -> reloj monótono local).
 */
internal class AudioBridge(
    private val scope: CoroutineScope,
    private val nanos: () -> Long,
    private val createOutput: () -> AudioOutput?,
    private val createSink: () -> LocalSink,
    private val runLocalThread: Boolean = true,
    private val log: (String) -> Unit = {},
    settings: BridgeSettings = BridgeSettings(enabled = true, speakerIds = setOf("x")),
) : PcmTap {
    private val lock = Any()
    private val timeline = StreamTimeline()
    private val sequencer = FrameSequencer()
    private var settings = settings
    private var remote: RemoteForwarder? = null
    internal var local: LocalPlayer? = null; private set
    private var sink: LocalSink? = null
    private var streaming = false
    private var lossless = false
    private var delayNs = 0L
    private var volumeDb = -15f
    private var statusJob: Job? = null

    private val _status = MutableStateFlow(BridgeStatus(BridgeStatus.Phase.Waiting))
    val status: StateFlow<BridgeStatus> = _status

    @Volatile override var ownsLocalPlayback = false; private set

    // ---------------------------------------------------------------- PcmTap

    override fun onStart(lossless: Boolean) = synchronized(lock) {
        this.lossless = lossless
        beginSession()
    }

    override fun onPcm(pcm: ByteArray, len: Int, rtpTime: Long, arrivalNanos: Long) = synchronized(lock) {
        if (!streaming) beginSession()
        val r = sequencer.accept(rtpTime, pcm, len)
        if (r.chunks.isEmpty()) return@synchronized
        val first = r.chunks.first()
        val resync = timeline.noteArrival(first.startFrame, arrivalNanos, resyncThresholdNs())
        if (r.discontinuity || resync) {
            log("puente: discontinuidad, se re-sincroniza")
            resetTimeline(first, arrivalNanos)
        }
        for (c in r.chunks) {
            local?.enqueue(c)
            remote?.submit(c)
        }
    }

    override fun onFlush() = synchronized(lock) {
        timeline.reset()
        sequencer.reset()
        local?.flush()
        remote?.flush()
        _status.value = _status.value.copy(phase = BridgeStatus.Phase.Waiting, message = null)
    }

    override fun onVolume(db: Float) = synchronized(lock) {
        volumeDb = db
        applyVolume()
    }

    override fun onStop() = synchronized(lock) { endSession() }

    // ---------------------------------------------------------------- ajustes

    fun apply(new: BridgeSettings) = synchronized(lock) {
        val old = settings
        settings = new
        if (!streaming) return@synchronized
        if (old.enabled != new.enabled || old.speakerIds != new.speakerIds) {
            endSession()
            beginSession()
        } else {
            local?.muted = !new.localEnabled
            if (old.trimMs != new.trimMs) {
                delayNs = totalDelayNs(currentLatencyMs)
                local?.setDelay(delayNs)
                timeline.reset()
                sequencer.reset()
            }
        }
    }

    fun shutdown() = synchronized(lock) { endSession() }

    // ---------------------------------------------------------------- sesión

    private var currentLatencyMs = 0

    private fun totalDelayNs(latencyMs: Int): Long = (latencyMs + settings.trimMs).coerceAtLeast(0) * 1_000_000L

    private fun resyncThresholdNs() = maxOf(300_000_000L, delayNs - 250_000_000L)

    private fun beginSession() {
        if (streaming) endSession()
        streaming = true
        timeline.reset()
        sequencer.reset()
        val out = if (settings.active) runCatching { createOutput() }.getOrNull() else null
        currentLatencyMs = out?.latencyMs ?: 0
        delayNs = if (out != null) totalDelayNs(currentLatencyMs) else 0L
        ownsLocalPlayback = false
        try {
            val s = createSink()
            sink = s
            local = LocalPlayer(s, timeline, nanos, delayNs).also {
                it.muted = !settings.localEnabled
                if (runLocalThread) it.startThread()
            }
            ownsLocalPlayback = true
        } catch (t: Throwable) {
            log("puente: no se pudo abrir la salida del TV (${t.message})")
            local = null
            sink = null
        }
        applyVolume()
        if (out != null) {
            remote = RemoteForwarder(scope, createOutput, { timeline.frameAt(nanos()) }, log = log).also { it.start(out) }
            remote?.volume = airplayLevel(volumeDb)
        }
        startStatus()
    }

    private fun endSession() {
        streaming = false
        statusJob?.cancel(); statusJob = null
        remote?.stop(); remote = null
        local?.stop(); local = null; sink = null
        ownsLocalPlayback = false
        timeline.reset()
        sequencer.reset()
        _status.value = BridgeStatus(if (settings.active) BridgeStatus.Phase.Waiting else BridgeStatus.Phase.Off)
    }

    private fun resetTimeline(first: Chunk, arrivalNanos: Long) {
        timeline.reset()
        timeline.anchorAt(first.startFrame, arrivalNanos)
        local?.flush()
        remote?.flush()
    }

    private fun applyVolume() {
        sink?.setGain(linearGain(volumeDb))
        remote?.volume = airplayLevel(volumeDb)
    }

    private fun startStatus() {
        statusJob?.cancel()
        statusJob = scope.launch {
            while (isActive) {
                publish()
                delay(500)
            }
        }
    }

    private fun publish() {
        val r = remote
        val l = local
        val delayMs = (delayNs / 1_000_000L).toInt()
        val drift = (l?.errNs ?: 0L) / 1_000_000f
        val st = r?.state?.value
        val s = when {
            r == null -> BridgeStatus(BridgeStatus.Phase.Waiting, 0, drift, if (settings.active) null else "Elige al menos un altavoz")
            st is RemoteState.Retry -> BridgeStatus(
                BridgeStatus.Phase.LocalOnly, delayMs, drift,
                "${st.message}. Reintentando en ${(st.retryInMs + 999) / 1000} s; el TV sigue sonando",
            )
            st is RemoteState.Streaming && l != null && l.inSync && timeline.isAnchored ->
                BridgeStatus(BridgeStatus.Phase.Synced, delayMs, drift, null)
            else -> BridgeStatus(BridgeStatus.Phase.Syncing, delayMs, drift, null)
        }
        _status.value = s
    }

    companion object {
        /** dB de AirPlay (-30..0, -144 = silencio) a ganancia lineal del TV. */
        fun linearGain(db: Float): Float =
            if (db <= -100f) 0f else Math.pow(10.0, db.coerceIn(-30f, 0f) / 20.0).toFloat()

        /** dB de AirPlay a nivel 0..1 para [AudioOutput.setVolume] (proporcional en dB, como el control del iPhone). */
        fun airplayLevel(db: Float): Float = if (db <= -100f) 0f else ((db.coerceIn(-30f, 0f) + 30f) / 30f)
    }
}
