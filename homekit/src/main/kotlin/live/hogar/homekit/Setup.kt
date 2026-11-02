package live.hogar.homekit

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Código de emparejamiento, ID de configuración, ID de dispositivo y la carga del QR (HAP 4.2). */
internal object Setup {
    /** Categoría HAP "Television". */
    const val CATEGORY_TELEVISION = 31

    /** Banderas de transporte del QR: IP = 2. */
    const val FLAG_IP = 2

    // Códigos que HAP prohíbe por triviales.
    private val BANNED = setOf(
        "00000000", "11111111", "22222222", "33333333", "44444444", "55555555",
        "66666666", "77777777", "88888888", "99999999", "12345678", "87654321",
    )

    /** "XXX-XX-XXX" aleatorio, no trivial. */
    fun newSetupCode(rnd: SecureRandom = SecureRandom()): String {
        while (true) {
            val v = rnd.nextInt(100_000_000)
            val digits = v.toString().padStart(8, '0')
            if (digits in BANNED) continue
            return formatCode(digits)
        }
    }

    fun formatCode(digits8: String): String = "${digits8.substring(0, 3)}-${digits8.substring(3, 5)}-${digits8.substring(5)}"

    fun isValidCode(code: String): Boolean {
        if (!Regex("\\d{3}-\\d{2}-\\d{3}").matches(code)) return false
        return code.replace("-", "") !in BANNED
    }

    private const val ID_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    /** 4 caracteres [A-Z0-9] (setup ID). */
    fun newSetupId(rnd: SecureRandom = SecureRandom()): String =
        buildString { repeat(4) { append(ID_CHARS[rnd.nextInt(ID_CHARS.length)]) } }

    /** Dirección tipo MAC "AA:BB:CC:DD:EE:FF"; bit de unicast local, así no choca con una real. */
    fun newDeviceId(rnd: SecureRandom = SecureRandom()): String {
        val b = ByteArray(6).also { rnd.nextBytes(it) }
        b[0] = ((b[0].toInt() and 0xFC) or 0x02).toByte()
        return b.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
    }

    /**
     * Carga de configuración: 45 bits = versión(3, =0) | reservado(4) | categoría(8) | banderas(4) |
     * código(27), en base 36 con 9 cifras; la URL es `X-HM://<carga><setupId>`.
     */
    fun payload(code: String, category: Int, flags: Int, setupId: String): String {
        val digits = code.replace("-", "").toLong()
        val value = (category.toLong() shl 31) or (flags.toLong() shl 27) or digits
        return "X-HM://" + value.toString(36).uppercase().padStart(9, '0') + setupId
    }

    /** Valor TXT `sh`: base64 de los 4 primeros bytes de SHA-512(setupId + deviceId). */
    fun setupHash(setupId: String, deviceId: String): String {
        val d = MessageDigest.getInstance("SHA-512").digest((setupId + deviceId).toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(d.copyOf(4))
    }
}
