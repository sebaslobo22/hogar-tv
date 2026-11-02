package live.hogar.receiver

import android.content.Context
import live.hogar.receiver.audio.AudioReceiver
import live.hogar.receiver.crypto.PairVerify
import live.hogar.receiver.fairplay.FairPlay
import live.hogar.receiver.mdns.Mdns
import live.hogar.receiver.mirror.MirrorReceiver
import live.hogar.receiver.mirror.ScreenHub
import live.hogar.receiver.net.Conn
import live.hogar.receiver.net.Plist
import live.hogar.receiver.net.Request
import live.hogar.receiver.net.RequestHandler
import live.hogar.receiver.net.Response
import live.hogar.receiver.net.RtspServer
import live.hogar.receiver.net.TimingServer
import live.hogar.receiver.video.VideoCast
import java.security.MessageDigest

/**
 * Nucleo del receptor: servidor RTSP/HTTP + anuncio mDNS + una sola sesion activa a la vez.
 * Logica de protocolo portada de UxPlay (raop_handlers.h, http_handlers.h, pairing.c).
 */
internal class AirPlayCore(private val context: Context) : RequestHandler {
    private val identity = Identity.load(context)
    private val server = RtspServer(this, Features.PORT_PREFERRED)
    private var mdns: Mdns? = null
    private val lock = Any()

    private class Session(val owner: Conn, val aesKey: ByteArray, val aesIv: ByteArray, val timing: TimingServer) {
        var mirror: MirrorReceiver? = null
        var audio: AudioReceiver? = null
        fun stopAll() {
            try { mirror?.stop() } catch (_: Throwable) {}
            try { audio?.stop() } catch (_: Throwable) {}
            try { timing.stop() } catch (_: Throwable) {}
            mirror = null; audio = null
        }
    }

    private var session: Session? = null
    private var video: VideoCast? = null
    private var eventConn: Conn? = null
    private var volumeDb = -15f

    fun start() {
        val port = server.start()
        mdns = Mdns(context, identity, port).also { it.start() }
        ScreenHub.onUserExit = { stopEverything("el usuario pulso Atras") }
    }

    fun stop() {
        mdns?.stop(); mdns = null
        stopEverything("receptor detenido")
        server.stop()
    }

    private fun stopEverything(why: String) {
        synchronized(lock) {
            Log.i("deteniendo sesion: $why")
            session?.stopAll(); session = null
            video?.stop(); video = null
        }
        ScreenHub.finishScreen()
    }

    override fun onClosed(conn: Conn) {
        synchronized(lock) {
            if (eventConn === conn) eventConn = null
            val s = session
            if (s != null && s.owner === conn && s.mirror == null && s.audio == null) { s.stopAll(); session = null }
        }
    }

    // ------------------------------------------------------------------ despacho
    override fun handle(conn: Conn, req: Request): Response {
        val m = req.method
        val p = req.path
        Log.d("<- $req")
        return when {
            m == "GET" && p == "/info" -> info(req)
            m == "POST" && p == "/pair-setup" -> pairSetup(req)
            m == "POST" && p == "/pair-verify" -> pairVerify(conn, req)
            m == "POST" && p == "/fp-setup" -> fpSetup(conn, req)
            m == "OPTIONS" -> Response().also { it.headers["Public"] = "SETUP, RECORD, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER, POST, GET" }
            m == "SETUP" -> setup(conn, req)
            m == "RECORD" -> Response().also { it.headers["Audio-Latency"] = "11025"; it.headers["Audio-Jack-Status"] = "connected; type=analog" }
            m == "FLUSH" -> { session?.audio?.flush(); Response() }
            m == "TEARDOWN" -> teardown(req)
            m == "GET_PARAMETER" -> getParameter(req)
            m == "SET_PARAMETER" -> setParameter(req)
            m == "POST" && p == "/feedback" -> Response()
            m == "POST" && (p == "/command" || p == "/audioMode" || p == "/action") -> Response()
            m == "POST" && p == "/reverse" -> reverse(conn, req)
            m == "POST" && p == "/play" -> play(conn, req)
            (m == "POST" || m == "GET") && p == "/scrub" -> scrub(req)
            m == "POST" && p == "/rate" -> rate(req)
            m == "POST" && p == "/stop" -> { synchronized(lock) { video?.stop(); video = null }; Response() }
            m == "GET" && p == "/playback-info" -> playbackInfo()
            m == "GET" && p == "/server-info" -> serverInfo()
            m == "PUT" && p == "/setProperty" -> Response.plist(mapOf("errorCode" to 0L), xml = true)
            m == "GET" && p == "/getProperty" -> Response()
            else -> {
                Log.w("peticion no soportada: $req")
                if (req.isRtsp) Response(501, "Not Implemented") else Response(404, "Not Found")
            }
        }
    }

    // ------------------------------------------------------------------ /info
    private fun info(req: Request): Response {
        val qualifier = try {
            if (req.body.isNotEmpty() && (req.header("Content-Type") ?: "").contains("plist")) {
                (Plist.parseDict(req.body)["qualifier"] as? List<*>)?.firstOrNull() as? String
            } else null
        } catch (_: Throwable) { null }
        val d = LinkedHashMap<String, Any?>()
        if (qualifier == "txtAirPlay") d["txtAirPlay"] = Advertisement.encodeTxt(Advertisement.airplayTxt(identity))
        if (qualifier == "txtRAOP") d["txtRAOP"] = Advertisement.encodeTxt(Advertisement.raopTxt(identity))
        if (qualifier == null) {
            d["deviceID"] = identity.deviceId
            d["macAddress"] = identity.deviceId
            d["pk"] = identity.edPublic
            d["features"] = Features.VALUE
            d["name"] = Features.NAME
            d["pi"] = identity.pi
            d["vv"] = 2L
            d["statusFlags"] = 68L
            d["keepAliveLowPower"] = true
            d["sourceVersion"] = Features.SRCVERS
            d["keepAliveSendStatsAsBody"] = true
            d["model"] = Features.MODEL
            d["initialVolume"] = volumeDb.toDouble()
            d["audioLatencies"] = listOf(
                mapOf("type" to 100L, "inputLatencyMicros" to 0L, "audioType" to "default", "outputLatencyMicros" to false),
                mapOf("type" to 101L, "inputLatencyMicros" to 0L, "audioType" to "default", "outputLatencyMicros" to false),
            )
            d["audioFormats"] = listOf(
                mapOf("audioOutputFormats" to 0x3fffffcL, "type" to 100L, "audioInputFormats" to 0x3fffffcL),
                mapOf("audioOutputFormats" to 0x3fffffcL, "type" to 101L, "audioInputFormats" to 0x3fffffcL),
            )
            d["displays"] = listOf(
                mapOf(
                    "uuid" to "e0ff8a27-6738-3d56-8a16-cc53aacee925", "widthPhysical" to 0L, "heightPhysical" to 0L,
                    "width" to 1920L, "height" to 1080L, "widthPixels" to 1920L, "heightPixels" to 1080L,
                    "rotation" to false, "refreshRate" to (1.0 / 60.0), "maxFPS" to 30L, "overscanned" to false, "features" to 14L,
                ),
            )
        }
        return Response.plist(d)
    }

    private fun serverInfo(): Response = Response.plist(
        linkedMapOf<String, Any?>(
            "features" to 0x27FL, "macAddress" to identity.deviceId, "model" to Features.MODEL,
            "osBuildVersion" to "12B435", "protovers" to "1.0", "srcvers" to Features.SRCVERS, "vv" to 2L, "deviceid" to identity.deviceId,
        ),
        xml = true,
    )

    // ------------------------------------------------------------------ emparejamiento
    private fun pairSetup(req: Request): Response {
        if (req.body.size != 32) return Response(400, "Bad Request")
        return Response.octets(identity.edPublic)
    }

    private fun pairVerify(conn: Conn, req: Request): Response {
        val d = req.body
        if (d.size < 4) return Response(400, "Bad Request")
        val pv = (conn.pair as? PairVerify) ?: PairVerify(identity.edSeed).also { conn.pair = it }
        return when (d[0].toInt()) {
            1 -> {
                if (d.size != 4 + 64) return Response(400, "Bad Request")
                val out = pv.step1(d.copyOfRange(4, 68))
                conn.ecdhSecret = pv.secret
                Response.octets(out)
            }
            0 -> {
                if (d.size != 4 + 64) return Response(400, "Bad Request")
                if (!pv.step2(d.copyOfRange(4, 68))) Log.w("pair-verify: la firma del cliente no coincide (se continua)")
                Response().also { it.headers["Content-Type"] = "application/octet-stream" }
            }
            else -> Response(400, "Bad Request")
        }
    }

    private fun fpSetup(conn: Conn, req: Request): Response {
        val d = req.body
        return when (d.size) {
            16 -> {
                val r = FairPlay.setup(d)
                if (r == null) {
                    Log.w("fp-setup: tipo de FairPlay no soportado (0x${"%02x".format(d.getOrElse(4) { 0 })})")
                    Response(501, "Not Implemented")
                } else Response.octets(r)
            }
            164 -> {
                val r = FairPlay.handshake(d) ?: return Response(400, "Bad Request")
                conn.fpMessage = d
                Response.octets(r)
            }
            else -> Response(400, "Bad Request")
        }
    }

    // ------------------------------------------------------------------ SETUP / TEARDOWN
    private fun setup(conn: Conn, req: Request): Response {
        val root = Plist.parseDict(req.body)
        val res = LinkedHashMap<String, Any?>()
        val ekey = root["ekey"] as? ByteArray
        val eiv = root["eiv"] as? ByteArray
        if (ekey != null && eiv != null) {
            if (eiv.size != 16 || ekey.size != 72) return Response(400, "Bad Request")
            val msg = conn.fpMessage ?: run { Log.w("SETUP sin fp-setup previo"); return Response(400, "Bad Request") }
            var aesKey = FairPlay.decryptKey(msg, ekey)
            conn.ecdhSecret?.let { secret ->
                aesKey = MessageDigest.getInstance("SHA-512").digest(aesKey + secret).copyOf(16)
            }
            val timingPort = (root["timingPort"] as? Long)?.toInt() ?: 0
            synchronized(lock) {
                session?.stopAll(); session = null
                video?.stop(); video = null
                val timing = TimingServer(conn.remoteAddress, timingPort)
                session = Session(conn, aesKey, eiv, timing)
                res["timingPort"] = timing.port.toLong()
            }
            res["eventPort"] = 0L
            Log.i("sesion nueva desde ${conn.remoteAddress.hostAddress} (${root["name"] ?: "?"}, ${root["model"] ?: "?"})")
        }
        val streams = root["streams"] as? List<*>
        if (streams != null) {
            val s = synchronized(lock) { session } ?: return Response(400, "Bad Request")
            val out = ArrayList<Any?>()
            for (st in streams) {
                @Suppress("UNCHECKED_CAST") val sm = st as Map<String, Any?>
                when ((sm["type"] as? Long)?.toInt()) {
                    110 -> {
                        val id = sm["streamConnectionID"] as? Long ?: 0L
                        synchronized(lock) {
                            s.mirror?.stop()
                            val mr = MirrorReceiver(context, s.aesKey, id) { onMirrorEnded(s) }
                            s.mirror = mr
                            out.add(mapOf("dataPort" to mr.port.toLong(), "type" to 110L))
                        }
                    }
                    96 -> {
                        val ct = (sm["ct"] as? Long)?.toInt() ?: 8
                        val spf = (sm["spf"] as? Long)?.toInt() ?: 0
                        synchronized(lock) {
                            s.audio?.stop()
                            val ar = AudioReceiver(s.aesKey, s.aesIv, ct, spf)
                            ar.setVolumeDb(volumeDb)
                            s.audio = ar
                            out.add(mapOf("dataPort" to ar.dataPort.toLong(), "controlPort" to ar.controlPort.toLong(), "type" to 96L))
                        }
                    }
                    else -> return Response(400, "Bad Request", close = true)
                }
            }
            res["streams"] = out
        }
        return Response.plist(res)
    }

    private fun onMirrorEnded(s: Session) {
        synchronized(lock) {
            if (session === s) { s.mirror?.stop(); s.mirror = null }
        }
        ScreenHub.finishScreen()
    }

    private fun teardown(req: Request): Response {
        var t96 = false
        var t110 = false
        try {
            if (req.body.isNotEmpty()) {
                (Plist.parseDict(req.body)["streams"] as? List<*>)?.forEach {
                    when (((it as? Map<*, *>)?.get("type") as? Long)?.toInt()) { 96 -> t96 = true; 110 -> t110 = true }
                }
            }
        } catch (t: Throwable) { Log.w("TEARDOWN: cuerpo ilegible", t) }
        synchronized(lock) {
            val s = session
            when {
                t96 && !t110 -> { s?.audio?.stop(); s?.audio = null }
                t110 && !t96 -> { s?.mirror?.stop(); s?.mirror = null; ScreenHub.finishScreen() }
                else -> { s?.stopAll(); session = null; video?.stop(); video = null; ScreenHub.finishScreen() }
            }
        }
        return Response(close = true).also { it.headers["Connection"] = "close" }
    }

    // ------------------------------------------------------------------ parametros (volumen)
    private fun getParameter(req: Request): Response {
        val text = String(req.body, Charsets.US_ASCII)
        if (text.startsWith("volume")) {
            return Response(body = "volume: ${"%.6f".format(java.util.Locale.US, volumeDb)}\r\n".toByteArray())
                .also { it.headers["Content-Type"] = "text/parameters" }
        }
        return Response()
    }

    private fun setParameter(req: Request): Response {
        val ct = req.header("Content-Type") ?: return Response(451, "Parameter not understood")
        if (ct.startsWith("text/parameters")) {
            val text = String(req.body, Charsets.US_ASCII)
            if (text.startsWith("volume:")) {
                val v = text.substringAfter(':').trim().lineSequence().first().trim().toFloatOrNull()
                if (v != null) {
                    volumeDb = v
                    session?.audio?.setVolumeDb(v)
                    video?.setVolumeDb(v)
                }
            }
        } // artwork (image/jpeg) y metadatos (dmap) se ignoran
        return Response()
    }

    // ------------------------------------------------------------------ video enviado (/play ...)
    private fun reverse(conn: Conn, req: Request): Response {
        conn.isEvent = true
        conn.sessionId = req.header("X-Apple-Session-ID")
        synchronized(lock) { eventConn = conn }
        return Response(101, "Switching Protocols").also {
            it.headers["Connection"] = "Upgrade"
            it.headers["Upgrade"] = "PTTH/1.0"
        }
    }

    private fun sendEvent(state: String) {
        val c = synchronized(lock) { eventConn } ?: return
        try {
            val body = Plist.toXml(linkedMapOf<String, Any?>("category" to "video", "sessionID" to 1L, "state" to state))
            val head = "POST /event HTTP/1.1\r\nContent-Type: text/x-apple-plist+xml\r\nContent-Length: ${body.size}\r\n" +
                (c.sessionId?.let { "X-Apple-Session-ID: $it\r\n" } ?: "") + "\r\n"
            c.write(head.toByteArray(Charsets.ISO_8859_1) + body)
            Log.i("evento de video: $state")
        } catch (t: Throwable) {
            Log.w("no se pudo enviar el evento $state", t)
        }
    }

    private fun play(conn: Conn, req: Request): Response {
        val ct = req.header("Content-Type") ?: ""
        val params: Map<String, Any?> = try {
            when {
                ct.contains("binary-plist") -> Plist.parseDict(req.body)
                ct.contains("plist") -> @Suppress("UNCHECKED_CAST") (Plist.parseXml(String(req.body, Charsets.UTF_8)) as Map<String, Any?>)
                else -> String(req.body, Charsets.UTF_8).lineSequence().filter { ':' in it }
                    .associate { it.substringBefore(':').trim() to it.substringAfter(':').trim() }
            }
        } catch (t: Throwable) {
            Log.w("/play: cuerpo ilegible", t); return Response(400, "Bad Request")
        }
        val location = params["Content-Location"] as? String ?: return Response(400, "Bad Request")
        val startSec = (params["Start-Position-Seconds"] as? Number)?.toDouble() ?: 0.0
        val startFrac = ((params["Start-Position"] as? Number)?.toDouble() ?: (params["Start-Position"] as? String)?.toDoubleOrNull()) ?: 0.0
        Log.i("/play $location (inicio ${startSec}s)")
        if (conn.sessionId == null) conn.sessionId = req.header("X-Apple-Session-ID")
        synchronized(lock) {
            session?.stopAll(); session = null
            video?.stop()
            val v = VideoCast(context) { sendEvent(it) }
            video = v
            if (location.startsWith("http://") || location.startsWith("https://")) {
                v.play(location, startSec, if (startFrac in 0.0..1.0) startFrac else 0.0)
            } else {
                // mlhls:// u otros esquemas (YouTube, apps con listas HLS propias que se piden de vuelta al iPhone): no soportado.
                Log.w("/play con esquema no soportado: ${location.substringBefore(':')}")
                ScreenHub.show(context)
                ScreenHub.showMessage("Esta app usa un tipo de AirPlay que esta TV no admite. Prueba el espejo de pantalla.")
                sendEvent("stopped")
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ ScreenHub.showMessage(null); ScreenHub.finishScreen() }, 5000)
            }
        }
        return Response()
    }

    private fun scrub(req: Request): Response {
        if (req.method == "GET") {
            val i = video?.info()
            return Response(body = "duration: ${i?.get("duration") ?: 0.0}\r\nposition: ${i?.get("position") ?: 0.0}\r\n".toByteArray())
                .also { it.headers["Content-Type"] = "text/parameters" }
        }
        val pos = req.query.split('&').firstOrNull { it.startsWith("position=") }?.substringAfter('=')?.toDoubleOrNull()
        if (pos != null) video?.seek(pos)
        return Response()
    }

    private fun rate(req: Request): Response {
        val v = req.query.split('&').firstOrNull { it.startsWith("value=") }?.substringAfter('=')?.toFloatOrNull()
        if (v != null) video?.setRate(v)
        return Response()
    }

    private fun playbackInfo(): Response {
        val info = video?.info()
        return if (info == null) Response.plist(linkedMapOf<String, Any?>("readyToPlay" to 0L), xml = true)
        else Response.plist(info, xml = true)
    }
}
