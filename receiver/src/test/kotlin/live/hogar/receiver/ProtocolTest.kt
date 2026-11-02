package live.hogar.receiver

import live.hogar.receiver.crypto.Curve25519
import live.hogar.receiver.crypto.PairVerify
import live.hogar.receiver.fairplay.FairPlay
import live.hogar.receiver.mirror.SpsParser
import live.hogar.receiver.mirror.StreamKeys
import live.hogar.receiver.net.Conn
import live.hogar.receiver.net.Plist
import live.hogar.receiver.net.Request
import live.hogar.receiver.net.RequestHandler
import live.hogar.receiver.net.Response
import live.hogar.receiver.net.RtspServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class ProtocolTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    // bplist generado con plistlib de Python (implementacion independiente): cuerpo tipico del SETUP del iPhone.
    private val setupPlist = "62706c6973743030d90102030405060708090a0b0c0d0e0f101a1b5864657669636549445365697654656b65795f1018697353637265656e4d6972726f72696e6753657373696f6e556d6f64656c546e616d655773747265616d735a74696d696e67506f72745e74696d696e6750726f746f636f6c5f101141413a42423a43433a44443a45453a46464f1010eef95a60e56143d6c43bcad76c008a9b4f1048e7eee7615ef35f30e49b482e15cae75007201e12617b0feda7e1647796ff022bea8ed02a82a175930f2337cd3794c52208006d6b1af0c0cbd625658aac2c9faa07d13c447e33051e095a6950686f6e6531342c325f100f6950686f6e65206465205365626173a111d41213141516171819596c6174656e63794d735f101273747265616d436f6e6e656374696f6e494454747970655178105a140000000000000000ab54a98ceb1f0ad2106e233ff800000000000011d431534e54500008001b00240028002d0048004e0053005b006600750089009c00e700e800f3010501070110011a012f0134013601380149014b015401570000000000000201000000000000001c0000000000000000000000000000015b"

    @Test
    fun plistLeeElSetupDeUnIphone() {
        val d = Plist.parseDict(hex(setupPlist))
        assertEquals("AA:BB:CC:DD:EE:FF", d["deviceID"])
        assertEquals(54321L, d["timingPort"])
        assertEquals(true, d["isScreenMirroringSession"])
        assertEquals(72, (d["ekey"] as ByteArray).size)
        assertEquals(16, (d["eiv"] as ByteArray).size)
        val st = (d["streams"] as List<*>)[0] as Map<*, *>
        assertEquals(110L, st["type"])
        assertEquals("12345678901234567890", java.lang.Long.toUnsignedString(st["streamConnectionID"] as Long))
        assertEquals(1.5, st["x"] as Double, 0.0)
    }

    @Test
    fun plistIdaYVuelta() {
        val src = linkedMapOf<String, Any?>(
            "features" to 0x5A7FFEFFL, "name" to "TV Daewoo", "pk" to ByteArray(32) { it.toByte() }, "ok" to true, "no" to false,
            "refreshRate" to (1.0 / 60.0), "ñandú" to "áéí", "n" to -5L,
            "displays" to listOf(linkedMapOf("width" to 1920L, "rotation" to false)),
            "grande" to ByteArray(300) { (it * 7).toByte() },
        )
        val back = Plist.parseDict(Plist.encode(src))
        assertEquals(src["features"], back["features"])
        assertEquals("TV Daewoo", back["name"])
        assertArrayEquals(src["pk"] as ByteArray, back["pk"] as ByteArray)
        assertEquals(false, back["no"])
        assertEquals(1.0 / 60.0, back["refreshRate"] as Double, 0.0)
        assertEquals("áéí", back["ñandú"])
        assertEquals(-5L, back["n"])
        assertEquals(300, (back["grande"] as ByteArray).size)
        assertEquals(1920L, ((back["displays"] as List<*>)[0] as Map<*, *>)["width"])
        // El XML de playback-info se puede leer de vuelta
        val xml = Plist.toXml(linkedMapOf("duration" to 12.5, "readyToPlay" to 1L, "r" to listOf(mapOf("start" to 0.0))))
        @Suppress("UNCHECKED_CAST") val x = Plist.parseXml(String(xml)) as Map<String, Any?>
        assertEquals(12.5, x["duration"] as Double, 0.0)
        assertEquals(1L, x["readyToPlay"])
    }

    @Test
    fun plistInfoGrandeUsaReferenciasDeDosBytes() {
        val big = (0 until 400).associate { "k$it" to it.toLong() }
        val back = Plist.parseDict(Plist.encode(big))
        assertEquals(400, back.size)
        assertEquals(399L, back["k399"])
    }

    // Valores calculados con hashlib de Python.
    @Test
    fun clavesDeVideoComoUxPlay() {
        val (k, iv) = StreamKeys.derive(ByteArray(16) { it.toByte() }, java.lang.Long.parseUnsignedLong("12345678901234567890"))
        assertEquals("94f121da840d248bf42a3631e2303bce", k.toHex())
        assertEquals("e3ac186510135c23070674d19b615097", iv.toHex())
    }

    @Test
    fun claveDeAudioMezclaConElSecretoEcdh() {
        val aes = ByteArray(16) { (50 + it).toByte() }
        val ecdh = ByteArray(32) { (100 + it).toByte() }
        val h = MessageDigest.getInstance("SHA-512").digest(aes + ecdh).copyOf(16)
        assertEquals("a56b447c409808ec17bf98eae2545c16", h.toHex())
    }

    @Test
    fun ctrContinuoEntrePaquetes() {
        val key = ByteArray(16) { it.toByte() }
        val iv = ByteArray(16) { (it * 3).toByte() }
        fun c() = Cipher.getInstance("AES/CTR/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }
        val data = ByteArray(1000) { (it * 11).toByte() }
        val whole = c().doFinal(data)
        val chunked = c()
        val out = ByteArrayOutputStream()
        for ((a, b) in listOf(0 to 37, 37 to 300, 300 to 301, 301 to 1000)) out.write(chunked.update(data, a, b - a))
        assertArrayEquals(whole, out.toByteArray())
    }

    @Test
    fun pairVerifyDeIdaYVuelta() {
        val serverSeed = ByteArray(32) { (it + 1).toByte() }
        val serverPub = Curve25519.ed25519PublicKey(serverSeed)
        val clientEdSeed = ByteArray(32) { (it + 90).toByte() }
        val clientEdPub = Curve25519.ed25519PublicKey(clientEdSeed)
        val clientEcdhPriv = ByteArray(32) { (it + 40).toByte() }
        val clientEcdhPub = Curve25519.x25519PublicKey(clientEcdhPriv)

        val pv = PairVerify(serverSeed)
        val resp = pv.step1(clientEcdhPub + clientEdPub)
        assertEquals(96, resp.size)
        val serverEcdh = resp.copyOfRange(0, 32)
        val secret = Curve25519.x25519(clientEcdhPriv, serverEcdh)
        assertArrayEquals(secret, pv.secret)
        fun sha(salt: String) = MessageDigest.getInstance("SHA-512").digest(salt.toByteArray() + secret).copyOf(16)
        val ctr = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(sha("Pair-Verify-AES-Key"), "AES"), IvParameterSpec(sha("Pair-Verify-AES-IV")))
        }
        val serverSig = ctr.update(resp.copyOfRange(32, 96)) // descifra con el mismo flujo
        assertTrue(Curve25519.ed25519Verify(serverPub, serverEcdh + clientEcdhPub, serverSig))
        // el cliente firma (su ecdh + la del servidor) y la cifra con la continuacion del flujo
        val clientSig = Curve25519.ed25519Sign(clientEdSeed, clientEcdhPub + serverEcdh)
        assertTrue(pv.step2(ctr.update(clientSig)))
        assertTrue(!PairVerify(serverSeed).also { it.step1(clientEcdhPub + clientEdPub) }.step2(ByteArray(64)))
    }

    @Test
    fun fairPlayFases() {
        val req = ByteArray(16).also { it[4] = 3; it[14] = 2 }
        val r = FairPlay.setup(req)!!
        assertEquals(142, r.size)
        assertEquals("46504c59", r.copyOfRange(0, 4).toHex())
        assertEquals(2, r[13].toInt())
        assertEquals(null, FairPlay.setup(ByteArray(16).also { it[4] = 1 }))
        val m = ByteArray(164) { it.toByte() }.also { it[4] = 3 }
        val h = FairPlay.handshake(m)!!
        assertEquals(32, h.size)
        assertEquals("46504c590301040000000014", h.copyOfRange(0, 12).toHex())
        assertArrayEquals(m.copyOfRange(144, 164), h.copyOfRange(12, 32))
    }

    private class Bits {
        val sb = StringBuilder()
        fun put(v: Int, n: Int) { for (i in n - 1 downTo 0) sb.append((v shr i) and 1) }
        fun ue(v: Int) { val x = v + 1; val len = 32 - Integer.numberOfLeadingZeros(x); put(0, len - 1); put(x, len) }
        fun bytes(): ByteArray { while (sb.length % 8 != 0) sb.append('0'); return ByteArray(sb.length / 8) { sb.substring(it * 8, it * 8 + 8).toInt(2).toByte() } }
    }

    @Test
    fun spsDa1920x1080() {
        val b = Bits()
        b.put(0x67, 8); b.put(66, 8); b.put(0, 8); b.put(30, 8)
        b.ue(0); b.ue(0); b.ue(0); b.ue(0); b.ue(1); b.put(0, 1)
        b.ue(119); b.ue(67); b.put(1, 1); b.put(1, 1)
        b.put(1, 1); b.ue(0); b.ue(0); b.ue(0); b.ue(4); b.put(0, 1)
        assertEquals(1920 to 1080, SpsParser.size(b.bytes()))
    }

    @Test
    fun txtDnsSd() {
        val t = Advertisement.encodeTxt(linkedMapOf("a" to "b", "cc" to "d"))
        assertEquals("03613d62" + "04" + "63633d64", t.toHex())
    }

    @Test
    fun servidorRtspYHttpEnElMismoPuerto() {
        val handler = object : RequestHandler {
            override fun handle(conn: Conn, req: Request): Response =
                if (req.path == "/info") Response.plist(mapOf("name" to "TV Daewoo", "echo" to req.body.size.toLong()))
                else Response(404, "Not Found")
            override fun onClosed(conn: Conn) {}
        }
        val server = RtspServer(handler, 17100)
        val port = server.start()
        try {
            Socket("127.0.0.1", port).use { s ->
                val o = s.getOutputStream()
                o.write("GET /info RTSP/1.0\r\nCSeq: 7\r\nContent-Length: 3\r\n\r\nabc".toByteArray())
                o.flush()
                val inp = s.getInputStream()
                val status = RtspServer.readLine(inp)
                assertEquals("RTSP/1.0 200 OK", status)
                val headers = HashMap<String, String>()
                while (true) { val l = RtspServer.readLine(inp)!!; if (l.isEmpty()) break; headers[l.substringBefore(':').lowercase()] = l.substringAfter(':').trim() }
                assertEquals("7", headers["cseq"])
                assertEquals("application/x-apple-binary-plist", headers["content-type"])
                val body = RtspServer.readFully(inp, headers["content-length"]!!.toInt())!!
                val d = Plist.parseDict(body)
                assertEquals("TV Daewoo", d["name"])
                assertEquals(3L, d["echo"])
                // misma conexion, ahora en HTTP/1.1
                o.write("GET /nada HTTP/1.1\r\n\r\n".toByteArray()); o.flush()
                assertEquals("HTTP/1.1 404 Not Found", RtspServer.readLine(inp))
            }
        } finally { server.stop() }
        assertNotNull(server)
    }
}
