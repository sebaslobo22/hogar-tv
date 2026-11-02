package live.hogar.homekit

import java.math.BigInteger
import java.security.MessageDigest
import java.util.Random
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Tlv8Test {
    @Test fun roundTripWithFragmentation() {
        val big = ByteArray(700) { (it % 251).toByte() }
        val data = Tlv8.of(Tlv8.STATE to byteArrayOf(3), Tlv8.ENCRYPTED_DATA to big, Tlv8.ERROR to ByteArray(0))
        // 700 = 255 + 255 + 190 → 3 trozos con la misma etiqueta
        assertEquals(3 + (700 + 3 * 2) + 2, data.size)
        val m = Tlv8.read(data)
        assertArrayEquals(byteArrayOf(3), m[Tlv8.STATE])
        assertArrayEquals(big, m[Tlv8.ENCRYPTED_DATA])
        assertEquals(0, m[Tlv8.ERROR]!!.size)
    }

    @Test fun listsKeepSeparatorsAndOrder() {
        val items = listOf(
            Tlv8.IDENTIFIER to "a".toByteArray(), Tlv8.PERMISSIONS to byteArrayOf(1),
            Tlv8.SEPARATOR to ByteArray(0),
            Tlv8.IDENTIFIER to "b".toByteArray(), Tlv8.PERMISSIONS to byteArrayOf(0),
        )
        val back = Tlv8.readList(Tlv8.write(items))
        assertEquals(items.map { it.first }, back.map { it.first })
        assertEquals("b", String(back[3].second))
    }

    @Test fun twoAdjacentFullChunksOfDifferentItemsAreNotMerged() {
        val a = ByteArray(255) { 1 }
        val data = Tlv8.write(listOf(7 to a)) + Tlv8.write(listOf(7 to byteArrayOf(9)))
        // un valor de exactamente 255 bytes seguido de otro con la misma etiqueta se lee como uno solo (así lo define TLV8)
        assertEquals(256, Tlv8.read(data)[7]!!.size)
    }
}

class CryptoTest {
    private val rfcKey = ByteArray(32) { (0x80 + it).toByte() }
    private val rfcNonce = hex("07000000 40414243 44454647")
    private val rfcAad = hex("50515253 c0c1c2c3 c4c5c6c7")
    private val rfcPlain = "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.".toByteArray()

    @Test fun chacha20Poly1305MatchesRfc8439() {
        val sealed = ChaCha20Poly1305.seal(rfcKey, rfcNonce, rfcPlain, rfcAad)
        assertEquals(
            "d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d63dbea45e8ca9671282fafb69da92728b" +
                "1a71de0a9e060b2905d6a5b67ecd3b3692ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7b" +
                "c3ff4def08e4b7a9de576d26586cec64b61161ae10b594f09e26a7e902ecbd0600691",
            sealed.toHex(),
        )
        assertArrayEquals(rfcPlain, ChaCha20Poly1305.open(rfcKey, rfcNonce, sealed, rfcAad))
        sealed[3] = (sealed[3].toInt() xor 1).toByte()
        assertNull(ChaCha20Poly1305.open(rfcKey, rfcNonce, sealed, rfcAad))
    }

    @Test fun matchesJdkImplementationOnRandomInputs() {
        val rnd = Random(42)
        repeat(20) { i ->
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

    @Test fun hkdfRfc5869AndSha512() {
        val okm = Hkdf.derive("HmacSHA256", ByteArray(22) { 0x0b }, hex("000102030405060708090a0b0c"), hex("f0f1f2f3f4f5f6f7f8f9"), 42)
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", okm.toHex())
        // Calculado con la librería `cryptography` de Python.
        assertEquals(
            "5a6cb19bcbe7d4df2dd8279f39562f7fae2dbf73eb5a4f98849c245c82b2fe96",
            Hkdf.sha512(ByteArray(64) { it.toByte() }, "Control-Salt".toByteArray(), "Control-Write-Encryption-Key".toByteArray(), 32).toHex(),
        )
    }

    @Test fun hapFramingRoundTripsAcrossArbitraryChunks() {
        val key = ByteArray(32) { it.toByte() }
        val data = ByteArray(1200) { (it % 200).toByte() }
        val frames = HapCipher(key, key).encrypt(data)
        assertEquals(1236, frames.size) // 2+1024+16 y 2+176+16
        assertEquals("6e84fc89974827d5f9e6e4ce87cc96d1ec389efd2a47a05598eab1f73ce0a824", MessageDigest.getInstance("SHA-256").digest(frames).toHex())
        // La longitud (2 bytes LE) es la del texto en claro: 0x0400 = 1024
        assertEquals(0x00, frames[0].toInt()); assertEquals(0x04, frames[1].toInt())
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

    @Test fun nonceIsFourZerosThenLittleEndianCounter() {
        assertEquals("000000000100000000000000", HapCipher.nonce(1).toHex())
        assertEquals("000000000201000000000000", HapCipher.nonce(0x102).toHex())
    }

    @Test fun framingRejectsTamperingAndOversizedFrames() {
        val key = ByteArray(32) { 5 }
        val frames = HapCipher(key, key).encrypt(ByteArray(10) { 1 })
        frames[5] = (frames[5].toInt() xor 1).toByte()
        try { HapCipher(key, key).decrypt(frames); org.junit.Assert.fail() } catch (_: HapException) {}
        try { HapCipher(key, key).decrypt(byteArrayOf(0xFF.toByte(), 0x7F, 0, 0)); org.junit.Assert.fail() } catch (_: HapException) {}
    }
}

class SrpTest {
    private fun big(h: String) = BigInteger(h, 16)

    @Test fun rfc5054AppendixBServerSide() {
        val v = SrpVectors.Rfc5054
        val bPriv = hex("E487CB59D31AC550471E81F00F6928E01DDA08E974A004F49E61F5D105284D20")
        val s = SrpServer(SrpGroup.RFC5054_1024_SHA1, "alice", "password123", hex(v.SALT), bPriv)
        assertEquals(v.B, s.publicKey.toString(16))        // B del RFC 5054
        val m2 = s.verify(hex(v.A), hex(v.M1))
        assertNotNull(m2)
        assertEquals(v.M2, m2!!.toHex())
        assertEquals(v.KEY, s.sessionKey!!.toHex())
    }

    @Test fun rfc5054AppendixBClientStillMatches() {
        val v = SrpVectors.Rfc5054
        val c = SrpClient(SrpGroup.RFC5054_1024_SHA1, "alice", "password123", hex(v.A_PRIVATE))
        val r = c.process(hex(v.SALT), big(v.B))
        assertEquals(v.U, r.u.toString(16))
        assertEquals(v.S, r.premaster.toString(16))
        assertEquals(v.M1, r.clientProof.toHex())
    }

    @Test fun hapGroup3072ServerAgreesWithClient() {
        val g = SrpGroup.HAP_3072_SHA512
        repeat(3) {
            val s = SrpServer(g, "Pair-Setup", "482-91-736")
            val c = SrpClient(g, "Pair-Setup", "482-91-736")
            val r = c.process(s.salt, s.publicKey)
            val m2 = s.verify(c.publicKey.toPadded(g.nLen), r.clientProof)
            assertNotNull(m2)
            assertArrayEquals(r.expectedServerProof, m2)
            assertArrayEquals(r.sessionKey, s.sessionKey)
        }
    }

    @Test fun wrongCodeIsRejected() {
        val g = SrpGroup.HAP_3072_SHA512
        val s = SrpServer(g, "Pair-Setup", "482-91-736")
        val c = SrpClient(g, "Pair-Setup", "482-91-737")
        val r = c.process(s.salt, s.publicKey)
        assertNull(s.verify(c.publicKey.toPadded(g.nLen), r.clientProof))
        assertNull(s.sessionKey)
    }

    @Test fun zeroClientKeyIsRejected() {
        val g = SrpGroup.HAP_3072_SHA512
        val s = SrpServer(g, "Pair-Setup", "482-91-736")
        assertNull(s.verify(ByteArray(384), ByteArray(64)))
        assertNull(s.verify(g.n.toBytes(), ByteArray(64)))
    }
}

class CurveTest {
    @Test fun x25519Rfc7748() {
        val r1 = Curve25519.x25519(
            hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4"),
            hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c"),
        )
        assertEquals("c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552", r1.toHex())
        val aPriv = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val bPriv = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val k = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"
        assertEquals(k, Curve25519.x25519(aPriv, Curve25519.x25519PublicKey(bPriv)).toHex())
    }

    @Test fun ed25519Rfc8032() {
        val seed = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        val pub = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        assertArrayEquals(pub, Curve25519.ed25519PublicKey(seed))
        val sig = Curve25519.ed25519Sign(seed, ByteArray(0))
        assertEquals("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b", sig.toHex())
        assertTrue(Curve25519.ed25519Verify(pub, ByteArray(0), sig))
        assertFalse(Curve25519.ed25519Verify(pub, byteArrayOf(1), sig))
    }
}

class SetupTest {
    @Test fun codesAreWellFormedAndNeverTrivial() {
        val rnd = java.security.SecureRandom()
        val seen = HashSet<String>()
        repeat(500) {
            val c = Setup.newSetupCode(rnd)
            assertTrue(c, Setup.isValidCode(c))
            seen += c
        }
        assertTrue(seen.size > 450)
        for (bad in listOf("123-45-678", "876-54-321", "000-00-000", "111-11-111", "99-99-9999", "12345678"))
            assertFalse(bad, Setup.isValidCode(bad))
    }

    @Test fun setupIdAndDeviceIdFormats() {
        assertTrue(Regex("[A-Z0-9]{4}").matches(Setup.newSetupId()))
        assertTrue(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}").matches(Setup.newDeviceId()))
    }

    @Test fun payloadMatchesKnownHapNodeJsExample() {
        // Ejemplo de HAP-NodeJS (Bridge, código 031-45-213, IP, setup ID SBL3): X-HM://0023ISYYLSBL3.
        assertEquals("X-HM://0023ISYYLSBL3", Setup.payload("031-45-213", 2, Setup.FLAG_IP, "SBL3"))
    }

    @Test fun payloadForTelevisionHasNineBase36DigitsAndSetupId() {
        val p = Setup.payload("482-91-736", Setup.CATEGORY_TELEVISION, Setup.FLAG_IP, "HK7Z")
        assertEquals("X-HM://00UQ7TEVSHK7Z", p)   // calculado aparte con Python
        // Decodifica: versión 0, categoría 31, banderas 2, código 48291736
        val v = p.removePrefix("X-HM://").take(9).toLong(36)
        assertEquals(31L, (v shr 31) and 0xFF)
        assertEquals(2L, (v shr 27) and 0xF)
        assertEquals(48291736L, v and ((1L shl 27) - 1))
        assertEquals(0L, v shr 43)
    }

    @Test fun setupHashIsBase64OfFirstFourBytesOfSha512() {
        assertEquals("XIonQA==", Setup.setupHash("7OSX", "AA:BB:CC:DD:EE:FF"))  // calculado aparte con Python
        assertEquals("keRIFA==", Setup.setupHash("HK7Z", "A2:3B:4C:5D:6E:7F"))
    }
}

class QrTest {
    @Test fun reedSolomonMatchesThonkyHelloWorldExample() {
        // "HELLO WORLD" 1-M: datos y corrección publicados en el tutorial de Thonky.
        val data = byteArrayOf(32, 91, 11, 120, 209.toByte(), 114, 220.toByte(), 77, 67, 64, 236.toByte(), 17, 236.toByte(), 17, 236.toByte(), 17)
        val ec = Qr.reedSolomon(data, 10).map { it.toInt() and 0xFF }
        assertEquals(listOf(196, 35, 39, 119, 235, 215, 231, 226, 93, 23), ec)
    }

    @Test fun matrixHasQrStructure() {
        val m = Qr.encode("X-HM://0023ISYYLSBL3")
        assertEquals(25, m.size)              // 20 bytes + 2 de cabecera caben en la versión 2-M
        fun finder(x0: Int, y0: Int) {
            for (d in 0..6) {
                assertTrue(m[y0][x0 + d]); assertTrue(m[y0 + 6][x0 + d]); assertTrue(m[y0 + d][x0]); assertTrue(m[y0 + d][x0 + 6])
            }
            for (d in 1..5) { assertFalse(m[y0 + 1][x0 + d]); assertFalse(m[y0 + 5][x0 + d]) }
            for (y in 2..4) for (x in 2..4) assertTrue(m[y0 + y][x0 + x])
        }
        finder(0, 0); finder(18, 0); finder(0, 18)
        for (i in 8..16) assertEquals(i % 2 == 0, m[6][i])   // sincronía horizontal
        for (i in 8..16) assertEquals(i % 2 == 0, m[i][6])   // y vertical
        assertTrue(m[25 - 8][8])                              // módulo oscuro
        assertTrue(m[18][18]); assertFalse(m[17][17]); assertTrue(m[16][16])   // patrón de alineación
    }

    @Test fun formatInformationIsValidBchForLevelM() {
        val m = Qr.encode("X-HM://0023ISYYLSBL3")
        // Primera copia: columna 8 filas 0-5, 7, 8 y fila 8 columnas 7, 5..0 (bit 0 = fila 0).
        var bits = 0
        val ys = listOf(0, 1, 2, 3, 4, 5, 7)
        for ((i, y) in ys.withIndex()) if (m[y][8]) bits = bits or (1 shl i)
        if (m[8][8]) bits = bits or (1 shl 7)
        if (m[8][7]) bits = bits or (1 shl 8)
        for (i in 9..14) if (m[8][14 - i]) bits = bits or (1 shl i)
        val data = (bits xor 0x5412) ushr 10
        assertEquals(0, data ushr 3)   // nivel M = 00
        var rem = data
        for (i in 0 until 10) rem = (rem shl 1) xor ((rem ushr 9) * 0x537)
        assertEquals((data shl 10) or rem, bits xor 0x5412)
        // La segunda copia es idéntica.
        var second = 0
        for (i in 0..7) if (m[8][24 - i]) second = second or (1 shl i)
        for (i in 8..14) if (m[25 - 15 + i][8]) second = second or (1 shl i)
        assertEquals(bits, second)
    }

    @Test fun dataAndEcSurviveRoundTripThroughMatrix() {
        // Lee los módulos en el orden de colocación, quita la máscara y comprueba datos + EC.
        val text = "X-HM://0023ISYYLSBL3"
        val m = Qr.encode(text)
        val n = m.size
        var bits = 0
        for ((i, y) in listOf(0, 1, 2, 3, 4, 5, 7).withIndex()) if (m[y][8]) bits = bits or (1 shl i)
        if (m[8][8]) bits = bits or (1 shl 7)
        if (m[8][7]) bits = bits or (1 shl 8)
        for (i in 9..14) if (m[8][14 - i]) bits = bits or (1 shl i)
        val mask = ((bits xor 0x5412) ushr 10) and 7
        val fn = Array(n) { BooleanArray(n) }
        fun mark(x0: Int, y0: Int, w: Int, h: Int) { for (y in y0 until y0 + h) for (x in x0 until x0 + w) if (x in 0 until n && y in 0 until n) fn[y][x] = true }
        mark(0, 0, 9, 9); mark(n - 8, 0, 8, 9); mark(0, n - 8, 9, 8)
        mark(6, 0, 1, n); mark(0, 6, n, 1); mark(16, 16, 5, 5)
        val out = ArrayList<Boolean>()
        var right = n - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vert in 0 until n) for (j in 0..1) {
                val x = right - j
                val y = if (((right + 1) and 2) == 0) n - 1 - vert else vert
                if (fn[y][x]) continue
                val inv = when (mask) {
                    0 -> (x + y) % 2 == 0; 1 -> y % 2 == 0; 2 -> x % 3 == 0; 3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0; 5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0; else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                }
                out += m[y][x] != inv
            }
            right -= 2
        }
        val cw = ByteArray(44) { i -> var b = 0; for (k in 0..7) if (out[i * 8 + k]) b = b or (0x80 shr k); b.toByte() }
        // Modo bytes (0100), longitud 20, y luego el texto.
        assertEquals(0x41, cw[0].toInt() and 0xFF)
        assertEquals(0x4, (cw[1].toInt() and 0xFF) ushr 4)     // 20 = 0x14: nibble alto de la longitud es 1; el bajo, 4
        val bytes = text.toByteArray()
        for (i in bytes.indices) {
            val b = ((cw[i + 1].toInt() and 0x0F) shl 4) or ((cw[i + 2].toInt() and 0xF0) ushr 4)
            assertEquals("byte $i", bytes[i].toInt() and 0xFF, b)
        }
        // Los 16 últimos son la corrección de errores de los 28 de datos.
        val ec = Qr.reedSolomon(cw.copyOf(28), 16)
        assertArrayEquals(ec, cw.copyOfRange(28, 44))
    }

    @Test fun goldenMatrixForHomeKitPayload() {
        val m = Qr.encode("X-HM://00UQ7TEVSHK7Z")
        val rows = m.joinToString("\n") { r -> r.joinToString("") { if (it) "1" else "0" } }
        assertEquals(GOLDEN_HASH, MessageDigest.getInstance("SHA-256").digest(rows.toByteArray()).toHex())
    }

    @Test fun rejectsTextThatDoesNotFit() {
        try { Qr.encode(ByteArray(200)); org.junit.Assert.fail() } catch (_: IllegalArgumentException) {}
        assertEquals(41, Qr.encode(ByteArray(100) { 'a'.code.toByte() }).size)  // versión 6
    }

    private companion object {
        // Matriz verificada con el detector de QR de CoreImage (decodifica a la misma carga).
        const val GOLDEN_HASH = "90ea80777805ae549a7112f590b06b989b0e2a0a25f99fcf44c98146a5a30c37"
    }
}
