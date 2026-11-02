package live.hogar.homekit

import java.math.BigInteger
import java.security.MessageDigest

/**
 * X25519 (RFC 7748) y Ed25519 (RFC 8032) con BigInteger, sin dependencias: Android 11 (API 30) no trae
 * esas curvas. No es de tiempo constante; solo se usa para el emparejamiento AirPlay en red local.
 */
internal object Curve25519 {
    private val P = BigInteger.ONE.shiftLeft(255) - BigInteger.valueOf(19)
    private val L = BigInteger.ONE.shiftLeft(252) + BigInteger("27742317777372353535851937790883648493")
    private val D = (BigInteger.valueOf(-121665) * BigInteger.valueOf(121666).modInverse(P)).mod(P)
    private val TWO_D = D.shiftLeft(1).mod(P)
    private val SQRT_M1 = BigInteger.valueOf(2).modPow((P - BigInteger.ONE).shiftRight(2), P)
    private val A24 = BigInteger.valueOf(121665)

    private fun leToBig(b: ByteArray, off: Int = 0, len: Int = b.size - off): BigInteger {
        val r = ByteArray(len) { b[off + len - 1 - it] }
        return BigInteger(1, r)
    }

    private fun bigToLe(v: BigInteger, len: Int): ByteArray {
        val be = v.toByteArray()
        val out = ByteArray(len)
        for (i in 0 until len) {
            val idx = be.size - 1 - i
            out[i] = if (idx >= 0) be[idx] else 0
        }
        return out
    }

    // ------------------------------------------------------------------ X25519
    fun x25519(scalar: ByteArray, u: ByteArray): ByteArray {
        require(scalar.size == 32 && u.size == 32)
        val k = scalar.copyOf()
        k[0] = (k[0].toInt() and 248).toByte()
        k[31] = ((k[31].toInt() and 127) or 64).toByte()
        val kInt = leToBig(k)
        val uc = u.copyOf()
        uc[31] = (uc[31].toInt() and 127).toByte()
        val x1 = leToBig(uc).mod(P)
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE
        var swap = false
        for (t in 254 downTo 0) {
            val kt = kInt.testBit(t)
            if (swap != kt) { val a = x2; x2 = x3; x3 = a; val b = z2; z2 = z3; z3 = b }
            swap = kt
            val a = (x2 + z2).mod(P)
            val aa = (a * a).mod(P)
            val b = (x2 - z2).mod(P)
            val bb = (b * b).mod(P)
            val e = (aa - bb).mod(P)
            val c = (x3 + z3).mod(P)
            val d = (x3 - z3).mod(P)
            val da = (d * a).mod(P)
            val cb = (c * b).mod(P)
            x3 = (da + cb).pow(2).mod(P)
            z3 = (x1 * (da - cb).pow(2)).mod(P)
            x2 = (aa * bb).mod(P)
            z2 = (e * (aa + A24 * e)).mod(P)
        }
        if (swap) { val a = x2; x2 = x3; x3 = a; val b = z2; z2 = z3; z3 = b }
        val res = (x2 * z2.modPow(P - BigInteger.valueOf(2), P)).mod(P)
        return bigToLe(res, 32)
    }

    fun x25519PublicKey(priv: ByteArray): ByteArray = x25519(priv, ByteArray(32).also { it[0] = 9 })

    // ------------------------------------------------------------------ Ed25519
    private class Pt(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

    private val BY = (BigInteger.valueOf(4) * BigInteger.valueOf(5).modInverse(P)).mod(P)
    private val B: Pt by lazy {
        val x = recoverX(BY, 0)!!
        Pt(x, BY, BigInteger.ONE, (x * BY).mod(P))
    }
    private val ZERO = Pt(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)

    private fun recoverX(y: BigInteger, sign: Int): BigInteger? {
        if (y >= P) return null
        val x2 = ((y * y - BigInteger.ONE) * (D * y * y + BigInteger.ONE).modInverse(P)).mod(P)
        if (x2.signum() == 0) return if (sign != 0) null else BigInteger.ZERO
        var x = x2.modPow((P + BigInteger.valueOf(3)).shiftRight(3), P)
        if ((x * x - x2).mod(P).signum() != 0) x = (x * SQRT_M1).mod(P)
        if ((x * x - x2).mod(P).signum() != 0) return null
        if (x.testBit(0) != (sign != 0)) x = P - x
        return x
    }

    private fun add(p: Pt, q: Pt): Pt {
        val a = ((p.y - p.x) * (q.y - q.x)).mod(P)
        val b = ((p.y + p.x) * (q.y + q.x)).mod(P)
        val c = (p.t * TWO_D * q.t).mod(P)
        val d = (p.z * q.z).shiftLeft(1).mod(P)
        val e = b - a
        val f = d - c
        val g = d + c
        val h = b + a
        return Pt((e * f).mod(P), (g * h).mod(P), (f * g).mod(P), (e * h).mod(P))
    }

    private fun mul(s: BigInteger, pt: Pt): Pt {
        var q = ZERO
        var p = pt
        var k = s
        while (k.signum() > 0) {
            if (k.testBit(0)) q = add(q, p)
            p = add(p, p)
            k = k.shiftRight(1)
        }
        return q
    }

    private fun compress(p: Pt): ByteArray {
        val zi = p.z.modInverse(P)
        val x = (p.x * zi).mod(P)
        val y = (p.y * zi).mod(P)
        val out = bigToLe(y, 32)
        if (x.testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
        return out
    }

    private fun decompress(b: ByteArray): Pt? {
        if (b.size != 32) return null
        val sign = (b[31].toInt() ushr 7) and 1
        val c = b.copyOf(); c[31] = (c[31].toInt() and 0x7F).toByte()
        val y = leToBig(c)
        val x = recoverX(y, sign) ?: return null
        return Pt(x, y, BigInteger.ONE, (x * y).mod(P))
    }

    private fun sha512(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-512")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    private fun clampedScalar(h: ByteArray): BigInteger {
        val a = h.copyOfRange(0, 32)
        a[0] = (a[0].toInt() and 248).toByte()
        a[31] = ((a[31].toInt() and 127) or 64).toByte()
        return leToBig(a)
    }

    fun ed25519PublicKey(seed: ByteArray): ByteArray {
        require(seed.size == 32)
        return compress(mul(clampedScalar(sha512(seed)), B))
    }

    fun ed25519Sign(seed: ByteArray, msg: ByteArray): ByteArray {
        val h = sha512(seed)
        val a = clampedScalar(h)
        val pub = compress(mul(a, B))
        val r = leToBig(sha512(h.copyOfRange(32, 64), msg)).mod(L)
        val rEnc = compress(mul(r, B))
        val k = leToBig(sha512(rEnc, pub, msg)).mod(L)
        val s = (r + k * a).mod(L)
        return rEnc + bigToLe(s, 32)
    }

    fun ed25519Verify(pub: ByteArray, msg: ByteArray, sig: ByteArray): Boolean {
        if (pub.size != 32 || sig.size != 64) return false
        val a = decompress(pub) ?: return false
        val r = decompress(sig.copyOfRange(0, 32)) ?: return false
        val s = leToBig(sig, 32, 32)
        if (s >= L) return false
        val k = leToBig(sha512(sig.copyOfRange(0, 32), pub, msg)).mod(L)
        val lhs = compress(mul(s, B))
        val rhs = compress(add(r, mul(k, a)))
        return lhs.contentEquals(rhs)
    }
}
