package live.hogar.receiver.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Emparejamiento "legacy" de AirPlay 1: pair-setup (clave publica Ed25519) y pair-verify
 * (ECDH X25519 + firmas Ed25519 cifradas con AES-128-CTR). Logica de UxPlay pairing.c.
 */
class PairVerify(private val edSeed: ByteArray) {
    private var ecdhPriv: ByteArray = ByteArray(0)
    private var ecdhOurs: ByteArray = ByteArray(0)
    private var ecdhTheirs: ByteArray = ByteArray(0)
    private var edTheirs: ByteArray = ByteArray(0)
    var secret: ByteArray? = null
        private set

    private fun sha512(salt: String, data: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-512")
        md.update(salt.toByteArray(Charsets.US_ASCII))
        md.update(data)
        return md.digest()
    }

    private fun ctr(): Cipher {
        val s = secret!!
        val key = sha512("Pair-Verify-AES-Key", s).copyOf(16)
        val iv = sha512("Pair-Verify-AES-IV", s).copyOf(16)
        return Cipher.getInstance("AES/CTR/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }
    }

    /** Paso 1: recibe (ecdh 32 + ed25519 32) del cliente, devuelve (ecdh nuestra 32 + firma cifrada 64). */
    fun step1(data: ByteArray): ByteArray {
        require(data.size == 64)
        ecdhTheirs = data.copyOfRange(0, 32)
        edTheirs = data.copyOfRange(32, 64)
        ecdhPriv = ByteArray(32).also(SecureRandom()::nextBytes)
        ecdhOurs = Curve25519.x25519PublicKey(ecdhPriv)
        secret = Curve25519.x25519(ecdhPriv, ecdhTheirs)
        val sig = Curve25519.ed25519Sign(edSeed, ecdhOurs + ecdhTheirs)
        return ecdhOurs + ctr().update(sig)
    }

    /** Paso 2: verifica la firma del cliente. Devuelve false si no coincide. */
    fun step2(signature: ByteArray): Boolean {
        require(signature.size == 64)
        val c = ctr()
        c.update(ByteArray(64)) // una ronda "falsa" del handshake inicial
        val plain = c.update(signature)
        return Curve25519.ed25519Verify(edTheirs, ecdhTheirs + ecdhOurs, plain)
    }
}
