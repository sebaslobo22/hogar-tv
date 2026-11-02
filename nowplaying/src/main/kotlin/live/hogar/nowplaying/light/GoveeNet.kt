package live.hogar.nowplaying.light

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/** Candado de multidifusión mientras se descubre o sincroniza (sin él Android descarta los UDP de grupo). */
internal class MulticastGuard(ctx: Context) {
    private val lock = (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
        ?.createMulticastLock("hogar-govee")?.apply { setReferenceCounted(false) }

    fun acquire() { runCatching { lock?.acquire() } }
    fun release() { runCatching { if (lock?.isHeld == true) lock.release() } }
}

private fun bindReply(): DatagramSocket =
    DatagramSocket(null).apply {
        reuseAddress = true
        bind(InetSocketAddress(GoveeProtocol.REPLY_PORT))
    }

/** Descubrimiento por UDP: multidifusión + difusión de respaldo; respuestas en el puerto 4002. */
internal object GoveeDiscovery {
    suspend fun scan(ctx: Context, timeoutMs: Long = 3_000): List<LampInfo> = withContext(Dispatchers.IO) {
        val guard = MulticastGuard(ctx)
        guard.acquire()
        var socket: DatagramSocket? = null
        val found = LinkedHashMap<String, LampInfo>()
        try {
            socket = bindReply().apply { soTimeout = 250; broadcast = true }
            val req = GoveeProtocol.scanRequest().toByteArray()
            val targets = listOf(GoveeProtocol.MULTICAST_GROUP, "255.255.255.255").map { InetAddress.getByName(it) }
            val buf = ByteArray(2048)
            val end = SystemClock.elapsedRealtime() + timeoutMs
            var nextSend = 0L
            while (isActive && SystemClock.elapsedRealtime() < end) {
                val now = SystemClock.elapsedRealtime()
                if (now >= nextSend) {
                    nextSend = now + 1_000
                    for (t in targets) runCatching { socket.send(DatagramPacket(req, req.size, t, GoveeProtocol.SCAN_PORT)) }
                }
                val p = DatagramPacket(buf, buf.size)
                try { socket.receive(p) } catch (_: SocketTimeoutException) { continue }
                GoveeProtocol.parseScan(String(p.data, 0, p.length))?.let { found[it.device] = it }
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { socket?.close() }
            guard.release()
        }
        found.values.toList()
    }

    /** Prefiere el H607C; si no, la primera. */
    fun pick(list: List<LampInfo>): LampInfo? = list.firstOrNull { it.sku.equals("H607C", true) } ?: list.firstOrNull()
}

/**
 * Sesión con una lámpara: un socket en el 4002 (donde responde) y envíos al 4003. Todo en
 * Dispatchers.IO. [lastReplyMs] se actualiza con cualquier mensaje de la lámpara.
 */
internal class GoveeSession(ctx: Context, scope: CoroutineScope, val ip: String) {
    private val guard = MulticastGuard(ctx)
    private val target = InetAddress.getByName(ip)
    private val socket: DatagramSocket
    private val rx: Job

    @Volatile var lastReplyMs = SystemClock.elapsedRealtime()
        private set
    @Volatile var status: LampStatus? = null
        private set
    @Volatile private var statusSeq = 0

    init {
        guard.acquire()
        try { socket = bindReply().apply { soTimeout = 400 } } catch (t: Throwable) { guard.release(); throw t }
        rx = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(2048)
            while (isActive) {
                val p = DatagramPacket(buf, buf.size)
                try { socket.receive(p) } catch (_: SocketTimeoutException) { continue } catch (_: Throwable) { break }
                if (p.address.hostAddress != ip) continue
                lastReplyMs = SystemClock.elapsedRealtime()
                GoveeProtocol.parseStatus(String(p.data, 0, p.length))?.let { status = it; statusSeq++ }
            }
        }
    }

    fun send(json: String) {
        val b = json.toByteArray()
        runCatching { socket.send(DatagramPacket(b, b.size, target, GoveeProtocol.CONTROL_PORT)) }
    }

    fun ping() = send(GoveeProtocol.devStatusRequest())

    /** Pide el estado y espera la respuesta hasta [timeoutMs]; null si no contesta. */
    suspend fun readStatus(timeoutMs: Long): LampStatus? {
        val seq = statusSeq
        val end = SystemClock.elapsedRealtime() + timeoutMs
        var nextAsk = 0L
        while (SystemClock.elapsedRealtime() < end) {
            val now = SystemClock.elapsedRealtime()
            if (now >= nextAsk) { nextAsk = now + 500; ping() }
            if (statusSeq != seq) return status
            delay(40)
        }
        return null
    }

    fun close() {
        rx.cancel()
        runCatching { socket.close() }
        guard.release()
    }
}
