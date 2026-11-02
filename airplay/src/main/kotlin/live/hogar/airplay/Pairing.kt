package live.hogar.airplay

import java.math.BigInteger

/** Claves de sesión derivadas del emparejamiento (ya listas para cifrar el canal de control). */
internal class PairingResult(
    /** Secreto compartido SRP (K = H(S)), 64 bytes con SHA-512. */
    val sharedSecret: ByteArray,
) {
    /** (clave de escritura, clave de lectura) para el canal de control. */
    fun controlKeys(): Pair<ByteArray, ByteArray> =
        derive("Control-Salt", "Control-Write-Encryption-Key") to derive("Control-Salt", "Control-Read-Encryption-Key")

    /** Claves del canal de eventos, desde el punto de vista del emisor (como `setup_channel` de pyatv). */
    fun eventKeys(): Pair<ByteArray, ByteArray> =
        derive("Events-Salt", "Events-Read-Encryption-Key") to derive("Events-Salt", "Events-Write-Encryption-Key")

    fun derive(salt: String, info: String): ByteArray =
        Hkdf.sha512(sharedSecret, salt.toByteArray(), info.toByteArray(), 32)
}

/**
 * Emparejamiento con el receptor. Si Apple cambia el método (p. ej. exige otro PIN o HomeKit),
 * se añade otra implementación sin tocar el resto.
 */
internal interface Pairing {
    suspend fun pair(rtsp: RtspClient): PairingResult
}

/**
 * Emparejamiento HAP **transitorio** (pair-setup M1..M4 con PIN fijo 3939 y bandera transient),
 * portado de `AirPlayHapTransientPairVerifyProcedure` (pyatv/protocols/airplay/auth/hap_transient.py).
 * No guarda nada en el altavoz: la clave sólo vale para esta conexión.
 */
internal class TransientPairing(
    private val pin: String = "3939",
    private val group: SrpGroup = SrpGroup.HAP_3072_SHA512,
) : Pairing {
    override suspend fun pair(rtsp: RtspClient): PairingResult {
        val headers = listOf(
            "User-Agent" to "AirPlay/320.20",
            "Connection" to "keep-alive",
            "X-Apple-HKP" to "4",
        )
        // Algunos receptores no lo necesitan; un fallo aquí no es fatal.
        try {
            rtsp.request("POST", "/pair-pin-start", "HTTP/1.1", headers, "application/octet-stream")
        } catch (e: java.io.IOException) {
            throw AirPlayException(MSG_PAIR_IO, e)
        }

        val m1 = Tlv8.write(
            listOf(
                Tlv8.METHOD to byteArrayOf(0),
                Tlv8.SEQ_NO to byteArrayOf(1),
                Tlv8.FLAGS to byteArrayOf(Tlv8.FLAG_TRANSIENT.toByte()),
            ),
        )
        val r2 = post(rtsp, headers, m1)
        val t2 = Tlv8.read(r2.body)
        checkError(t2)
        val salt = t2[Tlv8.SALT] ?: throw AirPlayException(MSG_PAIR_PROTOCOL)
        val serverPub = t2[Tlv8.PUBLIC_KEY] ?: throw AirPlayException(MSG_PAIR_PROTOCOL)

        val srp = SrpClient(group, "Pair-Setup", pin)
        val result = srp.process(salt, BigInteger(1, serverPub))

        val m3 = Tlv8.write(
            listOf(
                Tlv8.SEQ_NO to byteArrayOf(3),
                Tlv8.PUBLIC_KEY to srp.publicKey.toBytes(),
                Tlv8.PROOF to result.clientProof,
            ),
        )
        val r4 = post(rtsp, headers, m3)
        val t4 = Tlv8.read(r4.body)
        checkError(t4)
        val serverProof = t4[Tlv8.PROOF]
        if (serverProof != null && !serverProof.contentEquals(result.expectedServerProof)) {
            // pyatv ni siquiera lo comprueba; se avisa pero se continúa: si de verdad no
            // coincidiera, el SETUP cifrado fallará enseguida con un mensaje claro.
            APLog.w("Pair-setup: la prueba M2 del altavoz no coincide con la calculada")
        }
        return PairingResult(result.sessionKey)
    }

    private suspend fun post(rtsp: RtspClient, headers: List<Pair<String, String>>, body: ByteArray): RtspResponse {
        val r = try {
            rtsp.request("POST", "/pair-setup", "HTTP/1.1", headers, "application/octet-stream", body)
        } catch (e: java.io.IOException) {
            throw AirPlayException(MSG_PAIR_IO, e)
        }
        if (!r.ok) throw AirPlayException("$MSG_PAIR_REJECTED (respuesta ${r.code})")
        return r
    }

    private fun checkError(t: Map<Int, ByteArray>) {
        val err = t[Tlv8.ERROR] ?: return
        val code = err.firstOrNull()?.toInt() ?: 0
        throw AirPlayException("$MSG_PAIR_REJECTED (código HAP $code)")
    }

    companion object {
        const val MSG_PAIR_REJECTED =
            "El altavoz rechazó el emparejamiento: es posible que Apple haya cambiado AirPlay"
        const val MSG_PAIR_PROTOCOL =
            "El altavoz respondió al emparejamiento de forma inesperada: es posible que Apple haya cambiado AirPlay"
        const val MSG_PAIR_IO = "Se perdió la conexión con el altavoz durante el emparejamiento"
    }
}
