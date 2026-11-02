package live.hogar.homekit

import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap

/** Lectura y escritura de mensajes HTTP/1.1 de HAP (sin dependencias). */
internal object Http {
    private const val MAX_BODY = 64 * 1024
    private val CRLF2 = "\r\n\r\n".toByteArray(Charsets.US_ASCII)

    class Parsed(val req: HttpReq, val consumed: Int)

    /** Devuelve la primera petición completa de [data], o null si aún faltan bytes. Lanza si es basura. */
    fun parse(data: ByteArray, len: Int = data.size): Parsed? {
        val end = indexOf(data, len)
        if (end < 0) {
            if (len > 8192) throw IOException("Cabecera HTTP demasiado larga")
            return null
        }
        val head = String(data, 0, end, Charsets.ISO_8859_1).split("\r\n")
        val line = head[0].split(' ')
        if (line.size < 3) throw IOException("Petición HTTP no válida")
        val headers = HashMap<String, String>()
        for (i in 1 until head.size) {
            val c = head[i].indexOf(':')
            if (c > 0) headers[head[i].substring(0, c).trim().lowercase()] = head[i].substring(c + 1).trim()
        }
        val bodyLen = headers["content-length"]?.toIntOrNull() ?: 0
        if (bodyLen < 0 || bodyLen > MAX_BODY) throw IOException("Cuerpo HTTP demasiado grande")
        val total = end + 4 + bodyLen
        if (len < total) return null
        val target = line[1]
        val q = target.indexOf('?')
        val path = if (q < 0) target else target.substring(0, q)
        val query = HashMap<String, String>()
        if (q >= 0) for (kv in target.substring(q + 1).split('&')) {
            val e = kv.indexOf('=')
            if (e > 0) query[urlDecode(kv.substring(0, e))] = urlDecode(kv.substring(e + 1))
        }
        return Parsed(HttpReq(line[0], path, query, headers, data.copyOfRange(end + 4, total)), total)
    }

    private fun urlDecode(s: String) = try { URLDecoder.decode(s, "UTF-8") } catch (e: Exception) { s }

    private fun indexOf(d: ByteArray, len: Int): Int {
        var i = 0
        while (i + 4 <= len) {
            if (d[i] == CRLF2[0] && d[i + 1] == CRLF2[1] && d[i + 2] == CRLF2[2] && d[i + 3] == CRLF2[3]) return i
            i++
        }
        return -1
    }

    fun reason(status: Int) = when (status) {
        200 -> "OK"; 204 -> "No Content"; 207 -> "Multi-Status"; 400 -> "Bad Request"
        404 -> "Not Found"; 470 -> "Connection Authorization Required"; else -> "Internal Server Error"
    }

    fun serialize(r: HttpResp): ByteArray {
        val sb = StringBuilder("HTTP/1.1 ${r.status} ${reason(r.status)}\r\n")
        if (r.contentType != null) sb.append("Content-Type: ${r.contentType}\r\n")
        if (r.status != 204) sb.append("Content-Length: ${r.body.size}\r\n")
        sb.append("\r\n")
        return sb.toString().toByteArray(Charsets.ISO_8859_1) + r.body
    }

    /** Cuerpo JSON de un evento con los valores dados: {"characteristics":[{"aid":1,"iid":N,"value":V},...]}. */
    fun eventJson(values: List<Pair<Int, Any>>): String =
        Json.write(mapOf("characteristics" to values.map { (iid, v) -> linkedMapOf("aid" to Iid.AID, "iid" to iid, "value" to v) }))

    fun event(json: String): ByteArray {
        val body = json.toByteArray(Charsets.UTF_8)
        return ("EVENT/1.0 200 OK\r\nContent-Type: ${HttpResp.JSON}\r\nContent-Length: ${body.size}\r\n\r\n")
            .toByteArray(Charsets.ISO_8859_1) + body
    }
}

/**
 * Servidor TCP de HAP: un hilo por controlador (pequeño, hasta [MAX_CONNECTIONS]), cifrado de
 * tramas tras pair-verify, y eventos a las conexiones suscritas.
 */
internal class HapServer(
    private val router: HapRouter,
    private val pairing: PairingManager,
    private val log: (String) -> Unit = {},
) {
    private var server: ServerSocket? = null
    private val conns = ConcurrentHashMap<Conn, Socket>()

    val connectionCount: Int get() = conns.size

    /** ¿Algún controlador verificado espera eventos de esta característica? */
    fun isSubscribed(iid: Int): Boolean = conns.keys.any { it.verified && iid in it.subscriptions }

    /** Abre el puerto (libre, elegido por el sistema) y devuelve cuál es. */
    @Synchronized
    fun start(): Int {
        server?.let { return it.localPort }
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(0))
        server = s
        Thread(null, { acceptLoop(s) }, "hk-accept", 128 * 1024).apply { isDaemon = true }.start()
        return s.localPort
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        closeAll()
    }

    fun closeAll() {
        conns.values.forEach { runCatching { it.close() } }
    }

    /**
     * Cierra las conexiones de un controlador quitado. Con retraso: la petición POST /pairings que lo
     * quita llega por esa misma conexión y su respuesta tiene que salir antes del cierre.
     */
    fun closeController(id: String) {
        val victims = conns.filter { (c, _) -> c.session?.controllerId == id }.values.toList()
        if (victims.isEmpty()) return
        Thread({
            runCatching { Thread.sleep(CLOSE_DELAY_MS) }
            victims.forEach { runCatching { it.close() } }
        }, "hk-close").apply { isDaemon = true }.start()
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val sock = try { s.accept() } catch (e: IOException) { break }
            if (conns.size >= MAX_CONNECTIONS) {
                // Lleno (sockets colgados de equipos que salieron del Wi-Fi): se expulsa la más vieja.
                conns.entries.minByOrNull { it.key.since }?.let { (c, old) -> runCatching { old.close() }; conns.remove(c) }
            }
            val conn = Conn()
            conns[conn] = sock
            Thread(null, { serve(sock, conn) }, "hk-conn", 512 * 1024).apply { isDaemon = true }.start()
        }
    }

    private fun serve(sock: Socket, conn: Conn) {
        try {
            sock.tcpNoDelay = true
            sock.keepAlive = true
            val input = sock.getInputStream()
            val buf = ByteArray(4096)
            var pending = ByteArray(0)
            while (true) {
                // Una conexión sin verificar que calla durante un minuto se cierra.
                sock.soTimeout = if (conn.verified) 0 else 60_000
                val n = input.read(buf)
                if (n < 0) break
                val session = conn.session
                val plain = if (session != null) session.cipher.decrypt(buf, n) else buf.copyOf(n)
                pending += plain
                while (true) {
                    val parsed = Http.parse(pending) ?: break
                    pending = pending.copyOfRange(parsed.consumed, pending.size)
                    respond(sock, conn, router.handle(conn, parsed.req))
                }
            }
        } catch (e: SocketTimeoutException) {
            // sin verificar y callada
        } catch (e: Exception) {
            log("conexión cerrada: ${e.message}")
        } finally {
            conns.remove(conn)
            pairing.connectionClosed(conn)
            runCatching { sock.close() }
        }
    }

    private fun respond(sock: Socket, conn: Conn, resp: HttpResp) {
        val bytes = Http.serialize(resp)
        synchronized(conn.outLock) {
            val session = conn.session
            sock.getOutputStream().apply { write(if (session != null) session.cipher.encrypt(bytes) else bytes); flush() }
            // M4 de pair-verify va en claro; a partir de aquí todo va cifrado.
            conn.pendingSession?.let { conn.session = it; conn.pendingSession = null }
        }
    }

    /**
     * Manda un evento con los valores actuales de [iids] a las conexiones suscritas (salvo [except],
     * el controlador que provocó el cambio).
     */
    fun notifyChanged(iids: Collection<Int>, valueOf: (Int) -> Any?, except: Conn? = null) {
        if (iids.isEmpty()) return
        for ((conn, sock) in conns) {
            if (conn === except) continue
            val hit = iids.filter { it in conn.subscriptions }
            if (hit.isEmpty()) continue
            val list = hit.mapNotNull { iid -> valueOf(iid)?.let { iid to it } }
            if (list.isEmpty()) continue
            val msg = Http.event(Http.eventJson(list))
            try {
                synchronized(conn.outLock) {
                    val s = conn.session ?: return@synchronized
                    sock.getOutputStream().apply { write(s.cipher.encrypt(msg)); flush() }
                }
            } catch (e: Exception) {
                runCatching { sock.close() }
            }
        }
    }

    companion object {
        const val MAX_CONNECTIONS = 32
        const val CLOSE_DELAY_MS = 300L
    }
}
