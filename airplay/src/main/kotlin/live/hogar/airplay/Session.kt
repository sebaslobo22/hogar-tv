package live.hogar.airplay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import live.hogar.core.Speaker
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.thread

/** Códec de audio que se envía al receptor. Ambos son sin pérdida y bit a bit idénticos al PCM de origen. */
enum class AirPlayCodec {
    /** PCM L16 sin comprimir: el camino que usa pyatv con HomePod (más ancho de banda, ~1,4 Mbit/s). */
    PCM,

    /** ALAC en tramas “verbatim” (sin comprimir): mismo ancho de banda, formato que anuncian los receptores Apple. */
    ALAC,
}

internal data class AirPlayConfig(
    val codec: AirPlayCodec = AirPlayCodec.PCM,
    /** Retraso de reproducción pedido al receptor. HomePod admite entre 250 ms y 2 s. */
    val latencyMs: Int = 1500,
    val pairing: () -> Pairing = { TransientPairing() },
) {
    val encoder: AudioEncoder get() = if (codec == AirPlayCodec.ALAC) AlacEncoder else PcmEncoder
    val latencyFrames: Long get() = latencyMs.coerceIn(250, 2000).toLong() * SAMPLE_RATE / 1000

    companion object {
        const val SAMPLE_RATE = 44_100
    }
}

/**
 * Sesión de AirPlay 2 en tiempo real con un receptor: info → emparejamiento transitorio → canal
 * cifrado → SETUP (base) → canal de eventos → SETUP (flujo) → RECORD → FLUSH. Es el flujo de
 * `StreamClient` + `AirPlayV2` de pyatv (protocols/raop/stream_client.py y protocols/airplay/…).
 */
internal class ReceiverSession(
    val target: Speaker,
    private val config: AirPlayConfig,
) {
    private val random = SecureRandom()
    private val sessionId: Long = random.nextInt().toLong() and 0xFFFFFFFFL
    private var rtsp: RtspClient? = null
    private var sender: RtpSender? = null
    private var events: EventChannel? = null
    private var uri = ""
    private var feedbackFailures = 0

    @Volatile var alive = false
        private set

    val rtp: RtpSender get() = sender ?: error("sesión sin abrir")
    val stats: RtpStats? get() = sender?.stats

    suspend fun open(firstSeq: Int, firstRtp: Long) {
        val name = target.name
        val client = TcpRtspClient(target.host, target.port)
        rtsp = client
        try {
            try {
                client.connect()
            } catch (e: IOException) {
                throw AirPlayException("No se pudo conectar con «$name». ¿Está encendido y en la misma red Wi-Fi?", e)
            }
            uri = "rtsp://${client.localAddress}/$sessionId"

            // 1. /info (informativo; no todos los receptores lo contestan)
            try {
                client.request("GET", "/info")
            } catch (e: IOException) {
                throw AirPlayException("«$name» dejó de responder al conectar", e)
            }

            // 2. Emparejamiento transitorio y cifrado del canal de control
            val keys = config.pairing().pair(client)
            val (outKey, inKey) = keys.controlKeys()
            client.enableEncryption(outKey, inKey)

            // 3. Puertos locales (UDP) + clave de audio
            val shk = ByteArray(32).also(random::nextBytes)
            val rtpSender = UdpRtpSender(shk, sessionId)
            sender = rtpSender

            // 4. SETUP base (sesión, tiempo NTP)
            val base = request(client, "SETUP", "el SETUP de la sesión", body = Plist.encode(baseSetupBody(rtpSender.timingPort)))
            val baseInfo = parsePlist(base, "el SETUP de la sesión")
            val eventPort = (baseInfo["eventPort"] as? Number)?.toInt() ?: 0

            // 5. Canal de eventos (el receptor lo abre y espera que contestemos)
            if (eventPort != 0) {
                events = EventChannel.open(target.host, eventPort, keys.eventKeys())
            }

            // 6. SETUP del flujo de audio
            val enc = config.encoder
            val stream = mapOf(
                "audioFormat" to enc.audioFormat,
                "audioMode" to "default",
                "controlPort" to rtpSender.controlPort,
                "ct" to enc.compressionType,
                "isMedia" to true,
                "latencyMax" to 88200,
                "latencyMin" to 11025,
                "shk" to shk,
                "spf" to enc.framesPerPacket,
                "sr" to AirPlayConfig.SAMPLE_RATE,
                "type" to 0x60,
                "supportsDynamicStreamID" to false,
                "streamConnectionID" to sessionId,
            )
            val streamResp = request(
                client, "SETUP", "el SETUP del flujo de audio",
                body = Plist.encode(mapOf("streams" to listOf(stream))),
            )
            val info = parsePlist(streamResp, "el SETUP del flujo de audio")
            val s0 = (info["streams"] as? List<*>)?.firstOrNull() as? Map<*, *>
                ?: throw AirPlayException("«$name» no devolvió el flujo de audio: es posible que Apple haya cambiado AirPlay")
            val dataPort = (s0["dataPort"] as? Number)?.toInt()
            val controlPort = (s0["controlPort"] as? Number)?.toInt()
            if (dataPort == null || controlPort == null) {
                throw AirPlayException("«$name» no indicó los puertos de audio: es posible que Apple haya cambiado AirPlay")
            }
            rtpSender.connect(InetAddress.getByName(target.host), dataPort, controlPort)

            // 7. RECORD + FLUSH (inicio de la secuencia RTP)
            request(client, "RECORD", "RECORD")
            request(
                client, "FLUSH", "FLUSH",
                headers = listOf(
                    "Range" to "npt=0-",
                    "Session" to "0",
                    "RTP-Info" to "seq=$firstSeq;rtptime=$firstRtp",
                ),
            )
            alive = true
        } catch (t: Throwable) {
            close()
            throw if (t is AirPlayException) t else AirPlayException("Error al abrir la sesión con «$name»: ${t.message}", t)
        }
    }

    private fun baseSetupBody(timingPort: Int): Map<String, Any?> = mapOf(
        "deviceID" to FAKE_MAC,
        "sessionUUID" to UUID.randomUUID().toString().uppercase(Locale.ROOT),
        "timingPort" to timingPort,
        "timingProtocol" to "NTP",
        "isMultiSelectAirPlay" to true,
        "groupContainsGroupLeader" to false,
        "macAddress" to FAKE_MAC,
        "model" to "iPhone14,3",
        "name" to "Hogar",
        "osBuildVersion" to "20F66",
        "osName" to "iPhone OS",
        "osVersion" to "16.5",
        "senderSupportsRelay" to false,
        "sourceVersion" to "690.7.1",
        "statsCollectionEnabled" to false,
    )

    private suspend fun request(
        client: RtspClient,
        method: String,
        what: String,
        headers: List<Pair<String, String>> = emptyList(),
        body: ByteArray? = null,
    ): RtspResponse {
        val r = try {
            client.request(
                method, uri, "RTSP/1.0", headers,
                contentType = if (body != null) "application/x-apple-binary-plist" else null,
                body = body,
            )
        } catch (e: SocketTimeoutException) {
            throw AirPlayException("«${target.name}» no respondió a $what", e)
        } catch (e: IOException) {
            throw AirPlayException("Se perdió la conexión con «${target.name}» durante $what", e)
        }
        if (!r.ok) {
            throw AirPlayException(
                "«${target.name}» rechazó $what (código ${r.code}): es posible que Apple haya cambiado AirPlay",
            )
        }
        return r
    }

    private fun parsePlist(r: RtspResponse, what: String): Map<String, Any?> = try {
        r.plist()
    } catch (e: Exception) {
        throw AirPlayException("La respuesta de «${target.name}» a $what no se pudo leer: es posible que Apple haya cambiado AirPlay", e)
    }

    fun sendAudio(seq: Int, timestamp: Long, payload: ByteArray, first: Boolean) =
        rtp.sendAudio(seq, timestamp, payload, first)

    fun sendSync(nowWithoutLatency: Long, ntp: NtpTime, nowRtp: Long) = rtp.sendSync(nowWithoutLatency, ntp, nowRtp)

    /** Latido de 2 s de AirPlay 2: `POST /feedback`. Devuelve false tras 3 fallos seguidos. */
    suspend fun feedback(): Boolean {
        val c = rtsp ?: return false
        return try {
            c.request("POST", "/feedback", timeoutMs = 4_000)
            feedbackFailures = 0
            true
        } catch (e: Exception) {
            APLog.d("feedback «${target.name}»: ${e.message}")
            ++feedbackFailures < 3
        }
    }

    suspend fun setVolume(db: Float) {
        val c = rtsp ?: return
        try {
            c.request(
                "SET_PARAMETER", uri, "RTSP/1.0", emptyList(), "text/parameters",
                String.format(Locale.US, "volume: %.6f", db).toByteArray(), 6_000,
            )
        } catch (e: Exception) {
            throw AirPlayException("«${target.name}» no aceptó el cambio de volumen", e)
        }
    }

    suspend fun flush(nextSeq: Int, nextRtp: Long) {
        request(
            rtsp ?: return, "FLUSH", "FLUSH",
            headers = listOf("Range" to "npt=0-", "Session" to "0", "RTP-Info" to "seq=$nextSeq;rtptime=$nextRtp"),
        )
    }

    fun markDead() {
        alive = false
    }

    suspend fun close() {
        alive = false
        val c = rtsp
        if (c != null) {
            try {
                c.request("TEARDOWN", uri, "RTSP/1.0", listOf("Session" to "0"), timeoutMs = 2_000)
            } catch (_: Exception) {
            }
        }
        withContext(Dispatchers.IO) {
            events?.close(); events = null
            sender?.close(); sender = null
            c?.close(); rtsp = null
        }
    }

    companion object {
        /** Dirección “MAC” fija con el bit de administración local (02:…): “HOGAR”. */
        const val FAKE_MAC = "02:48:4F:47:41:52"
    }
}

/**
 * Canal de eventos de AirPlay 2: el receptor abre una conexión cifrada (claves “Events-*”) y manda
 * peticiones a las que basta con responder 200 (port de `EventChannel` de pyatv/protocols/airplay/channels.py).
 */
internal class EventChannel private constructor(private val socket: Socket, keys: Pair<ByteArray, ByteArray>) : AutoCloseable {
    private val channel = MessageChannel(socket.getInputStream(), socket.getOutputStream()).also {
        it.enable(keys.first, keys.second)
    }

    init {
        thread(name = "airplay-events", isDaemon = true) {
            try {
                socket.soTimeout = 0
                while (!socket.isClosed) {
                    val m = channel.readMessage()
                    val proto = m.startLine.substringAfterLast(' ', "RTSP/1.0")
                    val cseq = m.headers["cseq"]
                    val sb = StringBuilder("$proto 200 OK\r\nContent-Length: 0\r\nAudio-Latency: 0\r\n")
                    if (cseq != null) sb.append("CSeq: ").append(cseq).append("\r\n")
                    sb.append("\r\n")
                    channel.send(sb.toString().toByteArray())
                }
            } catch (_: Exception) {
                // Conexión cerrada: nada que hacer.
            }
        }
    }

    override fun close() {
        try { socket.close() } catch (_: Exception) {}
    }

    companion object {
        /** El receptor puede tardar en abrir el puerto tras el SETUP: se reintenta (como pyatv). Si no se logra, se sigue sin él. */
        suspend fun open(host: String, port: Int, keys: Pair<ByteArray, ByteArray>): EventChannel? {
            repeat(5) { attempt ->
                try {
                    val s = withContext(Dispatchers.IO) {
                        Socket().also { it.tcpNoDelay = true; it.connect(java.net.InetSocketAddress(host, port), 3_000) }
                    }
                    return EventChannel(s, keys)
                } catch (e: IOException) {
                    APLog.d("canal de eventos, intento ${attempt + 1}: ${e.message}")
                    delay(1_000)
                }
            }
            APLog.w("No se pudo abrir el canal de eventos; se continúa sin él")
            return null
        }
    }
}
