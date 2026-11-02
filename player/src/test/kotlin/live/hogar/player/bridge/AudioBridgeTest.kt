package live.hogar.player.bridge

import java.security.MessageDigest
import java.util.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import live.hogar.core.AudioOutput
import live.hogar.core.OutputState
import live.hogar.core.PcmFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeOutput(override val latencyMs: Int = 1500) : AudioOutput {
    override val state: StateFlow<OutputState> = MutableStateFlow(OutputState.Streaming)
    val received = java.io.ByteArrayOutputStream()
    @Volatile var flushes = 0
    @Volatile var connects = 0
    @Volatile var lastVolume = -1f
    @Volatile var failNextWrite = false
    override suspend fun connect(format: PcmFormat) { connects++ }
    override suspend fun write(pcm: ByteArray, offset: Int, length: Int) {
        if (failNextWrite) { failNextWrite = false; throw java.io.IOException("se cayó") }
        synchronized(received) { received.write(pcm, offset, length) }
    }
    override suspend fun setVolume(level: Float) { lastVolume = level }
    override suspend fun flush() { flushes++ }
    override suspend fun disconnect() {}
    fun bytes(): ByteArray = synchronized(received) { received.toByteArray() }
}

private class FakeSink : LocalSink {
    override val startLatencyNs = 0L
    val written = java.io.ByteArrayOutputStream()
    @Volatile var buffered = 0L
    var flushes = 0
    var starts = 0
    var lastGain = -1f
    override fun write(pcm: ByteArray, off: Int, len: Int) { written.write(pcm, off, len) }
    override fun bufferedFrames(nowNs: Long) = buffered
    override fun start() { starts++ }
    override fun pauseAndFlush() { flushes++; written.reset() }
    override fun setGain(g: Float) { lastGain = g }
    override fun release() {}
}

class AudioBridgeTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun tearDown() { scope.cancel() }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun waitFor(ms: Long = 3000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond() && System.currentTimeMillis() < end) Thread.sleep(5)
        assertTrue("tiempo agotado", cond())
    }

    private class Rig(val out: FakeOutput, val sink: FakeSink, val bridge: AudioBridge, val clock: LongArray)

    private fun rig(latency: Int = 1500, trim: Int = 0, local: Boolean = true): Rig {
        val out = FakeOutput(latency)
        val sink = FakeSink()
        val clock = longArrayOf(10_000_000_000L)
        val b = AudioBridge(
            scope, { clock[0] }, { out }, { sink }, runLocalThread = false,
            settings = BridgeSettings(true, setOf("hp"), local, trim),
        )
        return Rig(out, sink, b, clock)
    }

    private fun packets(n: Int, seed: Long = 1): List<ByteArray> {
        val r = Random(seed)
        return List(n) { ByteArray(352 * 4).also { r.nextBytes(it) } }
    }

    @Test fun `el retraso local es exactamente la latencia de la salida AirPlay mas el ajuste`() {
        val r = rig(latency = 1500, trim = 20)
        r.bridge.onStart(true)
        assertEquals(1_520_000_000L, r.bridge.local!!.delayNs)
        // El primer bloque llega en t0; debe empezar a sonar en t0 + 1520 ms (menos el colchón de 80 ms).
        val t0 = r.clock[0]
        r.bridge.onPcm(packets(1)[0], 352 * 4, 5000, t0)
        val item = LocalPlayer.Item(Chunk(5000, ByteArray(352 * 4)), 0)
        val w = r.bridge.local!!.process(item, t0) as LocalPlayer.Step.Wait
        assertEquals(1_520_000_000L - LocalPlayer.BUFFER_NS, w.ns)
        r.bridge.shutdown()
    }

    @Test fun `la correccion de deriva solo toca el TV - los HomePods reciben el hash exacto`() {
        val r = rig()
        r.bridge.onStart(true)
        val input = packets(300)
        val t0 = r.clock[0]
        var frame = 20_000L
        val local = r.bridge.local!!
        var started = false
        for ((i, p) in input.withIndex()) {
            r.bridge.onPcm(p, p.size, frame, t0 + i * 352L * 1_000_000_000L / 44_100)
            // El TV va retrasado 12 ms respecto a lo deseado: hay que quitar tramas.
            val chunk = Chunk(frame, p)
            val now = t0 + 1_500_000_000L - LocalPlayer.BUFFER_NS + i * 352L * 1_000_000_000L / 44_100
            r.sink.buffered = (LocalPlayer.BUFFER_NS + 12_000_000L) * 44_100 / 1_000_000_000L
            local.process(LocalPlayer.Item(chunk, 0), now)
            if (!started) { started = true }
            frame += 352
        }
        waitFor { r.out.bytes().size == input.sumOf { it.size } }
        assertEquals(sha(input.fold(ByteArray(0)) { a, b -> a + b }), sha(r.out.bytes()))
        assertTrue("debió quitar tramas del TV", local.droppedFrames > 0)
        r.bridge.shutdown()
    }

    @Test fun `la correccion de adelanto duplica tramas locales sin alterar a los HomePods`() {
        val r = rig()
        r.bridge.onStart(true)
        val input = packets(120, seed = 9)
        val t0 = r.clock[0]
        var frame = 0L
        val local = r.bridge.local!!
        for ((i, p) in input.withIndex()) {
            r.bridge.onPcm(p, p.size, frame, t0 + i * 8_000_000L)
            r.sink.buffered = (LocalPlayer.BUFFER_NS - 15_000_000L) * 44_100 / 1_000_000_000L
            local.process(LocalPlayer.Item(Chunk(frame, p), 0), t0 + 1_500_000_000L - LocalPlayer.BUFFER_NS + i * 352L * 1_000_000_000L / 44_100)
            frame += 352
        }
        waitFor { r.out.bytes().size == input.sumOf { it.size } }
        assertEquals(sha(input.fold(ByteArray(0)) { a, b -> a + b }), sha(r.out.bytes()))
        assertTrue(local.insertedFrames > 0)
        r.bridge.shutdown()
    }

    @Test fun `FLUSH vacia los HomePods y el TV y el siguiente bloque se vuelve a anclar`() {
        val r = rig()
        r.bridge.onStart(true)
        val t0 = r.clock[0]
        val a = packets(10)
        a.forEachIndexed { i, p -> r.bridge.onPcm(p, p.size, 1000L + i * 352, t0) }
        waitFor { r.out.bytes().isNotEmpty() }
        val flushesBefore = r.sink.flushes
        r.bridge.onFlush()
        waitFor { r.out.flushes == 1 }
        assertTrue(r.sink.flushes > flushesBefore)
        // Tras el FLUSH el iPhone reanuda con otra marca RTP, 5 s más tarde.
        val t1 = t0 + 5_000_000_000L
        val b = packets(3, seed = 5)
        val before = r.out.bytes().size
        b.forEachIndexed { i, p -> r.bridge.onPcm(p, p.size, 900_000L + i * 352, t1) }
        waitFor { r.out.bytes().size >= before + b.sumOf { it.size } }
        // Los bytes posteriores al flush son exactamente los nuevos.
        val all = r.out.bytes()
        assertEquals(sha(b.fold(ByteArray(0)) { x, y -> x + y }), sha(all.copyOfRange(all.size - b.sumOf { it.size }, all.size)))
        // y el TV espera su hora nueva (anclada en t1), no la vieja.
        val w = r.bridge.local!!.process(LocalPlayer.Item(Chunk(900_000L, ByteArray(352 * 4)), 1), t1) as LocalPlayer.Step.Wait
        assertEquals(1_500_000_000L - LocalPlayer.BUFFER_NS, w.ns)
        r.bridge.shutdown()
    }

    @Test fun `un paquete perdido se rellena con silencio en TV y HomePods por igual`() {
        val r = rig()
        r.bridge.onStart(true)
        val p = packets(3)
        val t0 = r.clock[0]
        r.bridge.onPcm(p[0], p[0].size, 0, t0)
        r.bridge.onPcm(p[2], p[2].size, 704, t0)   // falta el de 352
        waitFor { r.out.bytes().size == 3 * 352 * 4 }
        val got = r.out.bytes()
        assertTrue(got.copyOfRange(352 * 4, 704 * 4).all { it == 0.toByte() })
        r.bridge.shutdown()
    }

    @Test fun `si el altavoz cae el TV sigue y el puente reconecta`() {
        val r = rig()
        r.bridge.onStart(true)
        val t0 = r.clock[0]
        val p = packets(4)
        r.out.failNextWrite = true
        r.bridge.onPcm(p[0], p[0].size, 0, t0)
        r.bridge.onPcm(p[1], p[1].size, 352, t0)
        waitFor(4000) { r.out.connects >= 2 }   // reintento tras la espera de 1 s
        assertTrue(r.bridge.ownsLocalPlayback)
        r.bridge.shutdown()
    }

    @Test fun `el volumen del iPhone va como volumen AirPlay a los altavoces y como ganancia al TV`() {
        val r = rig()
        r.bridge.onStart(true)
        r.bridge.onVolume(-15f)
        assertEquals(0.5f, AudioBridge.airplayLevel(-15f), 0.001f)
        assertEquals(0.1778f, r.sink.lastGain, 0.001f)
        waitFor { Math.abs(r.out.lastVolume - 0.5f) < 0.001f }
        r.bridge.onVolume(-144f)
        assertEquals(0f, r.sink.lastGain, 0f)
        r.bridge.shutdown()
    }

    @Test fun `silenciar el TV deja a los HomePods sonando y el receptor sigue sin usar su AudioTrack`() {
        val r = rig(local = false)
        r.bridge.onStart(true)
        val p = packets(5)
        p.forEachIndexed { i, b -> r.bridge.onPcm(b, b.size, i * 352L, r.clock[0]) }
        waitFor { r.out.bytes().size == 5 * 352 * 4 }
        assertTrue(r.bridge.ownsLocalPlayback)
        assertEquals(0, r.sink.written.size())
        r.bridge.shutdown()
    }
}
