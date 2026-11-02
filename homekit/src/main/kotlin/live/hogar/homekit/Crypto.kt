package live.hogar.homekit

import java.math.BigInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** HKDF (RFC 5869) con HMAC-SHA512, como usa HAP (`hkdf_expand` en pyatv/auth/hap_srp.py). */
internal object Hkdf {
    fun sha512(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray =
        derive("HmacSHA512", ikm, salt, info, length)

    fun derive(alg: String, ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance(alg)
        val hashLen = mac.macLength
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(hashLen) else salt, alg))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, alg))
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            counter++
        }
        return out
    }
}

/**
 * ChaCha20-Poly1305 AEAD (RFC 8439) en Kotlin puro.
 *
 * Se implementa a mano para no depender de qué proveedor JCE trae cada Android TV
 * (el nombre del algoritmo y su disponibilidad varían) y para poder reutilizar la
 * misma clave con contadores propios, como exige AirPlay.
 */
internal object ChaCha20Poly1305 {
    const val TAG_SIZE = 16

    fun seal(key: ByteArray, nonce12: ByteArray, plain: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        require(key.size == 32 && nonce12.size == 12)
        val otk = chachaBlock(key, 0, nonce12).copyOf(32)
        val ct = xorStream(key, nonce12, 1, plain)
        val tag = poly1305(otk, macData(aad, ct))
        return ct + tag
    }

    /** Devuelve el texto en claro o `null` si la etiqueta no coincide. */
    fun open(key: ByteArray, nonce12: ByteArray, sealed: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray? {
        require(key.size == 32 && nonce12.size == 12)
        if (sealed.size < TAG_SIZE) return null
        val ct = sealed.copyOf(sealed.size - TAG_SIZE)
        val tag = sealed.copyOfRange(sealed.size - TAG_SIZE, sealed.size)
        val otk = chachaBlock(key, 0, nonce12).copyOf(32)
        val expected = poly1305(otk, macData(aad, ct))
        var diff = 0
        for (i in 0 until TAG_SIZE) diff = diff or (expected[i].toInt() xor tag[i].toInt())
        if (diff != 0) return null
        return xorStream(key, nonce12, 1, ct)
    }

    private fun macData(aad: ByteArray, ct: ByteArray): ByteArray {
        fun pad(n: Int) = (16 - n % 16) % 16
        val out = ByteArray(aad.size + pad(aad.size) + ct.size + pad(ct.size) + 16)
        System.arraycopy(aad, 0, out, 0, aad.size)
        val o2 = aad.size + pad(aad.size)
        System.arraycopy(ct, 0, out, o2, ct.size)
        val o3 = o2 + ct.size + pad(ct.size)
        putLongLe(out, o3, aad.size.toLong())
        putLongLe(out, o3 + 8, ct.size.toLong())
        return out
    }

    private fun putLongLe(b: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) b[off + i] = (v ushr (8 * i)).toByte()
    }

    private fun le32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun xorStream(key: ByteArray, nonce: ByteArray, startCounter: Int, data: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        var counter = startCounter
        var pos = 0
        while (pos < data.size) {
            val block = chachaBlock(key, counter++, nonce)
            val n = minOf(64, data.size - pos)
            for (i in 0 until n) out[pos + i] = (data[pos + i].toInt() xor block[i].toInt()).toByte()
            pos += n
        }
        return out
    }

    private fun chachaBlock(key: ByteArray, counter: Int, nonce: ByteArray): ByteArray {
        val s = IntArray(16)
        s[0] = 0x61707865; s[1] = 0x3320646e; s[2] = 0x79622d32; s[3] = 0x6b206574
        for (i in 0 until 8) s[4 + i] = le32(key, i * 4)
        s[12] = counter
        for (i in 0 until 3) s[13 + i] = le32(nonce, i * 4)
        val x = s.copyOf()
        fun qr(a: Int, b: Int, c: Int, d: Int) {
            x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] xor x[a], 16)
            x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] xor x[c], 12)
            x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] xor x[a], 8)
            x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] xor x[c], 7)
        }
        repeat(10) {
            qr(0, 4, 8, 12); qr(1, 5, 9, 13); qr(2, 6, 10, 14); qr(3, 7, 11, 15)
            qr(0, 5, 10, 15); qr(1, 6, 11, 12); qr(2, 7, 8, 13); qr(3, 4, 9, 14)
        }
        val out = ByteArray(64)
        for (i in 0 until 16) {
            val v = x[i] + s[i]
            out[i * 4] = v.toByte(); out[i * 4 + 1] = (v ushr 8).toByte()
            out[i * 4 + 2] = (v ushr 16).toByte(); out[i * 4 + 3] = (v ushr 24).toByte()
        }
        return out
    }

    private val P1305 = BigInteger.ONE.shiftLeft(130).subtract(BigInteger.valueOf(5))

    private fun leToBig(b: ByteArray, off: Int, len: Int): BigInteger {
        val be = ByteArray(len)
        for (i in 0 until len) be[len - 1 - i] = b[off + i]
        return BigInteger(1, be)
    }

    fun poly1305(key: ByteArray, msg: ByteArray): ByteArray {
        val clamp = BigInteger("0ffffffc0ffffffc0ffffffc0fffffff", 16)
        val r = leToBig(key, 0, 16).and(clamp)
        val s = leToBig(key, 16, 16)
        var acc = BigInteger.ZERO
        var pos = 0
        while (pos < msg.size) {
            val n = minOf(16, msg.size - pos)
            // El byte extra 0x01 se añade justo después del último byte del bloque.
            val block = ByteArray(n + 1)
            System.arraycopy(msg, pos, block, 0, n)
            block[n] = 1
            val m = leToBig(block, 0, n + 1)
            acc = acc.add(m).multiply(r).mod(P1305)
            pos += n
        }
        acc = acc.add(s).and(BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE))
        val out = ByteArray(16)
        val bytes = acc.toByteArray() // big-endian, puede traer cero inicial
        for (i in 0 until 16) {
            val idx = bytes.size - 1 - i
            out[i] = if (idx >= 0) bytes[idx] else 0
        }
        return out
    }
}

/**
 * Cifrado de tramas HAP (HAP IP spec 5.2.2, igual que `HAPSession` de pyatv): trozos de
 * hasta 1024 bytes, longitud de 2 bytes little-endian como AAD, nonce = contador de
 * 8 bytes little-endian precedido de 4 bytes a cero.
 */
internal class HapCipher(private val outKey: ByteArray, private val inKey: ByteArray) {
    private var outCounter = 0L
    private var inCounter = 0L
    private var pending = ByteArray(0)

    fun encrypt(data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(data.size + 64)
        var pos = 0
        while (pos < data.size) {
            val n = minOf(FRAME, data.size - pos)
            val len = byteArrayOf(n.toByte(), (n shr 8).toByte())
            out.write(len)
            out.write(ChaCha20Poly1305.seal(outKey, nonce(outCounter++), data.copyOfRange(pos, pos + n), len))
            pos += n
        }
        return out.toByteArray()
    }

    /** Acumula bytes del socket y devuelve todo el texto en claro ya completo. Lanza si la etiqueta falla. */
    fun decrypt(chunk: ByteArray, length: Int = chunk.size): ByteArray {
        pending += chunk.copyOf(length)
        val out = java.io.ByteArrayOutputStream()
        while (pending.size >= 2) {
            val n = (pending[0].toInt() and 0xFF) or ((pending[1].toInt() and 0xFF) shl 8)
            if (n > FRAME) throw HapException("Trama cifrada demasiado grande")
            val total = 2 + n + ChaCha20Poly1305.TAG_SIZE
            if (pending.size < total) break
            val plain = ChaCha20Poly1305.open(
                inKey, nonce(inCounter++), pending.copyOfRange(2, total), pending.copyOf(2),
            ) ?: throw HapException("Se corrompió el canal cifrado con el altavoz")
            out.write(plain)
            pending = pending.copyOfRange(total, pending.size)
        }
        return out.toByteArray()
    }

    companion object {
        const val FRAME = 1024
        fun nonce(counter: Long): ByteArray {
            val n = ByteArray(12)
            for (i in 0 until 8) n[4 + i] = (counter ushr (8 * i)).toByte()
            return n
        }
    }
}

internal class HapException(message: String) : Exception(message)
