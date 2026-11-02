package live.hogar.airplay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import live.hogar.core.AudioOutput
import live.hogar.core.OutputState
import live.hogar.core.PcmFormat
import live.hogar.core.Speaker
import java.io.IOException
import java.security.SecureRandom

/** Un destino lógico: el altavoz elegido y, si es un par estéreo, el otro miembro como alternativa. */
internal class Target(val candidates: List<Speaker>) {
    val primary: Speaker get() = candidates.first()
}

/**
 * [AudioOutput] hacia uno o varios receptores AirPlay 2. Ritmo = reloj RTP: cada paquete de 352 tramas
 * se envía a su hora (con 40 ms de adelanto), de modo que `write` se bloquea cuando el reproductor va
 * más rápido que el tiempo real (contrapresión).
 *
 * Si algo falla, `state` pasa a [OutputState.Error] con un mensaje en español y `connect`/`write`
 * lanzan [AirPlayException] (cuyo `message` es ese mismo texto) para que el reproductor se entere.
 *
 * Con varios destinos se usa la misma línea de tiempo RTP/NTP en todos; la sincronía fina entre
 * receptores distintos depende de ellos (ver notas en AirPlay.kt).
 */
internal class AirPlayOutput(
    private val targets: List<Target>,
    private val config: AirPlayConfig,
) : AudioOutput {
    private val _state = MutableStateFlow<OutputState>(OutputState.Idle)
    override val state: StateFlow<OutputState> = _state
    override val latencyMs: Int get() = config.latencyMs.coerceIn(250, 2000)

    private val lock = Mutex()          // write / flush / connect / disconnect
    private var scope: CoroutineScope? = null
    private var sessions: List<ReceiverSession> = emptyList()
    private val encoder = config.encoder
    private val pending = ByteArray(PACKET_BYTES)
    private var pendingLen = 0
    private var seq0 = 0
    private var rtpBase = 0L
    private var framesSent = 0L
    private var packetIndex = 0L
    @Volatile private var t0Nanos = 0L
    @Volatile private var anchored = false
    private var volume = 1f
    private var connected = false
    @Volatile private var failure: AirPlayException? = null

    /** Estadísticas del primer destino (para el autodiagnóstico). */
    internal fun stats(): List<Pair<String, RtpStats?>> = sessions.map { it.target.name to it.stats }

    override suspend fun connect(format: PcmFormat) = lock.withLock {
        if (connected) return@withLock
        if (format.sampleRate != AirPlayConfig.SAMPLE_RATE || format.channels != 2 || format.bitsPerSample != 16) {
            fail(AirPlayException("AirPlay sólo admite audio de 44,1 kHz, estéreo y 16 bits (recibido ${format.sampleRate} Hz, ${format.channels} canales, ${format.bitsPerSample} bits)"))
        }
        _state.value = OutputState.Connecting
        failure = null
        val rnd = SecureRandom()
        seq0 = rnd.nextInt(0x10000)
        rtpBase = rnd.nextInt().toLong() and 0xFFFFFFFFL
        framesSent = 0; packetIndex = 0; pendingLen = 0; anchored = false

        val opened = try {
            coroutineScope {
                targets.map { t -> async { openTarget(t) } }.awaitAll()
            }
        } catch (e: AirPlayException) {
            fail(e)
        } catch (e: Exception) {
            fail(AirPlayException("No se pudo conectar con el altavoz: ${e.message}", e))
        }
        sessions = opened
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        s.launch { syncLoop() }
        s.launch { feedbackLoop() }
        connected = true
        applyVolume()
        _state.value = OutputState.Streaming
    }

    /** Prueba cada candidato (líder del par primero) hasta que uno acepte la sesión. */
    private suspend fun openTarget(t: Target): ReceiverSession {
        var last: AirPlayException? = null
        for (candidate in t.candidates) {
            val session = ReceiverSession(candidate, config)
            try {
                session.open(seq0, (rtpBase + config.latencyFrames) and 0xFFFFFFFFL)
                return session
            } catch (e: AirPlayException) {
                APLog.w("«${candidate.name}»: ${e.message}")
                last = e
            }
        }
        throw last ?: AirPlayException("No hay altavoz al que conectar")
    }

    override suspend fun write(pcm: ByteArray, offset: Int, length: Int) {
        lock.withLock {
            ensureOk()
            var pos = offset
            val end = offset + length
            while (pos < end) {
                val take = minOf(end - pos, PACKET_BYTES - pendingLen)
                System.arraycopy(pcm, pos, pending, pendingLen, take)
                pos += take
                pendingLen += take
                if (pendingLen == PACKET_BYTES) {
                    sendPacket()
                    pendingLen = 0
                }
            }
        }
    }

    private suspend fun sendPacket() {
        val frames = encoder.framesPerPacket
        val now0 = System.nanoTime()
        if (!anchored) {
            t0Nanos = now0 - framesSent * NANOS / AirPlayConfig.SAMPLE_RATE
            anchored = true
            // Primera sincronía inmediata, antes del primer paquete.
            sendSyncNow()
        }
        val due = t0Nanos + framesSent * NANOS / AirPlayConfig.SAMPLE_RATE
        val wait = due - AHEAD_NANOS - now0
        if (wait > 0) {
            delay(wait / 1_000_000L)
        } else if (-wait > UNDERRUN_NANOS + AHEAD_NANOS) {
            // El reproductor se quedó sin datos (pausa, carga): se re-ancla el reloj para no mandar ráfagas.
            t0Nanos = now0 - framesSent * NANOS / AirPlayConfig.SAMPLE_RATE
            APLog.d("Reloj RTP re-anclado tras un hueco de audio")
        }
        val payload = encoder.encode(pending, 0)
        val seq = ((seq0 + packetIndex) and 0xFFFF).toInt()
        val ts = (rtpBase + config.latencyFrames + framesSent) and 0xFFFFFFFFL
        val first = packetIndex == 0L
        withContext(Dispatchers.IO) {
            for (s in sessions) {
                if (!s.alive) continue
                try {
                    s.sendAudio(seq, ts, payload, first)
                } catch (e: IOException) {
                    s.markDead()
                    APLog.w("UDP «${s.target.name}»: ${e.message}")
                }
            }
        }
        framesSent += frames
        packetIndex++
        if (sessions.none { it.alive }) {
            fail(AirPlayException("Se perdió la conexión con el altavoz"))
        }
    }

    private fun sendSyncNow() {
        val nowNanos = System.nanoTime()
        val pos = (nowNanos - t0Nanos).coerceAtLeast(0) * AirPlayConfig.SAMPLE_RATE / NANOS
        val nwl = (rtpBase + pos) and 0xFFFFFFFFL
        val withLatency = (nwl + config.latencyFrames) and 0xFFFFFFFFL
        val ntp = NtpTime.now()
        for (s in sessions) {
            if (!s.alive) continue
            try { s.sendSync(nwl, ntp, withLatency) } catch (e: IOException) { APLog.d("sync: ${e.message}") }
        }
    }

    private suspend fun syncLoop() {
        while (scope?.isActive == true) {
            delay(1_000)
            if (anchored) sendSyncNow()
        }
    }

    private suspend fun feedbackLoop() {
        while (scope?.isActive == true) {
            delay(2_000)
            for (s in sessions) {
                if (!s.alive) continue
                if (!s.feedback()) {
                    s.markDead()
                    APLog.w("«${s.target.name}» dejó de responder")
                }
            }
            if (connected && sessions.none { it.alive }) {
                failure = AirPlayException("Se perdió la conexión con el altavoz")
                _state.value = OutputState.Error(failure!!.userMessage)
                return
            }
        }
    }

    override suspend fun setVolume(level: Float) {
        volume = level.coerceIn(0f, 1f)
        lock.withLock { if (connected) applyVolume() }
    }

    private suspend fun applyVolume() {
        val db = volumeToDb(volume)
        for (s in sessions) {
            if (!s.alive) continue
            try { s.setVolume(db) } catch (e: AirPlayException) { APLog.w(e.message ?: "volumen") }
        }
    }

    override suspend fun flush() {
        lock.withLock {
            pendingLen = 0
            if (!connected) return
            val nextSeq = ((seq0 + packetIndex) and 0xFFFF).toInt()
            val nextTs = (rtpBase + config.latencyFrames + framesSent) and 0xFFFFFFFFL
            for (s in sessions) {
                if (!s.alive) continue
                try { s.flush(nextSeq, nextTs) } catch (e: AirPlayException) { APLog.w(e.message ?: "flush") }
            }
            anchored = false // el siguiente paquete re-ancla el reloj
        }
    }

    override suspend fun disconnect() {
        lock.withLock {
            connected = false
            scope?.cancel(); scope = null
            val ss = sessions
            sessions = emptyList()
            for (s in ss) s.close()
            pendingLen = 0
            _state.value = OutputState.Idle
        }
    }

    private fun ensureOk() {
        failure?.let { throw it }
        if (!connected) throw AirPlayException("La salida AirPlay no está conectada")
    }

    private fun fail(e: AirPlayException): Nothing {
        failure = e
        _state.value = OutputState.Error(e.userMessage)
        // Se libera lo que hubiera abierto sin esperar al llamador.
        scope?.cancel(); scope = null
        val ss = sessions
        sessions = emptyList()
        connected = false
        CoroutineScope(Dispatchers.IO).launch { ss.forEach { s -> runCatching { s.close() } } }
        throw e
    }

    companion object {
        const val PACKET_BYTES = Alac.FRAMES_PER_PACKET * 4
        private const val NANOS = 1_000_000_000L
        private const val AHEAD_NANOS = 40_000_000L
        private const val UNDERRUN_NANOS = 150_000_000L

        /** 0..1 → dB de AirPlay: 0 = silencio (-144), el resto lineal de -30 a 0 (como `pct_to_dbfs` de pyatv). */
        fun volumeToDb(level: Float): Float =
            if (level <= 0.0001f) -144f else -30f + level.coerceIn(0f, 1f) * 30f
    }
}
