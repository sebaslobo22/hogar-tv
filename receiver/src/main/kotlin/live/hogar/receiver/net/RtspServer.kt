package live.hogar.receiver.net

import live.hogar.receiver.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class Request(
    val method: String,
    val target: String,
    val proto: String,
    val headers: Map<String, String>, // claves en minuscula
    val body: ByteArray,
) {
    val path: String get() = target.substringBefore('?')
    val query: String get() = if ('?' in target) target.substringAfter('?') else ""
    val isRtsp: Boolean get() = proto.startsWith("RTSP")
    fun header(name: String): String? = headers[name.lowercase()]
    override fun toString() = "$method $target $proto (${body.size} bytes)"
}

class Response(
    val code: Int = 200,
    val text: String = "OK",
    val headers: LinkedHashMap<String, String> = LinkedHashMap(),
    val body: ByteArray = ByteArray(0),
    val close: Boolean = false,
    val protoOverride: String? = null,
) {
    companion object {
        fun plist(value: Any?, xml: Boolean = false): Response {
            val r = Response(body = if (xml) Plist.toXml(value) else Plist.encode(value))
            r.headers["Content-Type"] = if (xml) "text/x-apple-plist+xml" else "application/x-apple-binary-plist"
            return r
        }

        fun octets(b: ByteArray) = Response(body = b).also { it.headers["Content-Type"] = "application/octet-stream" }
        fun status(code: Int, text: String) = Response(code, text)
    }
}

/** Una conexion TCP del receptor. Guarda el estado que AirPlay liga a la conexion (emparejamiento, FairPlay). */
class Conn(val socket: Socket) {
    private val out: OutputStream = socket.getOutputStream()
    val remoteAddress: InetAddress get() = socket.inetAddress
    var fpMessage: ByteArray? = null
    var pair: Any? = null
    var ecdhSecret: ByteArray? = null
    var isEvent = false
    var sessionId: String? = null

    @Synchronized
    fun write(bytes: ByteArray) {
        out.write(bytes); out.flush()
    }

    fun close() {
        try { socket.close() } catch (_: IOException) {}
    }
}

interface RequestHandler {
    fun handle(conn: Conn, req: Request): Response
    fun onClosed(conn: Conn)
}

/**
 * Servidor unico para RTSP/1.0 y HTTP/1.1 (AirPlay mezcla los dos en el mismo puerto).
 * Cualquier fallo en un hilo de conexion se registra y cierra solo esa conexion.
 */
class RtspServer(private val handler: RequestHandler, private val preferredPort: Int) {
    private var server: ServerSocket? = null
    @Volatile private var running = false
    private val conns = java.util.Collections.synchronizedSet(HashSet<Conn>())
    var port: Int = 0
        private set

    fun start(): Int {
        var ss: ServerSocket? = null
        for (p in preferredPort..preferredPort + 30) {
            try {
                ss = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(p))
                }
                break
            } catch (e: IOException) {
                Log.w("puerto $p ocupado")
            }
        }
        if (ss == null) ss = ServerSocket(0)
        server = ss
        port = ss.localPort
        running = true
        Thread({ acceptLoop(ss) }, "hogar-rtsp-accept").also { it.isDaemon = true }.start()
        Log.i("servidor AirPlay escuchando en el puerto $port")
        return port
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: IOException) {}
        synchronized(conns) { conns.toList() }.forEach { it.close() }
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            try {
                val s = ss.accept()
                s.tcpNoDelay = true
                s.keepAlive = true
                val conn = Conn(s)
                conns.add(conn)
                Thread({ serve(conn) }, "hogar-rtsp-${s.inetAddress.hostAddress}").also { it.isDaemon = true }.start()
            } catch (e: Throwable) {
                if (running) Log.w("accept fallo", e)
                if (!running) break
            }
        }
    }

    private fun serve(conn: Conn) {
        try {
            val input = BufferedInputStream(conn.socket.getInputStream(), 16384)
            while (running) {
                val first = readLine(input) ?: break
                if (first.isBlank()) continue
                val parts = first.split(' ', limit = 3)
                val isResponse = parts[0].startsWith("HTTP/") || parts[0].startsWith("RTSP/")
                val headers = HashMap<String, String>()
                while (true) {
                    val l = readLine(input) ?: return
                    if (l.isEmpty()) break
                    val i = l.indexOf(':')
                    if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
                }
                val len = headers["content-length"]?.toIntOrNull() ?: 0
                val body = if (len > 0) readFully(input, len) ?: return else ByteArray(0)
                if (isResponse) continue // respuesta a un evento nuestro (canal inverso)
                if (parts.size < 3) continue
                val req = Request(parts[0], parts[1], parts[2], headers, body)
                val resp = try {
                    handler.handle(conn, req)
                } catch (t: Throwable) {
                    Log.e("error atendiendo $req", t)
                    Response(500, "Internal Server Error")
                }
                writeResponse(conn, req, resp)
                if (resp.close) break
            }
        } catch (e: IOException) {
            Log.d("conexion cerrada: ${e.message}")
        } catch (t: Throwable) {
            Log.e("fallo en la conexion", t)
        } finally {
            conns.remove(conn)
            try { handler.onClosed(conn) } catch (t: Throwable) { Log.w("onClosed", t) }
            conn.close()
        }
    }

    private fun writeResponse(conn: Conn, req: Request, resp: Response) {
        val proto = resp.protoOverride ?: if (req.isRtsp) "RTSP/1.0" else "HTTP/1.1"
        val sb = StringBuilder()
        sb.append(proto).append(' ').append(resp.code).append(' ').append(resp.text).append("\r\n")
        for ((k, v) in resp.headers) sb.append(k).append(": ").append(v).append("\r\n")
        if (!resp.headers.containsKey("Server")) sb.append("Server: AirTunes/220.68\r\n")
        req.header("CSeq")?.let { sb.append("CSeq: ").append(it).append("\r\n") }
        if (resp.code != 101) sb.append("Content-Length: ").append(resp.body.size).append("\r\n")
        if (resp.close && !resp.headers.containsKey("Connection")) sb.append("Connection: close\r\n")
        sb.append("\r\n")
        val head = sb.toString().toByteArray(Charsets.ISO_8859_1)
        conn.write(if (resp.body.isEmpty()) head else head + resp.body)
    }

    companion object {
        fun readLine(input: java.io.InputStream): String? {
            val bos = ByteArrayOutputStream(64)
            while (true) {
                val b = input.read()
                if (b < 0) return if (bos.size() == 0) null else bos.toString("ISO-8859-1")
                if (b == '\n'.code) break
                if (b != '\r'.code) bos.write(b)
                if (bos.size() > 65536) throw IOException("linea demasiado larga")
            }
            return bos.toString("ISO-8859-1")
        }

        fun readFully(input: java.io.InputStream, n: Int): ByteArray? {
            if (n > 32 * 1024 * 1024) throw IOException("cuerpo demasiado grande")
            val buf = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = input.read(buf, off, n - off)
                if (r < 0) return null
                off += r
            }
            return buf
        }
    }
}
