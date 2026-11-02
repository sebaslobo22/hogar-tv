package live.hogar.airplay

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/** Parámetros de un grupo SRP (RFC 5054). */
internal class SrpGroup(val n: BigInteger, val g: BigInteger, val hashAlg: String) {
    companion object {
        /** Grupo de 3072 bits (RFC 3526 / RFC 5054) con SHA-512: el que usa HAP / AirPlay 2. */
        val HAP_3072_SHA512 = SrpGroup(BigInteger(PRIME_3072_HEX, 16), BigInteger.valueOf(5), "SHA-512")
        val RFC5054_1024_SHA1 = SrpGroup(BigInteger(PRIME_1024_HEX, 16), BigInteger.valueOf(2), "SHA-1")

        private const val PRIME_3072_HEX =
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DD" +
                "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
                "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F" +
                "83655D23DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
                "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183995497CEA956AE515D2261898FA0510" +
                "15728E5A8AAAC42DAD33170D04507A33A85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7" +
                "ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864D87602733EC86A64521F2B18177B200C" +
                "BBE117577A615D6C770988C0BAD946E208E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF"

        private const val PRIME_1024_HEX =
            "EEAF0AB9ADB38DD69C33F80AFA8FC5E86072618775FF3C0B9EA2314C9C256576D674DF7496EA81D3383B4813D692C6E0" +
                "E0D5D8E250B98BE48E495C1D6089DAD15DC7D7B46154D6B6CE8EF4AD69B15D4982559B297BCF1885C529F566660E57EC" +
                "68EDBC3C05726CC02FD4CBF4976EAA9AFD5138FE8376435B9FC61D2FC0EB06E3"
    }
}

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
        if (serverPublic.mod(n) == BigInteger.ZERO) throw AirPlayException("Clave SRP del altavoz no válida")
        val x = bigH(salt, h((username + ":" + password).toByteArray(Charsets.UTF_8)))
        val u = bigH(pad(publicKey), pad(serverPublic))
        if (u == BigInteger.ZERO) throw AirPlayException("Parámetro SRP no válido")
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

/** Bytes big-endian sin signo ni ceros a la izquierda (como `int_to_bytes` de srptools). */
internal fun BigInteger.toBytes(): ByteArray {
    val b = toByteArray()
    return if (b.size > 1 && b[0] == 0.toByte()) b.copyOfRange(1, b.size) else b
}
