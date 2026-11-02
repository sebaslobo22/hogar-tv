package live.hogar.homekit

import java.net.Socket
import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** El controlador (iPhone) de las pruebas: pair-setup y pair-verify completos contra el código real. */
internal class TestController(val id: String = "11111111-2222-3333-4444-555555555555") {
    val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
    val ltpk = Curve25519.ed25519PublicKey(seed)
    private fun nonce(label: String) = ByteArray(4) + label.toByteArray()
    private fun hkdf(ikm: ByteArray, salt: String, info: String) = Hkdf.sha512(ikm, salt.toByteArray(), info.toByteArray(), 32)

    /** Devuelve (clave de sesión SRP, TLV de M6 descifrado) o lanza si algo falla. */
    fun pairSetup(post: (ByteArray) -> ByteArray, code: String, accessoryId: String, accessoryLtpk: ByteArray?): ByteArray {
        val g = SrpGroup.HAP_3072_SHA512
        val m2 = Tlv8.read(post(Tlv8.of(Tlv8.STATE to byteArrayOf(1), Tlv8.METHOD to byteArrayOf(0))))
        check(m2[Tlv8.STATE]!![0].toInt() == 2) { "M2: ${m2.keys}" }
        val client = SrpClient(g, "Pair-Setup", code)
        val r = client.process(m2[Tlv8.SALT]!!, java.math.BigInteger(1, m2[Tlv8.PUBLIC_KEY]!!))
        val m4 = Tlv8.read(post(Tlv8.of(Tlv8.STATE to byteArrayOf(3), Tlv8.PUBLIC_KEY to client.publicKey.toPadded(g.nLen), Tlv8.PROOF to r.clientProof)))
        check(m4[Tlv8.ERROR] == null) { "M4 error ${m4[Tlv8.ERROR]?.get(0)}" }
        assertArrayEquals(r.expectedServerProof, m4[Tlv8.PROOF])
        val k = r.sessionKey
        val iosX = hkdf(k, "Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info")
        val sig = Curve25519.ed25519Sign(seed, iosX + id.toByteArray() + ltpk)
        val sub = Tlv8.of(Tlv8.IDENTIFIER to id.toByteArray(), Tlv8.PUBLIC_KEY to ltpk, Tlv8.SIGNATURE to sig)
        val key = hkdf(k, "Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info")
        val m6 = Tlv8.read(post(Tlv8.of(Tlv8.STATE to byteArrayOf(5), Tlv8.ENCRYPTED_DATA to ChaCha20Poly1305.seal(key, nonce("PS-Msg05"), sub))))
        check(m6[Tlv8.ERROR] == null) { "M6 error ${m6[Tlv8.ERROR]?.get(0)}" }
        val plain = ChaCha20Poly1305.open(key, nonce("PS-Msg06"), m6[Tlv8.ENCRYPTED_DATA]!!)!!
        val acc = Tlv8.read(plain)
        assertEquals(accessoryId, String(acc[Tlv8.IDENTIFIER]!!))
        if (accessoryLtpk != null) assertArrayEquals(accessoryLtpk, acc[Tlv8.PUBLIC_KEY])
        val accX = hkdf(k, "Pair-Setup-Accessory-Sign-Salt", "Pair-Setup-Accessory-Sign-Info")
        assertTrue(Curve25519.ed25519Verify(acc[Tlv8.PUBLIC_KEY]!!, accX + acc[Tlv8.IDENTIFIER]!! + acc[Tlv8.PUBLIC_KEY]!!, acc[Tlv8.SIGNATURE]!!))
        return acc[Tlv8.PUBLIC_KEY]!!
    }

    /** Devuelve el cifrador del lado del controlador, o null si el accesorio rechazó la verificación. */
    fun pairVerify(post: (ByteArray) -> ByteArray, accessoryId: String, accessoryLtpk: ByteArray): HapCipher? {
        val priv = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val pub = Curve25519.x25519PublicKey(priv)
        val m2 = Tlv8.read(post(Tlv8.of(Tlv8.STATE to byteArrayOf(1), Tlv8.PUBLIC_KEY to pub)))
        check(m2[Tlv8.STATE]!![0].toInt() == 2)
        val accPub = m2[Tlv8.PUBLIC_KEY]!!
        val shared = Curve25519.x25519(priv, accPub)
        val key = hkdf(shared, "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info")
        val sub = Tlv8.read(ChaCha20Poly1305.open(key, nonce("PV-Msg02"), m2[Tlv8.ENCRYPTED_DATA]!!)!!)
        assertEquals(accessoryId, String(sub[Tlv8.IDENTIFIER]!!))
        assertTrue(Curve25519.ed25519Verify(accessoryLtpk, accPub + sub[Tlv8.IDENTIFIER]!! + pub, sub[Tlv8.SIGNATURE]!!))
        val sig = Curve25519.ed25519Sign(seed, pub + id.toByteArray() + accPub)
        val enc = ChaCha20Poly1305.seal(key, nonce("PV-Msg03"), Tlv8.of(Tlv8.IDENTIFIER to id.toByteArray(), Tlv8.SIGNATURE to sig))
        val m4 = Tlv8.read(post(Tlv8.of(Tlv8.STATE to byteArrayOf(3), Tlv8.ENCRYPTED_DATA to enc)))
        if (m4[Tlv8.ERROR] != null) return null
        val read = hkdf(shared, "Control-Salt", "Control-Read-Encryption-Key")     // accesorio → controlador
        val write = hkdf(shared, "Control-Salt", "Control-Write-Encryption-Key")   // controlador → accesorio
        return HapCipher(outKey = write, inKey = read) // el controlador escribe con Write y lee con Read
    }
}

internal class Rig(val tv: FakeTv = FakeTv()) {
    val store = HomeKitStore(MemoryKv())
    var changed = 0
    val closed = ArrayList<String>()
    val pairing = PairingManager(store, onChanged = { changed++ }, closeController = { closed += it })
    var controller = TvController(AccessoryDb(TEST_INPUTS), store, tv)
    val written = ArrayList<Conn>()
    val router = HapRouter(store, pairing, { controller }, afterWrite = { written += it })
    val conn = Conn()

    fun post(path: String, body: ByteArray, c: Conn = conn): ByteArray {
        val r = router.handle(c, HttpReq("POST", path, emptyMap(), emptyMap(), body))
        assertEquals(200, r.status)
        return r.body
    }

    fun verified(): Conn {
        val c = Conn()
        val ctl = TestController()
        ctl.pairSetup({ post("/pair-setup", it, c) }, store.setupCode, store.deviceId, store.ltpk)
        val cc = Conn()
        checkNotNull(ctl.pairVerify({ post("/pair-verify", it, cc) }, store.deviceId, store.ltpk))
        cc.session = cc.pendingSession
        return cc
    }

    fun get(c: Conn, path: String, query: Map<String, String> = emptyMap()) =
        router.handle(c, HttpReq("GET", path, query, emptyMap(), ByteArray(0)))

    fun put(c: Conn, json: String) = router.handle(c, HttpReq("PUT", "/characteristics", emptyMap(), emptyMap(), json.toByteArray()))
}

class PairingFlowTest {
    @Test fun pairSetupThenVerifyEstablishesSessionKeys() {
        val rig = Rig()
        assertFalse(rig.store.isPaired())
        val ctl = TestController()
        ctl.pairSetup({ rig.post("/pair-setup", it) }, rig.store.setupCode, rig.store.deviceId, rig.store.ltpk)
        assertTrue(rig.store.isPaired())
        assertEquals(1, rig.changed)
        val p = rig.store.pairing(ctl.id)!!
        assertTrue(p.admin)
        assertArrayEquals(ctl.ltpk, p.ltpk)

        val c = Conn()
        val cipher = ctl.pairVerify({ rig.post("/pair-verify", it, c) }, rig.store.deviceId, rig.store.ltpk)
        assertNotNull(cipher)
        val session = c.pendingSession!!
        assertEquals(ctl.id, session.controllerId)
        // Lo que cifra el accesorio lo lee el controlador y al revés.
        val frame = session.cipher.encrypt("hola".toByteArray())
        assertEquals("hola", String(cipher!!.decrypt(frame)))
        val back = cipher.encrypt("adios".toByteArray())
        assertEquals("adios", String(session.cipher.decrypt(back)))
    }

    @Test fun secondSetupIsRefusedOncePaired() {
        val rig = Rig()
        TestController().pairSetup({ rig.post("/pair-setup", it) }, rig.store.setupCode, rig.store.deviceId, null)
        val m2 = Tlv8.read(rig.post("/pair-setup", Tlv8.of(Tlv8.STATE to byteArrayOf(1), Tlv8.METHOD to byteArrayOf(0)), Conn()))
        assertEquals(Tlv8.ERR_UNAVAILABLE, m2[Tlv8.ERROR]!![0].toInt())
    }

    @Test fun wrongCodeFailsWithAuthenticationAndDoesNotPair() {
        val rig = Rig()
        try {
            TestController().pairSetup({ rig.post("/pair-setup", it) }, "000-11-222", rig.store.deviceId, null)
            org.junit.Assert.fail()
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!, e.message!!.contains("error 2"))
        }
        assertFalse(rig.store.isPaired())
    }

    @Test fun anotherConnectionGetsBusyWhileSetupIsInProgress() {
        val rig = Rig()
        val m1 = Tlv8.of(Tlv8.STATE to byteArrayOf(1), Tlv8.METHOD to byteArrayOf(0))
        rig.post("/pair-setup", m1, Conn())
        val other = Tlv8.read(rig.post("/pair-setup", m1, Conn()))
        assertEquals(Tlv8.ERR_BUSY, other[Tlv8.ERROR]!![0].toInt())
    }

    @Test fun verifyWithUnknownControllerIsRejected() {
        val rig = Rig()
        TestController().pairSetup({ rig.post("/pair-setup", it) }, rig.store.setupCode, rig.store.deviceId, null)
        val stranger = TestController("99999999-9999-9999-9999-999999999999")
        val c = Conn()
        assertNull(stranger.pairVerify({ rig.post("/pair-verify", it, c) }, rig.store.deviceId, rig.store.ltpk))
        assertNull(c.pendingSession)
    }

    @Test fun pairingsAddListRemoveRequireAdmin() {
        val rig = Rig()
        val admin = rig.verified()
        val other = TestController("AAAAAAAA-0000-0000-0000-000000000001")
        val add = Tlv8.of(Tlv8.STATE to byteArrayOf(1), Tlv8.METHOD to byteArrayOf(3), Tlv8.IDENTIFIER to other.id.toByteArray(),
            Tlv8.PUBLIC_KEY to other.ltpk, Tlv8.PERMISSIONS to byteArrayOf(0))
        assertNull(Tlv8.read(rig.post("/pairings", add, admin))[Tlv8.ERROR])
        assertFalse(rig.store.pairing(other.id)!!.admin)

        val list = Tlv8.readList(rig.post("/pairings", Tlv8.of(Tlv8.STATE to byteArrayOf(1), Tlv8.METHOD to byteArrayOf(5)), admin))
        assertEquals(2, list.count { it.first == Tlv8.IDENTIFIER })
        assertEquals(1, list.count { it.first == Tlv8.SEPARATOR })

        // El segundo (usuario normal) no puede gestionar.
        val userConn = Conn()
        userConn.session = VerifiedSession(HapCipher(ByteArray(32), ByteArray(32)), other.id, admin = false)
        val denied = Tlv8.read(rig.post("/pairings", Tlv8.of(Tlv8.STATE to byteArrayOf(1), Tlv8.METHOD to byteArrayOf(5)), userConn))
        assertEquals(Tlv8.ERR_AUTHENTICATION, denied[Tlv8.ERROR]!![0].toInt())

        val remove = Tlv8.of(Tlv8.STATE to byteArrayOf(1), Tlv8.METHOD to byteArrayOf(4), Tlv8.IDENTIFIER to other.id.toByteArray())
        rig.post("/pairings", remove, admin)
        assertNull(rig.store.pairing(other.id))
        assertEquals(listOf(other.id), rig.closed)
    }

    @Test fun removingTheLastAdminUnpairsEverything() {
        val rig = Rig()
        val admin = rig.verified()
        val id = rig.store.pairings().single().id
        rig.post("/pairings", Tlv8.of(Tlv8.STATE to byteArrayOf(1), Tlv8.METHOD to byteArrayOf(4), Tlv8.IDENTIFIER to id.toByteArray()), admin)
        assertFalse(rig.store.isPaired())
    }

    @Test fun protectedRoutesNeedAVerifiedSession() {
        val rig = Rig()
        assertEquals(470, rig.get(Conn(), "/accessories").status)
        assertEquals(470, rig.get(Conn(), "/characteristics", mapOf("id" to "1.12")).status)
        assertEquals(204, rig.router.handle(Conn(), HttpReq("POST", "/identify", emptyMap(), emptyMap(), ByteArray(0))).status)
    }
}

class RouterTest {
    @Test fun accessoriesJsonHasTheTelevisionStructure() {
        val rig = Rig()
        val c = rig.verified()
        val r = rig.get(c, "/accessories")
        assertEquals(200, r.status)
        assertEquals("application/hap+json", r.contentType)
        @Suppress("UNCHECKED_CAST")
        val root = Json.parse(String(r.body)) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val acc = (root["accessories"] as List<Map<String, Any?>>).single()
        assertEquals(1L, acc["aid"])
        @Suppress("UNCHECKED_CAST")
        val services = acc["services"] as List<Map<String, Any?>>
        assertEquals(listOf("3E", "A2", "D8", "D9", "D9", "D9", "D9", "113"), services.map { it["type"] })
        val tv = services.first { it["type"] == "D8" }
        assertEquals(true, tv["primary"])
        assertEquals(listOf(110L, 120L, 130L, 140L, 20L), tv["linked"])
        val all = services.flatMap { @Suppress("UNCHECKED_CAST") (it["characteristics"] as List<Map<String, Any?>>) }
        val iids = all.map { it["iid"] }
        assertEquals(iids.size, iids.toSet().size)           // iid únicos en todo el accesorio
        val active = all.first { it["iid"] == 12L }
        assertEquals("B0", active["type"]); assertEquals("uint8", active["format"]); assertEquals(1L, active["value"])
        assertEquals(listOf("pr", "pw", "ev"), active["perms"])
        val mute = all.first { it["iid"] == 21L }
        assertEquals("bool", mute["format"]); assertEquals(false, mute["value"])
        val name = all.first { it["iid"] == 5L }
        assertEquals("TV Daewoo", name["value"])
        val vol = all.first { it["iid"] == 25L }
        assertEquals("percentage", vol["unit"]); assertEquals(100L, vol["maxValue"])
        assertNull(all.first { it["iid"] == 15L }["value"])   // RemoteKey es solo de escritura
    }

    @Test fun serializationEscapesAndPreservesTypes() {
        assertEquals("""{"a":1,"b":true,"c":"x\"y\\z\n","d":[1,2],"e":null}""", Json.write(linkedMapOf("a" to 1, "b" to true, "c" to "x\"y\\z\n", "d" to listOf(1, 2), "e" to null)))
        assertEquals(1.5, Json.parse("1.5"))
        assertEquals(listOf(1L, "a", true, null), Json.parse("""[1, "a", true, null]"""))
    }

    @Test fun getCharacteristicsReturnsValuesAndStatuses() {
        val rig = Rig()
        val c = rig.verified()
        val ok = rig.get(c, "/characteristics", mapOf("id" to "1.12,1.25"))
        assertEquals(200, ok.status)
        assertEquals("""{"characteristics":[{"aid":1,"iid":12,"value":1},{"aid":1,"iid":25,"value":40}]}""", String(ok.body))
        val mixed = rig.get(c, "/characteristics", mapOf("id" to "1.12,1.999,1.15"))
        assertEquals(207, mixed.status)
        assertEquals(
            """{"characteristics":[{"aid":1,"iid":12,"value":1,"status":0},{"aid":1,"iid":999,"status":-70409},{"aid":1,"iid":15,"status":-70405}]}""",
            String(mixed.body),
        )
        assertEquals(400, rig.get(c, "/characteristics", emptyMap()).status)
    }

    @Test fun putWritesDriveTheTvAndSubscriptionsAreRemembered() {
        val rig = Rig()
        val c = rig.verified()
        val r = rig.put(c, """{"characteristics":[{"aid":1,"iid":12,"value":0},{"aid":1,"iid":25,"value":65},{"aid":1,"iid":21,"value":true}]}""")
        assertEquals(204, r.status)
        assertEquals(listOf("sleep", "vol:65", "mute:true"), rig.tv.calls)
        assertEquals(listOf(c), rig.written)

        val ev = rig.put(c, """{"characteristics":[{"aid":1,"iid":12,"ev":true},{"aid":1,"iid":13,"ev":true}]}""")
        assertEquals(204, ev.status)
        assertEquals(setOf(12, 13), c.subscriptions.toSet())
        assertEquals(204, rig.put(c, """{"characteristics":[{"aid":1,"iid":12,"ev":false}]}""").status)
        assertEquals(setOf(13), c.subscriptions.toSet())
    }

    @Test fun putErrorsAreReportedPerCharacteristic() {
        val rig = Rig()
        val c = rig.verified()
        val r = rig.put(c, """{"characteristics":[{"aid":1,"iid":3,"value":"x"},{"aid":1,"iid":12,"value":7},{"aid":1,"iid":4242,"value":1},{"aid":1,"iid":3,"ev":true}]}""")
        assertEquals(207, r.status)
        assertEquals(
            """{"characteristics":[{"aid":1,"iid":3,"status":-70404},{"aid":1,"iid":12,"status":-70410},{"aid":1,"iid":4242,"status":-70409},{"aid":1,"iid":3,"status":-70406}]}""",
            String(r.body),
        )
        assertTrue(rig.tv.calls.isEmpty())
        assertEquals(400, rig.put(c, "no es json").status)
    }

    @Test fun httpParserHandlesPartialPipelinedAndQueryRequests() {
        val raw = ("GET /characteristics?id=1.12%2C1.13&ev=1 HTTP/1.1\r\nHost: x\r\n\r\n" +
            "PUT /characteristics HTTP/1.1\r\nContent-Length: 4\r\nContent-Type: application/hap+json\r\n\r\nabcd").toByteArray()
        assertNull(Http.parse(raw, 10))
        val a = Http.parse(raw)!!
        assertEquals("GET", a.req.method); assertEquals("/characteristics", a.req.path)
        assertEquals("1.12,1.13", a.req.query["id"]); assertEquals("1", a.req.query["ev"])
        val rest = raw.copyOfRange(a.consumed, raw.size)
        assertNull(Http.parse(rest, rest.size - 1))          // falta el último byte del cuerpo
        val b = Http.parse(rest)!!
        assertEquals("abcd", String(b.req.body))
        assertEquals("application/hap+json", b.req.headers["content-type"])
        val s = String(Http.serialize(HttpResp.json(200, "{}")))
        assertTrue(s.startsWith("HTTP/1.1 200 OK\r\nContent-Type: application/hap+json\r\nContent-Length: 2\r\n\r\n{}"))
        assertTrue(String(Http.event("{}")).startsWith("EVENT/1.0 200 OK\r\n"))
    }
}

class LoopbackServerTest {
    /** Servidor real en un puerto local: pair-setup y pair-verify por HTTP, y luego peticiones cifradas. */
    @Test fun fullSessionOverRealSocketsWithEvents() {
        val rig = Rig()
        val server = HapServer(rig.router, rig.pairing)
        val port = server.start()
        try {
            val ctl = TestController()
            Socket("127.0.0.1", port).use { sock ->
                val out = sock.getOutputStream()
                val inp = sock.getInputStream()
                fun plainPost(path: String, body: ByteArray): ByteArray {
                    out.write("POST $path HTTP/1.1\r\nContent-Type: application/pairing+tlv8\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray() + body)
                    out.flush()
                    return readResponse(inp, null).second
                }
                ctl.pairSetup({ plainPost("/pair-setup", it) }, rig.store.setupCode, rig.store.deviceId, rig.store.ltpk)
                val cipher = ctl.pairVerify({ plainPost("/pair-verify", it) }, rig.store.deviceId, rig.store.ltpk)!!

                fun secure(req: String): Pair<Int, ByteArray> {
                    out.write(cipher.encrypt(req.toByteArray())); out.flush()
                    return readResponse(inp, cipher)
                }
                val acc = secure("GET /accessories HTTP/1.1\r\nHost: x\r\n\r\n")
                assertEquals(200, acc.first)
                assertTrue(String(acc.second).contains("\"type\":\"D8\""))          // > 1024 bytes: varias tramas
                assertTrue(acc.second.size > 1024)

                val body = """{"characteristics":[{"aid":1,"iid":12,"ev":true}]}"""
                assertEquals(204, secure("PUT /characteristics HTTP/1.1\r\nContent-Type: application/hap+json\r\nContent-Length: ${body.length}\r\n\r\n$body").first)

                // Un cambio de estado llega como EVENT/1.0 cifrado.
                rig.tv.awake = false
                rig.controller.refresh()
                val before = rig.controller.read(12)
                assertEquals(0, before)
                server.notifyChanged(listOf(12), { rig.controller.read(it) })
                val ev = readEvent(inp, cipher)
                assertEquals("""{"characteristics":[{"aid":1,"iid":12,"value":0}]}""", ev)

                val w = """{"characteristics":[{"aid":1,"iid":25,"value":10}]}"""
                assertEquals(204, secure("PUT /characteristics HTTP/1.1\r\nContent-Length: ${w.length}\r\n\r\n$w").first)
                assertEquals(10, rig.tv.vol)
            }
        } finally {
            server.stop()
        }
    }

    private fun readResponse(inp: java.io.InputStream, cipher: HapCipher?): Pair<Int, ByteArray> {
        var buf = ByteArray(0)
        val chunk = ByteArray(2048)
        while (true) {
            val head = String(buf, Charsets.ISO_8859_1)
            val end = head.indexOf("\r\n\r\n")
            if (end >= 0) {
                val len = Regex("Content-Length: (\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
                if (buf.size >= end + 4 + len) {
                    return head.split(' ')[1].take(3).toInt() to buf.copyOfRange(end + 4, end + 4 + len)
                }
            }
            val n = inp.read(chunk)
            check(n > 0) { "conexión cerrada" }
            buf += if (cipher != null) cipher.decrypt(chunk, n) else chunk.copyOf(n)
        }
    }

    private fun readEvent(inp: java.io.InputStream, cipher: HapCipher): String {
        val (status, body) = readResponse(inp, cipher)
        assertEquals(200, status)
        return String(body)
    }
}
