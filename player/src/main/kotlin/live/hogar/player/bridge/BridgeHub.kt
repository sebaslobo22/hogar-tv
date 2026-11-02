package live.hogar.player.bridge

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import live.hogar.core.AudioBridgeHub
import live.hogar.core.AudioOutput
import live.hogar.core.AudioOutputFactory
import live.hogar.core.Speaker
import live.hogar.core.SpeakerDiscovery

/**
 * Un solo puente por proceso. Se inicia una vez (HogarApp) y vive aunque la pantalla Audio esté cerrada:
 * lo que importa es que el iPhone puede empezar a transmitir en cualquier momento.
 * Con el puente apagado no se instala ningún oyente y el receptor suena exactamente como antes.
 */
object BridgeHub {
    private const val TAG = "HogarBridge"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private var store: BridgeSettingsStore? = null
    private var outputs: AudioOutputFactory? = null
    private var discovery: SpeakerDiscovery? = null
    private var bridge: AudioBridge? = null
    @Volatile private var snapshots: Map<String, Speaker> = emptyMap()   // copia inmutable: se lee sin candado

    private val _settings = MutableStateFlow(BridgeSettings())
    val settings: StateFlow<BridgeSettings> = _settings.asStateFlow()
    private val _status = MutableStateFlow(BridgeStatus())
    val status: StateFlow<BridgeStatus> = _status.asStateFlow()

    fun init(context: Context, outputs: AudioOutputFactory, discovery: SpeakerDiscovery) {
        synchronized(lock) {
            if (store != null) return
            val st = BridgeSettingsStore(context)
            store = st
            this.outputs = outputs
            this.discovery = discovery
            snapshots = st.loadSnapshots()
            _settings.value = st.load()
            applyLocked()
        }
    }

    fun setEnabled(on: Boolean) = update { it.copy(enabled = on) }
    fun setLocalEnabled(on: Boolean) = update { it.copy(localEnabled = on) }
    fun nudgeTrim(deltaMs: Int) = update { it.copy(trimMs = (it.trimMs + deltaMs).coerceIn(-300, 300)) }

    fun toggleSpeaker(speaker: Speaker) {
        synchronized(lock) {
            snapshots = snapshots + (speaker.id to speaker)
            store?.saveSnapshots(snapshots.values)
        }
        update {
            it.copy(speakerIds = if (speaker.id in it.speakerIds) it.speakerIds - speaker.id else it.speakerIds + speaker.id)
        }
    }

    private fun update(block: (BridgeSettings) -> BridgeSettings) {
        synchronized(lock) {
            val new = block(_settings.value)
            if (new == _settings.value) return
            _settings.value = new
            store?.save(new)
            applyLocked()
        }
    }

    private fun applyLocked() {
        val s = _settings.value
        if (!s.enabled) {
            AudioBridgeHub.setTap(null)
            bridge?.shutdown()
            bridge = null
            _status.value = BridgeStatus(BridgeStatus.Phase.Off)
            return
        }
        val b = bridge ?: newBridge(s).also { bridge = it }
        b.apply(s)
        AudioBridgeHub.setTap(b)
        scope.launchStatusRelay(b)
    }

    private var relay: kotlinx.coroutines.Job? = null
    private fun CoroutineScope.launchStatusRelay(b: AudioBridge) {
        relay?.cancel()
        relay = launch { b.status.collect { st -> _status.update { st } } }
    }

    private fun newBridge(s: BridgeSettings): AudioBridge = AudioBridge(
        scope = scope,
        nanos = System::nanoTime,
        createOutput = ::createOutput,
        createSink = { AudioTrackSink() },
        log = { Log.i(TAG, it) },
        settings = s,
    )

    /** Altavoces elegidos: primero lo que ve el descubrimiento ahora; si no, la copia guardada. */
    private fun createOutput(): AudioOutput? {
        val f = outputs ?: return null
        val live = discovery?.speakers?.value.orEmpty().associateBy { it.id }
        val saved = snapshots
        val list = _settings.value.speakerIds.mapNotNull { id -> live[id] ?: saved[id] }
        if (list.isEmpty()) return null
        return f.create(list)
    }
}
