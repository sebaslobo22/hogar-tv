package live.hogar.homekit

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/** Controlador SRP (lado iPhone) solo para pruebas. */
/**
 * Cliente SRP-6a (RFC 2945 / 5054) tal y como lo usa el emparejamiento de HAP.
 * Equivale a `SRPContext` + `SRPClientSession` de srptools que usa pyatv, con estas
 * convenciones: x = H(s | H(I ":" P)), k = H(N | PAD(g)), u = H(PAD(A) | PAD(B)),
 * K = H(S) con S sin relleno, M1 = H(H(N) xor H(g) | H(I) | s | A | B | K) con A y B sin
 * relleno, M2 = H(A | M1 | K).
 */
internal class SrpClient(
    private val group: SrpGroup,
    private val username: String,
    private val password: String,
    privateKey: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) },
) {
    private val n = group.n
    private val g = group.g
    private val a = BigInteger(1, privateKey)

    /** A = g^a mod N */
    val publicKey: BigInteger = g.modPow(a, n)

    class Result(
        val x: BigInteger,
        val u: BigInteger,
        val premaster: BigInteger,
        /** K = H(S) */
        val sessionKey: ByteArray,
        /** M1: prueba del cliente. */
        val clientProof: ByteArray,
        /** M2 esperado del servidor. */
        val expectedServerProof: ByteArray,
    )

    val multiplier: BigInteger by lazy { bigH(n.toBytes(), pad(g)) }

    private fun h(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance(group.hashAlg)
        parts.forEach { md.update(it) }
        return md.digest()
    }

    private fun bigH(vararg parts: ByteArray) = BigInteger(1, h(*parts))

    private fun pad(v: BigInteger): ByteArray {
        val len = n.toBytes().size
        val b = v.toBytes()
        return if (b.size >= len) b else ByteArray(len - b.size) + b
    }

    fun process(salt: ByteArray, serverPublic: BigInteger): Result {
        if (serverPublic.mod(n) == BigInteger.ZERO) throw IllegalStateException("Clave SRP del altavoz no válida")
        val x = bigH(salt, h((username + ":" + password).toByteArray(Charsets.UTF_8)))
        val u = bigH(pad(publicKey), pad(serverPublic))
        if (u == BigInteger.ZERO) throw IllegalStateException("Parámetro SRP no válido")
        val gx = g.modPow(x, n)
        val base = serverPublic.subtract(multiplier.multiply(gx)).mod(n)
        val s = base.modPow(a.add(u.multiply(x)), n)
        val k = h(s.toBytes())
        val hn = h(n.toBytes())
        val hg = h(g.toBytes())
        val xor = ByteArray(hn.size) { (hn[it].toInt() xor hg[it].toInt()).toByte() }
        val m1 = h(xor, h(username.toByteArray(Charsets.UTF_8)), salt, publicKey.toBytes(), serverPublic.toBytes(), k)
        val m2 = h(publicKey.toBytes(), m1, k)
        return Result(x, u, s, k, m1, m2)
    }
}

