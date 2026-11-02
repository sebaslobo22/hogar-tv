package live.hogar.core

import kotlinx.coroutines.flow.StateFlow

/** Un altavoz encontrado en la red (HomePod, barra, receptor AirPlay). */
data class Speaker(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val model: String?,
    val txt: Map<String, String>,
)

interface SpeakerDiscovery {
    val speakers: StateFlow<List<Speaker>>
    fun start()
    fun stop()
}

/** PCM entero con signo, little-endian, intercalado. AirPlay a HomePod: 44,1 kHz, 2 canales, 16 bits. */
data class PcmFormat(
    val sampleRate: Int = 44_100,
    val channels: Int = 2,
    val bitsPerSample: Int = 16,
) {
    val bytesPerFrame: Int get() = channels * bitsPerSample / 8
}

sealed interface OutputState {
    data object Idle : OutputState
    data object Connecting : OutputState
    data object Streaming : OutputState
    data class Error(val message: String) : OutputState
}

/**
 * Destino de audio. El reproductor escribe PCM sin tocar; la salida lo entrega bit a bit
 * (sin re-muestrear ni recomprimir con pérdida).
 */
interface AudioOutput {
    val state: StateFlow<OutputState>

    /** Retraso aproximado entre write() y lo que suena, en milisegundos. */
    val latencyMs: Int

    suspend fun connect(format: PcmFormat)

    /** Bloquea mientras el búfer está lleno, para que el ritmo lo marque el destino. */
    suspend fun write(pcm: ByteArray, offset: Int = 0, length: Int = pcm.size)

    /** 0f = silencio, 1f = máximo. */
    suspend fun setVolume(level: Float)

    /** Vacía el búfer (al pausar o saltar de canción). */
    suspend fun flush()

    suspend fun disconnect()
}

interface AudioOutputFactory {
    /** Uno o varios altavoces sincronizados. */
    fun create(speakers: List<Speaker>): AudioOutput
}

// ---------------------------------------------------------------------------------------------
// "TV como puente": el receptor AirPlay del TV entrega aquí el PCM ya decodificado para que otro
// módulo (player/bridge) lo reenvíe a altavoces AirPlay y decida cómo suena el TV.
// ---------------------------------------------------------------------------------------------

/**
 * Oyente del PCM que el receptor AirPlay acaba de decodificar. Formato siempre 16 bits, 44,1 kHz,
 * estéreo, little-endian. Para ALAC y PCM los bytes son los del emisor (sin re-muestrear ni volumen);
 * AAC-LC/ELD ya es con pérdida de origen y se entrega tal cual sale del decodificador.
 *
 * Se llama desde el hilo de red del receptor: no bloquear.
 */
interface PcmTap {
    /**
     * true = el puente reproduce el audio del TV (con su retraso propio) y el receptor NO usa su
     * AudioTrack. false = el receptor reproduce como siempre y esto solo copia el PCM.
     */
    val ownsLocalPlayback: Boolean

    /** Empieza (o se reanuda tras reconectar) un flujo. [lossless] = ALAC o PCM (entrega bit a bit). */
    fun onStart(lossless: Boolean)

    /**
     * Bloque de PCM. [rtpTime] = marca RTP (32 bits sin signo, en tramas a 44,1 kHz) de su primera trama;
     * [arrivalNanos] = `System.nanoTime()` en que llegó el paquete. El arreglo pasa a ser del oyente.
     */
    fun onPcm(pcm: ByteArray, len: Int, rtpTime: Long, arrivalNanos: Long)

    /** El iPhone pausó, saltó de canción o buscó: descartar lo pendiente. */
    fun onFlush()

    /** Volumen del emisor en dB AirPlay (-30..0; -144 = silencio). */
    fun onVolume(db: Float)

    fun onStop()
}

/**
 * Punto de encuentro entre el receptor (productor) y el puente (consumidor). El receptor llama a los
 * métodos `stream*`/`pcm`/`flush`/`volume` con su propio objeto como [owner]; solo el flujo vigente
 * se escucha (un TEARDOWN tardío de una sesión vieja no corta la nueva).
 */
object AudioBridgeHub {
    @Volatile private var tapRef: PcmTap? = null
    private val lock = Any()
    private var owner: Any? = null
    private var lossless = false
    private var volumeDb: Float? = null

    val hasTap: Boolean get() = tapRef != null

    /** Instala (o quita con null) el oyente. Si ya hay un flujo en curso, lo pone al corriente. */
    fun setTap(tap: PcmTap?) {
        val old: PcmTap?
        val live: Boolean
        val ll: Boolean
        val vol: Float?
        synchronized(lock) {
            old = tapRef
            tapRef = tap
            live = owner != null
            ll = lossless
            vol = volumeDb
        }
        if (old === tap) return
        if (old != null && live) old.onStop()
        if (tap != null && live) {
            tap.onStart(ll)
            vol?.let { tap.onVolume(it) }
        }
    }

    fun streamStarted(owner: Any, lossless: Boolean) {
        val t: PcmTap?
        synchronized(lock) {
            this.owner = owner
            this.lossless = lossless
            t = tapRef
        }
        t?.onStart(lossless)
    }

    fun streamStopped(owner: Any) {
        val t: PcmTap?
        synchronized(lock) {
            if (this.owner !== owner) return
            this.owner = null
            t = tapRef
        }
        t?.onStop()
    }

    /** true = el puente reproduce el audio local: el receptor debe omitir su AudioTrack. */
    fun pcm(owner: Any, pcm: ByteArray, len: Int, rtpTime: Long, arrivalNanos: Long): Boolean {
        val t = tapRef ?: return false
        if (synchronized(lock) { this.owner } !== owner) return false
        t.onPcm(pcm, len, rtpTime, arrivalNanos)
        return t.ownsLocalPlayback
    }

    fun flush(owner: Any) {
        val t = tapRef ?: return
        if (synchronized(lock) { this.owner } === owner) t.onFlush()
    }

    fun volume(owner: Any, db: Float) {
        val t: PcmTap?
        synchronized(lock) {
            if (this.owner === owner) volumeDb = db
            t = tapRef
        }
        if (t != null && synchronized(lock) { this.owner } === owner) t.onVolume(db)
    }
}
