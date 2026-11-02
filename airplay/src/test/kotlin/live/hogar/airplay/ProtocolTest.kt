package live.hogar.airplay

import live.hogar.core.Speaker
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.MessageDigest
import java.util.Random
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private fun hex(s: String): ByteArray = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

class PlistTest {
    // Generado con Python plistlib (FMT_BINARY): cadena UTF-16, entero negativo, real, data, listas y dicts anidados.
    private val pythonBlob = hex(
        "62706c6973743030db0102030405060708090a0b0c0d0e0f101116171819215161536269675464617461516654666c6167546c697374546e616d65536e65675270695773747265616d7353756e691001130000010000000000430001ff0809a30c12135178d11415516ba055486f67617213fffffffffffffffb23400c000000000000a11ad31b1c1d1e1f205373686b5373706654747970654f1020000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f11016010606b00430061006e0063006900f3006e0020006100f1006f081f21252a2c31363b3f424a4e50595d5e5f6365686a6b717a83858c909499bcbfc100000000000001010000000000000022000000000000000000000000000000d8",
    )

    @Test fun decodesPlistlibOutput() {
        val m = Plist.decodeDict(pythonBlob)
        assertEquals(1L, m["a"])
        assertEquals("Hogar", m["name"])
        assertEquals(true, m["flag"])
        assertEquals(false, m["f"])
        assertEquals(3.5, m["pi"])
        assertEquals(-5L, m["neg"])
        assertEquals(1L shl 40, m["big"])
        assertArrayEquals(byteArrayOf(0, 1, -1), m["data"] as ByteArray)
        assertEquals("Canción año", m["uni"])
        val list = m["list"] as List<*>
        assertEquals(listOf<Any?>(1L, "x", mapOf("k" to emptyList<Any?>())), list)
        val s0 = ((m["streams"] as List<*>)[0]) as Map<*, *>
        assertEquals(352L, s0["spf"])
        assertEquals(96L, s0["type"])
        assertArrayEquals(ByteArray(32) { it.toByte() }, s0["shk"] as ByteArray)
    }

    @Test fun roundTrip() {
        val src = linkedMapOf<String, Any?>(
            "int" to 7, "long" to (1L shl 33), "neg" to -42L, "t" to true, "f" to false,
            "dbl" to -12.25, "str" to "hola", "uni" to "añejo €", "data" to ByteArray(300) { it.toByte() },
            "list" to listOf(1, "dos", listOf(3.0)), "map" to mapOf("x" to mapOf("y" to 1)),
            "big" to (0 until 40).map { "item$it" }, // fuerza cuenta >= 15 y más de 40 objetos
        )
        val back = Plist.decodeDict(Plist.encode(src))
        assertEquals(7L, back["int"]); assertEquals(1L shl 33, back["long"]); assertEquals(-42L, back["neg"])
        assertEquals(true, back["t"]); assertEquals(false, back["f"]); assertEquals(-12.25, back["dbl"])
        assertEquals("hola", back["str"]); assertEquals("añejo €", back["uni"])
        assertArrayEquals(src["data"] as ByteArray, back["data"] as ByteArray)
        assertEquals(listOf<Any?>(1L, "dos", listOf(3.0)), back["list"])
        assertEquals(mapOf("x" to mapOf("y" to 1L)), back["map"])
        assertEquals((0 until 40).map { "item$it" }, back["big"])
    }

    @Test fun headerAndTrailer() {
        val b = Plist.encode(mapOf("k" to 1))
        assertEquals("bplist00", String(b, 0, 8))
        assertEquals(0xD1, b[8].toInt() and 0xFF) // dict de 1 entrada
        val t = b.size - 32
        assertEquals(1, b[t + 6].toInt()) // tamaño de offset
        assertEquals(1, b[t + 7].toInt()) // tamaño de referencia
        assertEquals(3L, java.nio.ByteBuffer.wrap(b, t + 8, 8).long) // dict + clave + valor
    }
}

class CryptoTest {
    private val rfcKey = ByteArray(32) { (0x80 + it).toByte() }
    private val rfcNonce = hex("07000000 40414243 44454647")
    private val rfcAad = hex("50515253 c0c1c2c3 c4c5c6c7")
    private val rfcPlain = "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.".toByteArray()

    @Test fun chacha20Poly1305MatchesRfc8439() {
        val sealed = ChaCha20Poly1305.seal(rfcKey, rfcNonce, rfcPlain, rfcAad)
        // RFC 8439 §2.8.2: inicio del texto cifrado y etiqueta
        assertTrue(sealed.toHex().startsWith("d31a8d34648e60db7b86afbc53ef7ec2"))
        assertEquals("1ae10b594f09e26a7e902ecbd0600691", sealed.copyOfRange(sealed.size - 16, sealed.size).toHex())
        assertEquals(
            "d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d63dbea45e8ca9671282fafb69da92728b" +
                "1a71de0a9e060b2905d6a5b67ecd3b3692ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7b" +
                "c3ff4def08e4b7a9de576d26586cec64b61161ae10b594f09e26a7e902ecbd0600691",
            sealed.toHex(),
        )
        assertArrayEquals(rfcPlain, ChaCha20Poly1305.open(rfcKey, rfcNonce, sealed, rfcAad))
    }

    @Test fun rejectsTamperedData() {
        val sealed = ChaCha20Poly1305.seal(rfcKey, rfcNonce, rfcPlain, rfcAad)
        sealed[3] = (sealed[3].toInt() xor 1).toByte()
        assertNull(ChaCha20Poly1305.open(rfcKey, rfcNonce, sealed, rfcAad))
        val ok = ChaCha20Poly1305.seal(rfcKey, rfcNonce, rfcPlain, rfcAad)
        assertNull(ChaCha20Poly1305.open(rfcKey, rfcNonce, ok, byteArrayOf(1)))
    }

    @Test fun matchesJdkImplementationOnRandomInputs() {
        val rnd = Random(42)
        repeat(30) { i ->
            val key = ByteArray(32).also(rnd::nextBytes)
            val nonce = ByteArray(12).also(rnd::nextBytes)
            val aad = ByteArray(rnd.nextInt(40)).also(rnd::nextBytes)
            val pt = ByteArray(rnd.nextInt(300) + (if (i == 0) 0 else 1)).also(rnd::nextBytes)
            val c = Cipher.getInstance("ChaCha20-Poly1305")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
            c.updateAAD(aad)
            assertArrayEquals(c.doFinal(pt), ChaCha20Poly1305.seal(key, nonce, pt, aad))
        }
    }

    @Test fun hkdfSha256Rfc5869TestCase1() {
        val okm = Hkdf.derive("HmacSHA256", ByteArray(22) { 0x0b }, hex("000102030405060708090a0b0c"), hex("f0f1f2f3f4f5f6f7f8f9"), 42)
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            okm.toHex(),
        )
    }

    @Test fun hkdfSha512MatchesPythonCryptography() {
        // Valores calculados con la librería `cryptography` de Python.
        assertEquals(
            "5a6cb19bcbe7d4df2dd8279f39562f7fae2dbf73eb5a4f98849c245c82b2fe96",
            Hkdf.sha512(ByteArray(64) { it.toByte() }, "Control-Salt".toByteArray(), "Control-Write-Encryption-Key".toByteArray(), 32).toHex(),
        )
        assertEquals(
            "832390086cda71fb47625bb5ceb168e4c8e26a1a16ed34d9fc7fe92c1481579338da362cb8d9f925d7cbcce0dff7098769cf15959867d571c1715450cb530137be3fb62f3cf32b84feba8f1eb1b563e20d9749b8640b8264c4b69b14ad5199115e1d609c",
            Hkdf.sha512(ByteArray(22) { 0x0b }, ByteArray(13) { it.toByte() }, ByteArray(10) { (0xf0 + it).toByte() }, 100).toHex(),
        )
    }

    @Test fun hapFramingMatchesReferenceAndRoundTrips() {
        val key = ByteArray(32) { it.toByte() }
        val data = ByteArray(1200) { (it % 200).toByte() }
        val frames = HapCipher(key, key).encrypt(data)
        assertEquals(1236, frames.size) // 2 tramas: 2+1024+16 y 2+176+16
        assertEquals(
            "6e84fc89974827d5f9e6e4ce87cc96d1ec389efd2a47a05598eab1f73ce0a824",
            MessageDigest.getInstance("SHA-256").digest(frames).toHex(),
        )
        // Descifrado en trozos arbitrarios
        val dec = HapCipher(key, key)
        val out = java.io.ByteArrayOutputStream()
        var pos = 0
        val rnd = Random(1)
        while (pos < frames.size) {
            val n = minOf(rnd.nextInt(200) + 1, frames.size - pos)
            out.write(dec.decrypt(frames.copyOfRange(pos, pos + n)))
            pos += n
        }
        assertArrayEquals(data, out.toByteArray())
    }
}

class SrpTest {
    private fun big(h: String) = BigInteger(h, 16)

    @Test fun rfc5054Appendix_B_1024bitSha1() {
        val v = SrpVectors.Rfc5054
        val c = SrpClient(SrpGroup.RFC5054_1024_SHA1, "alice", "password123", hex(v.A_PRIVATE))
        // Valores publicados en el RFC 5054, Apéndice B
        assertEquals("7556aa045aef2cdd07abaf0f665c3e818913186f", c.multiplier.toString(16))
        assertEquals("61d5e490f6f1b795", c.publicKey.toString(16).take(16))
        val r = c.process(hex(v.SALT), big(v.B))
        assertEquals("94b7555aabe9127cc58ccf4993db6cf84d16c124", r.x.toString(16))
        assertEquals("ce38b9593487da98554ed47d70a7ae5f462ef019", r.u.toString(16))
        assertEquals(v.A, c.publicKey.toString(16))
        assertTrue(r.premaster.toString(16).startsWith("b0dc82babcf30674"))
        assertEquals(v.S, r.premaster.toString(16))
        // Resto de valores contrastados con srptools
        assertEquals(v.KEY, r.sessionKey.toHex())
        assertEquals(v.M1, r.clientProof.toHex())
        assertEquals(v.M2, r.expectedServerProof.toHex())
    }

    @Test fun hapGroup3072Sha512MatchesSrptools() {
        val v = SrpVectors.Hap3072
        val c = SrpClient(SrpGroup.HAP_3072_SHA512, "Pair-Setup", "3939", hex(v.A_PRIVATE))
        assertEquals(v.KEY.length / 2, 64)
        assertEquals(v.A, c.publicKey.toString(16))
        assertEquals(v.MULT, c.multiplier.toString(16))
        val r = c.process(hex(v.SALT), big(v.B))
        assertEquals(v.X, r.x.toString(16))
        assertEquals(v.U, r.u.toString(16))
        assertEquals(v.S, r.premaster.toString(16))
        assertEquals(v.KEY, r.sessionKey.toHex())
        assertEquals(v.M1, r.clientProof.toHex())
        assertEquals(v.M2, r.expectedServerProof.toHex())
    }

    @Test fun controlKeysAreDerivedFromSharedSecret() {
        val secret = ByteArray(64) { it.toByte() }
        val (w, r) = PairingResult(secret).controlKeys()
        assertEquals("5a6cb19bcbe7d4df2dd8279f39562f7fae2dbf73eb5a4f98849c245c82b2fe96", w.toHex())
        assertEquals(32, r.size)
        assertTrue(!w.contentEquals(r))
    }

    @Test fun tlvRoundTripWithFragmentation() {
        val big = ByteArray(384) { it.toByte() }
        val enc = Tlv8.write(listOf(Tlv8.SEQ_NO to byteArrayOf(3), Tlv8.PUBLIC_KEY to big, Tlv8.PROOF to ByteArray(64) { 9 }))
        // 384 bytes → 255 + 129
        assertEquals(0x03, enc[3 + 0].toInt() and 0xFF) // tag de la clave pública tras SeqNo (3 bytes)
        assertEquals(255, enc[4].toInt() and 0xFF)
        val dec = Tlv8.read(enc)
        assertArrayEquals(big, dec[Tlv8.PUBLIC_KEY])
        assertArrayEquals(byteArrayOf(3), dec[Tlv8.SEQ_NO])
        assertEquals(64, dec[Tlv8.PROOF]!!.size)
    }
}

class AlacRtpTest {
    @Test fun verbatimFrameIsBitExact() {
        // 2 tramas: L=0x1234 R=0xFEDC / L=0x0001 R=0x8000 (s16le); esperado calculado aparte con Python.
        val pcm = hex("3412 dcfe 0100 0080")
        assertEquals("2000022469fdb800030001c0", Alac.encodeVerbatim(pcm, 0, 2).toHex())
    }

    @Test fun fullPacketHasExpectedSizeAndDecodesBack() {
        val rnd = Random(7)
        val pcm = ByteArray(352 * 4).also(rnd::nextBytes)
        val frame = AlacEncoder.encode(pcm)
        assertEquals(1412, frame.size) // 23 + 352*32 + 3 bits = 11290 bits
        // Decodificador de referencia mínimo (ALACDecoder, caso ID_CPE con escape)
        var bit = 0
        fun read(n: Int): Int {
            var v = 0
            repeat(n) { v = (v shl 1) or ((frame[bit shr 3].toInt() shr (7 - (bit and 7))) and 1); bit++ }
            return v
        }
        assertEquals(1, read(3)); assertEquals(0, read(4)); assertEquals(0, read(12))
        assertEquals(1, read(4)) // sin parcial, sin desplazamiento, escape
        val out = ByteArray(pcm.size)
        for (i in 0 until 352 * 2) {
            val s = read(16)
            out[i * 2] = s.toByte(); out[i * 2 + 1] = (s shr 8).toByte()
        }
        assertEquals(7, read(3))
        assertArrayEquals(pcm, out)
    }

    @Test fun pcmEncoderSwapsToNetworkOrder() {
        val pcm = ByteArray(352 * 4) { it.toByte() }
        val out = PcmEncoder.encode(pcm)
        assertEquals(1, out[0].toInt()); assertEquals(0, out[1].toInt())
        assertEquals(3, out[2].toInt()); assertEquals(2, out[3].toInt())
        assertEquals(352 * 4, out.size)
    }

    @Test fun audioHeaderLayout() {
        val h = RtpPackets.audioHeader(0xABCD, 0x01020304L, 0xDEADBEEFL, first = true)
        assertEquals("80e0abcd01020304deadbeef", h.toHex())
        assertEquals("8060abcd01020304deadbeef", RtpPackets.audioHeader(0xABCD, 0x01020304L, 0xDEADBEEFL, false).toHex())
    }

    @Test fun syncPacketLayout() {
        val p = RtpPackets.sync(true, 1000, NtpTime(0xE0000000L, 0x80000000L), 67150)
        assertEquals(20, p.size)
        assertEquals("90d40007", p.copyOf(4).toHex())
        assertEquals("000003e8" + "e0000000" + "80000000" + "0001064e", p.copyOfRange(4, 20).toHex())
        assertEquals(0x80, RtpPackets.sync(false, 0, NtpTime(0, 0), 0)[0].toInt() and 0xFF)
    }

    @Test fun timingResponseEchoesClientSendTime() {
        val req = ByteArray(32)
        req[0] = 0x80.toByte(); req[1] = 0xD2.toByte()
        for (i in 24 until 32) req[i] = (i - 23).toByte() // envío del cliente: 01..08
        val resp = RtpPackets.timingResponse(req, NtpTime(0x11223344L, 0x55667788L))
        assertEquals(32, resp.size)
        assertEquals("80d30007", resp.copyOf(4).toHex())
        assertEquals("0000000000000000".take(8), resp.copyOfRange(4, 8).toHex())
        assertEquals("0102030405060708", resp.copyOfRange(8, 16).toHex())
        assertEquals("1122334455667788", resp.copyOfRange(16, 24).toHex())
        assertEquals("1122334455667788", resp.copyOfRange(24, 32).toHex())
    }

    @Test fun retransmitRequestAndResponse() {
        val rq = hex("80d5 0001 1234 0003")
        assertEquals(0x1234 to 3, RtpPackets.parseRetransmitRequest(rq, rq.size))
        assertNull(RtpPackets.parseRetransmitRequest(hex("80d4 0001 1234 0003"), 8))
        val original = RtpPackets.audioHeader(0x1234, 5, 6, false) + byteArrayOf(9, 9)
        val resp = RtpPackets.retransmitResponse(original)
        assertEquals("80d61234", resp.copyOf(4).toHex())
        assertArrayEquals(original, resp.copyOfRange(4, resp.size))
    }

    @Test fun volumeMapping() {
        assertEquals(-144f, AirPlayOutput.volumeToDb(0f))
        assertEquals(0f, AirPlayOutput.volumeToDb(1f))
        assertEquals(-15f, AirPlayOutput.volumeToDb(0.5f), 0.0001f)
        assertEquals(-30f, AirPlayOutput.volumeToDb(0.001f), 0.05f)
    }
}

class DiscoveryMergeTest {
    private fun rec(type: String, name: String, host: String, txt: Map<String, String>, port: Int = 7000) =
        MdnsRecord(type, name, host, port, txt)

    @Test fun mergesAirplayAndRaopAndGroupsStereoPair() {
        val ap = SpeakerMerger.TYPE_AIRPLAY
        val raop = SpeakerMerger.TYPE_RAOP
        val records = listOf(
            rec(ap, "Sala", "10.0.0.2", mapOf("deviceid" to "AA:BB:CC:00:00:01", "model" to "AudioAccessory5,1", "features" to "0x4A7FCA00,0x3C356BD0", "gid" to "G1", "igl" to "1", "srcvers" to "1005.13.2")),
            rec(raop, "AABBCC000001@Sala", "10.0.0.2", mapOf("am" to "AudioAccessory5,1", "ft" to "0x4A7FCA00,0x3C356BD0", "cn" to "0,1,2,3")),
            rec(ap, "Sala (2)", "10.0.0.3", mapOf("deviceid" to "AA:BB:CC:00:00:02", "model" to "AudioAccessory5,1", "features" to "0x4A7FCA00,0x3C356BD0", "gid" to "G1", "igl" to "0")),
            rec(raop, "AABBCC000002@Sala (2)", "10.0.0.3", mapOf("am" to "AudioAccessory5,1")),
            rec(ap, "Barra", "10.0.0.9", mapOf("deviceid" to "11:22:33:44:55:66", "model" to "HW-Q990D", "features" to "0xC05F8A00,0x1C340", "gid" to "G9")),
        )
        val list = SpeakerMerger.merge(records)
        assertEquals(2, list.size)
        val pair = list.first { it.id.startsWith("par:") }
        assertEquals("Sala", pair.name)
        assertEquals("10.0.0.2", pair.host)
        assertEquals("AudioAccessory5,1", pair.model)
        assertEquals("AA:BB:CC:00:00:01", pair.txt[SpeakerMerger.KEY_LEADER])
        val members = SpeakerMerger.membersOf(pair)
        assertEquals(listOf("10.0.0.2", "10.0.0.3"), members.map { it.host })
        val bar = list.first { it.name == "Barra" }
        assertEquals("11:22:33:44:55:66", bar.id)
        assertEquals("0xC05F8A00,0x1C340", bar.txt["features"])
    }

    @Test fun mergedTxtHasHomogeneousKeys() {
        val l = SpeakerMerger.merge(
            listOf(
                rec(SpeakerMerger.TYPE_AIRPLAY, "X", "1.1.1.1", mapOf("deviceid" to "AA:AA:AA:AA:AA:AA", "features" to "0x200")),
                rec(SpeakerMerger.TYPE_RAOP, "AAAAAAAAAAAA@X", "1.1.1.1", mapOf("am" to "Foo1,1", "vs" to "366.0", "sf" to "0x4")),
            ),
        )
        assertEquals(1, l.size)
        assertEquals("Foo1,1", l[0].model)
        assertEquals("366.0", l[0].txt["srcvers"])
        assertEquals("0x4", l[0].txt["flags"])
    }

    @Test fun skipsDevicesWithoutAudio() {
        val l = SpeakerMerger.merge(listOf(rec(SpeakerMerger.TYPE_AIRPLAY, "TV", "1.1.1.1", mapOf("deviceid" to "AA:AA:AA:AA:AA:AA", "features" to "0x1"))))
        assertTrue(l.isEmpty())
    }

    @Test fun leaderPreference() {
        val a = Speaker("A", "Sala", "h1", 7000, null, mapOf("igl" to "0"))
        val b = Speaker("B", "Sala (2)", "h2", 7000, null, mapOf("igl" to "1"))
        assertEquals("B", SpeakerMerger.leaderFirst(listOf(a, b)).first().id)
        val c = Speaker("C", "Sala (2)", "h3", 7000, null, emptyMap())
        val d = Speaker("D", "Sala", "h4", 7000, null, emptyMap())
        assertEquals("D", SpeakerMerger.leaderFirst(listOf(c, d)).first().id)
    }

    @Test fun factoryTreatsSeparatePairMembersAsOneTarget() {
        val a = Speaker("A", "Sala", "h1", 7000, null, mapOf("gid" to "G", "igl" to "1"))
        val b = Speaker("B", "Sala (2)", "h2", 7000, null, mapOf("gid" to "G"))
        val t = TargetResolver.resolve(listOf(a, b))
        assertEquals(1, t.size)
        assertEquals(listOf("A", "B"), t[0].candidates.map { it.id })
    }
}
