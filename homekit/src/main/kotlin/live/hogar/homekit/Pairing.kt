package live.hogar.homekit

import java.security.SecureRandom

/** Respuesta HTTP lista para serializar. */
internal class HttpResp(
    val status: Int,
    val contentType: String? = null,
    val body: ByteArray = ByteArray(0),
) {
    companion object {
        const val TLV = "application/pairing+tlv8"
        const val JSON = "application/hap+json"
        fun tlv(vararg items: Pair<Int, ByteArray>) = HttpResp(200, TLV, Tlv8.write(items.toList()))
        fun json(status: Int, body: String) = HttpResp(status, JSON, body.toByteArray(Charsets.UTF_8))
        fun empty(status: Int) = HttpResp(status)
    }
}

/** Una sesión de emparejamiento de un controlador ya verificado: claves de la sesión cifrada. */
internal class VerifiedSession(val cipher: HapCipher, val controllerId: String, val admin: Boolean)

/** Estado de una conexión TCP. Lo toca solo su hilo, salvo [subscriptions] y [outLock]. */
internal class Conn {
    /** Para expulsar la conexión más vieja cuando se llena el servidor. */
    val since: Long = System.nanoTime()
    var setup: SetupState? = null
    var verify: VerifyState? = null
    /** Se asigna al terminar pair-verify y se activa tras enviar M4 en claro. */
    var pendingSession: VerifiedSession? = null
    var session: VerifiedSession? = null
    val subscriptions: MutableSet<Int> = java.util.Collections.synchronizedSet(HashSet())
    val verified: Boolean get() = session != null
    val outLock = Any()
}

internal class SetupState(val srp: SrpServer, val startedMs: Long) {
    var step = 2
    var key: ByteArray? = null
}

internal class VerifyState(val accPub: ByteArray, val iosPub: ByteArray, val shared: ByteArray, val sessionKey: ByteArray)

/**
 * Emparejamiento y verificación de HAP (especificación, secciones 5.6 a 5.10) más la gestión de
 * emparejamientos (POST /pairings). Sin sockets ni Android, para poder probarlo entero.
 */
internal class PairingManager(
    private val store: HomeKitStore,
    private val rnd: SecureRandom = SecureRandom(),
    private val clock: () -> Long = System::currentTimeMillis,
    /** Se llama cuando cambia la lista de emparejados (hay que actualizar mDNS y la pantalla). */
    private val onChanged: () -> Unit = {},
    /** Cierra las conexiones de un controlador retirado. */
    private val closeController: (String) -> Unit = {},
) {
    private var setupOwner: Conn? = null
    private var setupStartedMs = 0L
    private var failedAttempts = 0

    private fun state(n: Int) = Tlv8.STATE to Tlv8.byte(n)
    private fun error(n: Int, code: Int) = HttpResp.tlv(state(n), Tlv8.ERROR to Tlv8.byte(code))
    private fun nonce(label: String) = ByteArray(4) + label.toByteArray(Charsets.US_ASCII)
    private fun hkdf(ikm: ByteArray, salt: String, info: String) =
        Hkdf.sha512(ikm, salt.toByteArray(Charsets.US_ASCII), info.toByteArray(Charsets.US_ASCII), 32)

    @Synchronized
    fun connectionClosed(conn: Conn) {
        if (setupOwner === conn) setupOwner = null
        conn.setup = null
    }

    // ---------------------------------------------------------------- pair-setup (M1..M6)

    fun setup(conn: Conn, body: ByteArray): HttpResp = try {
        val tlv = Tlv8.read(body)
        when (tlv[Tlv8.STATE]?.firstOrNull()?.toInt()) {
            1 -> setupM1(conn)
            3 -> setupM3(conn, tlv)
            5 -> setupM5(conn, tlv)
            else -> error(2, Tlv8.ERR_UNKNOWN)
        }
    } catch (e: Exception) {
        error(((conn.setup?.step ?: 1) + 1).coerceAtMost(6), Tlv8.ERR_UNKNOWN)
    }

    private fun setupM1(conn: Conn): HttpResp {
        synchronized(this) {
            if (store.isPaired()) return error(2, Tlv8.ERR_UNAVAILABLE)
            if (failedAttempts >= MAX_TRIES) return error(2, Tlv8.ERR_MAX_TRIES)
            val owner = setupOwner
            if (owner != null && owner !== conn && clock() - setupStartedMs < SETUP_TIMEOUT_MS) return error(2, Tlv8.ERR_BUSY)
            setupOwner = conn
            setupStartedMs = clock()
        }
        val srp = SrpServer(SrpGroup.HAP_3072_SHA512, "Pair-Setup", store.setupCode)
        conn.setup = SetupState(srp, clock())
        return HttpResp.tlv(
            state(2),
            Tlv8.PUBLIC_KEY to srp.publicKey.toPadded(SrpGroup.HAP_3072_SHA512.nLen),
            Tlv8.SALT to srp.salt,
        )
    }

    private fun setupM3(conn: Conn, tlv: Map<Int, ByteArray>): HttpResp {
        val st = conn.setup ?: return error(4, Tlv8.ERR_UNKNOWN)
        val a = tlv[Tlv8.PUBLIC_KEY]
        val proof = tlv[Tlv8.PROOF]
        if (a == null || proof == null) return error(4, Tlv8.ERR_UNKNOWN)
        val m2 = st.srp.verify(a, proof)
        if (m2 == null) {
            synchronized(this) { failedAttempts++; if (setupOwner === conn) setupOwner = null }
            conn.setup = null
            return error(4, Tlv8.ERR_AUTHENTICATION)
        }
        st.key = st.srp.sessionKey
        st.step = 4
        return HttpResp.tlv(state(4), Tlv8.PROOF to m2)
    }

    private fun setupM5(conn: Conn, tlv: Map<Int, ByteArray>): HttpResp {
        val st = conn.setup
        val k = st?.key ?: return error(6, Tlv8.ERR_UNKNOWN)
        val enc = tlv[Tlv8.ENCRYPTED_DATA] ?: return error(6, Tlv8.ERR_UNKNOWN)
        val sessionKey = hkdf(k, "Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info")
        val plain = ChaCha20Poly1305.open(sessionKey, nonce("PS-Msg05"), enc) ?: return fail(conn, Tlv8.ERR_AUTHENTICATION)
        val sub = Tlv8.read(plain)
        val id = sub[Tlv8.IDENTIFIER]
        val ltpk = sub[Tlv8.PUBLIC_KEY]
        val sig = sub[Tlv8.SIGNATURE]
        if (id == null || ltpk == null || sig == null || ltpk.size != 32) return fail(conn, Tlv8.ERR_AUTHENTICATION)
        val iosX = hkdf(k, "Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info")
        if (!Curve25519.ed25519Verify(ltpk, iosX + id + ltpk, sig)) return fail(conn, Tlv8.ERR_AUTHENTICATION)

        val accId = store.deviceId.toByteArray(Charsets.US_ASCII)
        val accPk = store.ltpk
        val accX = hkdf(k, "Pair-Setup-Accessory-Sign-Salt", "Pair-Setup-Accessory-Sign-Info")
        val accSig = Curve25519.ed25519Sign(store.ltskSeed, accX + accId + accPk)
        val reply = Tlv8.of(Tlv8.IDENTIFIER to accId, Tlv8.PUBLIC_KEY to accPk, Tlv8.SIGNATURE to accSig)
        val sealed = ChaCha20Poly1305.seal(sessionKey, nonce("PS-Msg06"), reply)

        store.savePairing(Pairing(String(id, Charsets.UTF_8), ltpk, admin = true))
        synchronized(this) { failedAttempts = 0; if (setupOwner === conn) setupOwner = null }
        conn.setup = null
        onChanged()
        return HttpResp.tlv(state(6), Tlv8.ENCRYPTED_DATA to sealed)
    }

    private fun fail(conn: Conn, code: Int): HttpResp {
        synchronized(this) { if (setupOwner === conn) setupOwner = null }
        conn.setup = null
        return error(6, code)
    }

    // ---------------------------------------------------------------- pair-verify (M1..M4)

    fun verify(conn: Conn, body: ByteArray): HttpResp = try {
        val tlv = Tlv8.read(body)
        when (tlv[Tlv8.STATE]?.firstOrNull()?.toInt()) {
            1 -> verifyM1(conn, tlv)
            3 -> verifyM3(conn, tlv)
            else -> error(2, Tlv8.ERR_UNKNOWN)
        }
    } catch (e: Exception) {
        error(4, Tlv8.ERR_UNKNOWN)
    }

    private fun verifyM1(conn: Conn, tlv: Map<Int, ByteArray>): HttpResp {
        val iosPub = tlv[Tlv8.PUBLIC_KEY]?.takeIf { it.size == 32 } ?: return error(2, Tlv8.ERR_UNKNOWN)
        val priv = ByteArray(32).also { rnd.nextBytes(it) }
        val accPub = Curve25519.x25519PublicKey(priv)
        val shared = Curve25519.x25519(priv, iosPub)
        val accId = store.deviceId.toByteArray(Charsets.US_ASCII)
        val sig = Curve25519.ed25519Sign(store.ltskSeed, accPub + accId + iosPub)
        val sessionKey = hkdf(shared, "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info")
        val sealed = ChaCha20Poly1305.seal(sessionKey, nonce("PV-Msg02"), Tlv8.of(Tlv8.IDENTIFIER to accId, Tlv8.SIGNATURE to sig))
        conn.verify = VerifyState(accPub, iosPub, shared, sessionKey)
        return HttpResp.tlv(state(2), Tlv8.PUBLIC_KEY to accPub, Tlv8.ENCRYPTED_DATA to sealed)
    }

    private fun verifyM3(conn: Conn, tlv: Map<Int, ByteArray>): HttpResp {
        val v = conn.verify ?: return error(4, Tlv8.ERR_UNKNOWN)
        conn.verify = null
        val enc = tlv[Tlv8.ENCRYPTED_DATA] ?: return error(4, Tlv8.ERR_UNKNOWN)
        val plain = ChaCha20Poly1305.open(v.sessionKey, nonce("PV-Msg03"), enc) ?: return error(4, Tlv8.ERR_AUTHENTICATION)
        val sub = Tlv8.read(plain)
        val id = sub[Tlv8.IDENTIFIER] ?: return error(4, Tlv8.ERR_AUTHENTICATION)
        val sig = sub[Tlv8.SIGNATURE] ?: return error(4, Tlv8.ERR_AUTHENTICATION)
        val pairing = store.pairing(String(id, Charsets.UTF_8)) ?: return error(4, Tlv8.ERR_AUTHENTICATION)
        if (!Curve25519.ed25519Verify(pairing.ltpk, v.iosPub + id + v.accPub, sig)) return error(4, Tlv8.ERR_AUTHENTICATION)
        val readKey = hkdf(v.shared, "Control-Salt", "Control-Read-Encryption-Key")
        val writeKey = hkdf(v.shared, "Control-Salt", "Control-Write-Encryption-Key")
        // HAP §6.5.2: el accesorio CIFRA con Read (accesorio→controlador) y DESCIFRA con Write.
        conn.pendingSession = VerifiedSession(HapCipher(outKey = readKey, inKey = writeKey), pairing.id, pairing.admin)
        return HttpResp.tlv(state(4))
    }

    // ---------------------------------------------------------------- POST /pairings

    fun pairings(conn: Conn, body: ByteArray): HttpResp {
        val s = conn.session ?: return error(2, Tlv8.ERR_AUTHENTICATION)
        return try {
            val tlv = Tlv8.read(body)
            val method = tlv[Tlv8.METHOD]?.firstOrNull()?.toInt()
            if (!s.admin) return error(2, Tlv8.ERR_AUTHENTICATION)
            when (method) {
                3 -> addPairing(tlv)
                4 -> removePairing(tlv)
                5 -> listPairings()
                else -> error(2, Tlv8.ERR_UNKNOWN)
            }
        } catch (e: Exception) {
            error(2, Tlv8.ERR_UNKNOWN)
        }
    }

    private fun addPairing(tlv: Map<Int, ByteArray>): HttpResp {
        val id = tlv[Tlv8.IDENTIFIER]?.let { String(it, Charsets.UTF_8) } ?: return error(2, Tlv8.ERR_UNKNOWN)
        val ltpk = tlv[Tlv8.PUBLIC_KEY]?.takeIf { it.size == 32 } ?: return error(2, Tlv8.ERR_UNKNOWN)
        val admin = (tlv[Tlv8.PERMISSIONS]?.firstOrNull()?.toInt() ?: 0) == 1
        val existing = store.pairing(id)
        if (existing != null && !existing.ltpk.contentEquals(ltpk)) return error(2, Tlv8.ERR_UNKNOWN)
        store.savePairing(Pairing(id, ltpk, admin))
        onChanged()
        return HttpResp.tlv(state(2))
    }

    private fun removePairing(tlv: Map<Int, ByteArray>): HttpResp {
        val id = tlv[Tlv8.IDENTIFIER]?.let { String(it, Charsets.UTF_8) } ?: return error(2, Tlv8.ERR_UNKNOWN)
        val removed = store.pairing(id)
        if (removed != null) {
            store.removePairing(id)
            closeController(id)
            // Sin administradores no puede quedar nadie: se vacía todo y el accesorio vuelve a ser nuevo.
            if (store.pairings().none { it.admin }) {
                val others = store.pairings().map { it.id }
                store.clearPairings()
                others.forEach(closeController)
            }
            onChanged()
        }
        return HttpResp.tlv(state(2))
    }

    private fun listPairings(): HttpResp {
        val items = ArrayList<Pair<Int, ByteArray>>()
        items += state(2)
        store.pairings().forEachIndexed { i, p ->
            if (i > 0) items += Tlv8.SEPARATOR to ByteArray(0)
            items += Tlv8.IDENTIFIER to p.id.toByteArray(Charsets.UTF_8)
            items += Tlv8.PUBLIC_KEY to p.ltpk
            items += Tlv8.PERMISSIONS to Tlv8.byte(if (p.admin) 1 else 0)
        }
        return HttpResp(200, HttpResp.TLV, Tlv8.write(items))
    }

    private companion object {
        const val MAX_TRIES = 100
        const val SETUP_TIMEOUT_MS = 30_000L
    }
}
