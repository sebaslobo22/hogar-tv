package live.hogar.player.bridge

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import live.hogar.core.AudioOutput
import live.hogar.core.PcmFormat

sealed interface RemoteState {
    data object Idle : RemoteState
    data object Connecting : RemoteState
    data object Streaming : RemoteState
    /** Falló; se reintenta en [retryInMs]. [message] ya está en español. */
    data class Retry(val message: String, val retryInMs: Long) : RemoteState
}

/**
 * Lleva los bloques, SIN TOCARLOS, a los altavoces AirPlay. Una cola, un escritor: lo que entra sale
 * byte a byte igual (la deriva del TV no pasa por aquí). Si el altavoz cae, reintenta con espera creciente
 * mientras el TV sigue sonando. Un FLUSH cancela la escritura en curso, vacía la cola y vacía el altavoz.
 */
internal class RemoteForwarder(
    private val scope: CoroutineScope,
    private val createOutput: () -> AudioOutput?,
    private val currentFrame: () -> Long,
    private val backoffMs: List<Long> = listOf(1_000, 2_000, 4_000, 8_000, 15_000),
    private val log: (String) -> Unit = {},
) {
    private val queue = Channel<Chunk>(Channel.UNLIMITED)
    private val queuedBytes = AtomicInteger(0)
    private val flushReq = Channel<Unit>(Channel.CONFLATED)
    private val _state = kotlinx.coroutines.flow.MutableStateFlow<RemoteState>(RemoteState.Idle)
    val state: StateFlow<RemoteState> = _state

    @Volatile var volume = 1f
        set(v) {
            field = v.coerceIn(0f, 1f)
            val o = current ?: return
            scope.launch { runCatching { o.setVolume(field) } }
        }
    @Volatile private var current: AudioOutput? = null
    @Volatile private var overflow = false
    @Volatile private var skipStale = true
    private var job: Job? = null
    var sessions = 0; private set

    fun start(first: AudioOutput) {
        job = scope.launch { run(first) }
    }

    fun stop() {
        job?.cancel()
        job = null
        drain()
        _state.value = RemoteState.Idle
    }

    /** No bloquea. Si el altavoz lleva más de ~8 s sin consumir, se fuerza una reconexión. */
    fun submit(chunk: Chunk) {
        if (job == null) return
        if (queuedBytes.get() > MAX_QUEUED) { overflow = true; return }
        queuedBytes.addAndGet(chunk.pcm.size)
        queue.trySend(chunk)
    }

    fun flush() {
        drain()
        flushReq.trySend(Unit)
    }

    private fun drain() {
        while (queue.tryReceive().getOrNull() != null) { /* descartar */ }
        queuedBytes.set(0)
    }

    private suspend fun run(first: AudioOutput) {
        var out: AudioOutput? = first
        var attempt = 0
        while (scope.isActive) {
            val o = out ?: createOutput()
            out = null
            var failure: String? = null
            if (o == null) {
                failure = "No encuentro los altavoces en la red"
            } else {
                current = o
                try {
                    _state.value = RemoteState.Connecting
                    o.connect(PcmFormat())
                    o.setVolume(volume)
                    attempt = 0
                    sessions++
                    skipStale = true
                    overflow = false
                    _state.value = RemoteState.Streaming
                    serve(o)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    failure = e.message?.takeIf { it.isNotBlank() } ?: "Se perdió la conexión"
                    log("altavoz: $failure")
                } finally {
                    current = null
                    withContext(NonCancellable) { runCatching { o.disconnect() } }
                }
            }
            val wait = backoffMs[minOf(attempt, backoffMs.size - 1)]
            attempt++
            _state.value = RemoteState.Retry(failure ?: "Reconectando", wait)
            delay(wait)
            drain()   // lo que se acumuló sin altavoz ya es viejo
        }
    }

    private suspend fun serve(o: AudioOutput) = coroutineScope {
        while (isActive) {
            var failure: Throwable? = null
            val epoch = launch {
                try {
                    while (true) {
                        val c = queue.receive()
                        queuedBytes.addAndGet(-c.pcm.size)
                        if (overflow) throw IOException("El altavoz no da abasto")
                        if (skipStale) {
                            // Al abrir sesión, lo acumulado mientras conectaba ya pasó: se empieza por lo actual.
                            if (c.endFrame < currentFrame() - STALE_TOLERANCE_FRAMES) continue
                            skipStale = false
                        }
                        o.write(c.pcm, 0, c.pcm.size)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    failure = e
                }
            }
            select<Unit> {
                flushReq.onReceive {
                    epoch.cancel()
                    epoch.join()
                    drain()
                    o.flush()
                    skipStale = false
                }
                epoch.onJoin { }
            }
            failure?.let { throw it }
        }
    }

    companion object {
        const val MAX_QUEUED = 44_100 * 4 * 8
        const val STALE_TOLERANCE_FRAMES = 44_100 / 50   // 20 ms
    }
}
