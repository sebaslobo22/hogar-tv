package live.hogar.receiver.fairplay

/** Handshake FairPlay SAP v2.5 de /fp-setup (fairplay_playfair.c de UxPlay). */
object FairPlay {
    private val replyMessage = FairPlayTables.bytes("reply_message") // [4][142]
    private val header = FairPlayTables.bytes("fp_header")

    /** Fase 1: peticion de 16 bytes -> respuesta fija de 142 bytes segun el "modo" (req[14]). null si no es soportado. */
    fun setup(req: ByteArray): ByteArray? {
        if (req.size != 16 || req[4].toInt() != 0x03) return null
        val mode = req[14].toInt() and 0xFF
        if (mode > 3) return null
        return replyMessage.copyOfRange(mode * 142, mode * 142 + 142)
    }

    /** Fase 2: peticion de 164 bytes -> 32 bytes (cabecera de 12 + los ultimos 20 de la peticion). */
    fun handshake(req: ByteArray): ByteArray? {
        if (req.size != 164 || req[4].toInt() != 0x03) return null
        return header + req.copyOfRange(144, 164)
    }

    /** Descifra la clave AES (ekey de 72 bytes) con el mensaje de la fase 2. */
    fun decryptKey(message164: ByteArray, ekey72: ByteArray): ByteArray = PlayFair.decrypt(message164, ekey72)
}
