package live.hogar.airplay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import kotlin.random.Random

internal class RtspResponse(
    val protocol: String,
    val code: Int,
    val reason: String,
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    val ok: Boolean get() = code in 200..299
    fun header(name: String): String? = headers[name.lowercase(Locale.ROOT)]
    fun plist(): Map<String, Any?> = Plist.decodeDict(body)
    override fun toString() = "$protocol $code $reason"
}

/** Mensaje RTSP/HTTP ya separado (petición o respuesta). */
internal class RawMessage(val startLine: String, val headers: Map<String, String>, val body: ByteArray)

/**
 * Canal de control RTSP (con o sin cifrado HAP). Una sola petición en vuelo a la vez.
 * Si Apple cambia el transporte de control, se sustituye esta interfaz.
 */
internal interface RtspClient : AutoCloseable {
    val remoteAddress: String
    val localAddress: String

    /** Conecta el socket TCP. */
    suspend fun connect()

    /** Activa el cifrado de tramas HAP en este mismo socket (todo lo posterior va cifrado). */
    fun enableEncryption(outKey: ByteArray, inKey: ByteArray)

    suspend fun request(
        method: String,
        uri: String,
        protocol: String = "RTSP/1.0",
        headers: List<Pair<String, String>> = emptyList(),
        contentType: String? = null,
        body: ByteArray? = null,
        timeoutMs: Int = 15_000,
    ): RtspResponse
}

/** Lector/escritor de mensajes RTSP/HTTP sobre streams, con cifrado HAP opcional. */
internal class MessageChannel(private val input: InputStream, private val output: OutputStream) {
    private var cipher: HapCipher? = null
    private var buffer = ByteArray(0)
    private val readBuf = ByteArray(8192)

    fun enable(out: ByteArray, inn: ByteArray) {
        cipher = HapCipher(out, inn)
    }

    fun send(bytes: ByteArray) {
        val c = cipher
        output.write(if (c != null) c.encrypt(bytes) else bytes)
        output.flush()
    }

    /** Bloquea hasta tener un mensaje completo. Lanza EOF si el otro lado cierra. */
    fun readMessage(): RawMessage {
        while (true) {
            parseBuffered()?.let { return it }
            val n = input.read(readBuf)
            if (n < 0) throw java.io.EOFException("El altavoz cerró la conexión")
            val c = cipher
            val chunk = if (c != null) c.decrypt(readBuf, n) else readBuf.copyOf(n)
            if (chunk.isNotEmpty()) buffer += chunk
        }
    }

    private fun parseBuffered(): RawMessage? {
        val end = indexOfHeaderEnd(buffer)
        if (end < 0) return null
        val head = String(buffer, 0, end, Charsets.UTF_8).split("\r\n")
        val headers = LinkedHashMap<String, String>()
        for (line in head.drop(1)) {
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase(Locale.ROOT)] = line.substring(i + 1).trim()
        }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        val total = end + 4 + len
        if (buffer.size < total) return null
        val body = buffer.copyOfRange(end + 4, total)
        buffer = buffer.copyOfRange(total, buffer.size)
        return RawMessage(head[0], headers, body)
    }

    private fun indexOfHeaderEnd(b: ByteArray): Int {
        for (i in 0..b.size - 4) {
            if (b[i] == 13.toByte() && b[i + 1] == 10.toByte() && b[i + 2] == 13.toByte() && b[i + 3] == 10.toByte()) return i
        }
        return -1
    }
}

internal fun formatRequest(
    method: String,
    uri: String,
    protocol: String,
    userAgent: String,
    contentType: String?,
    headers: List<Pair<String, String>>,
    body: ByteArray?,
): ByteArray {
    val sb = StringBuilder()
    sb.append(method).append(' ').append(uri).append(' ').append(protocol).append("\r\n")
    if (headers.none { it.first.equals("User-Agent", true) }) sb.append("User-Agent: ").append(userAgent).append("\r\n")
    if (contentType != null) sb.append("Content-Type: ").append(contentType).append("\r\n")
    sb.append("Content-Length: ").append(body?.size ?: 0).append("\r\n")
    for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
    sb.append("\r\n")
    val head = sb.toString().toByteArray(Charsets.UTF_8)
    return if (body == null || body.isEmpty()) head else head + body
}

internal class TcpRtspClient(
    private val host: String,
    private val port: Int,
    private val connectTimeoutMs: Int = 8_000,
) : RtspClient {
    private val socket = Socket()
    private lateinit var channel: MessageChannel
    private val mutex = Mutex()
    private var cseq = 0
    private val dacpId = java.lang.Long.toHexString(Random.nextLong()).uppercase(Locale.ROOT)
    private val activeRemote = Random.nextLong(0, 1L shl 32)

    override val remoteAddress: String get() = host
    override val localAddress: String get() = socket.localAddress?.hostAddress ?: "0.0.0.0"

    override suspend fun connect() = withContext(Dispatchers.IO) {
        socket.tcpNoDelay = true
        socket.keepAlive = true
        socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
        socket.soTimeout = 15_000
        channel = MessageChannel(socket.getInputStream(), socket.getOutputStream())
    }

    override fun enableEncryption(outKey: ByteArray, inKey: ByteArray) = channel.enable(outKey, inKey)

    override suspend fun request(
        method: String,
        uri: String,
        protocol: String,
        headers: List<Pair<String, String>>,
        contentType: String?,
        body: ByteArray?,
        timeoutMs: Int,
    ): RtspResponse = mutex.withLock {
        withContext(Dispatchers.IO) {
            val all = ArrayList<Pair<String, String>>()
            val isRtsp = protocol.startsWith("RTSP")
            val seq = cseq++
            if (isRtsp) {
                all += "CSeq" to seq.toString()
                all += "DACP-ID" to dacpId
                all += "Active-Remote" to activeRemote.toString()
                all += "Client-Instance" to dacpId
            }
            all += headers
            val msg = formatRequest(method, uri, protocol, USER_AGENT, contentType, all, body)
            socket.soTimeout = timeoutMs
            channel.send(msg)
            // Se descartan peticiones que el receptor nos mande por este canal; interesa la respuesta.
            while (true) {
                val m = channel.readMessage()
                val parts = m.startLine.split(' ', limit = 3)
                if (parts.size >= 2 && (parts[0].startsWith("RTSP/") || parts[0].startsWith("HTTP/"))) {
                    return@withContext RtspResponse(
                        parts[0], parts[1].toIntOrNull() ?: 0, parts.getOrElse(2) { "" }, m.headers, m.body,
                    )
                }
                APLog.d("RTSP: petición del receptor ignorada: ${m.startLine}")
            }
            error("inalcanzable")
        }
    }

    override fun close() {
        try { socket.close() } catch (_: Exception) {}
    }

    companion object {
        const val USER_AGENT = "AirPlay/550.10"
    }
}
