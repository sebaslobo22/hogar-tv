package live.hogar.homekit

import java.security.SecureRandom

/** Almacén clave-valor mínimo (SharedPreferences en el TV, memoria en las pruebas). */
internal interface Kv {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
    fun keys(): Set<String>
}

internal class MemoryKv : Kv {
    private val m = LinkedHashMap<String, String>()
    @Synchronized override fun get(key: String) = m[key]
    @Synchronized override fun put(key: String, value: String) { m[key] = value }
    @Synchronized override fun remove(key: String) { m.remove(key) }
    @Synchronized override fun keys(): Set<String> = m.keys.toSet()
}

/** Un controlador emparejado (iPhone, iPad, Mac…). */
internal class Pairing(val id: String, val ltpk: ByteArray, val admin: Boolean)

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

internal fun String.unhex(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }

/**
 * Estado persistente del accesorio: identidad (ID de dispositivo y clave Ed25519 a largo plazo),
 * código y setup ID, controladores emparejados, número de configuración (c#) e identificadores
 * estables de las entradas. Todo en un [Kv].
 */
internal class HomeKitStore(private val kv: Kv, private val rnd: SecureRandom = SecureRandom()) {

    @get:Synchronized
    val deviceId: String
        get() = kv.get(K_DEVICE) ?: Setup.newDeviceId(rnd).also { kv.put(K_DEVICE, it) }

    /** Semilla de 32 bytes de la clave Ed25519 (LTSK). */
    @get:Synchronized
    val ltskSeed: ByteArray
        get() = kv.get(K_SEED)?.unhex() ?: ByteArray(32).also { rnd.nextBytes(it); kv.put(K_SEED, it.hex()) }

    @get:Synchronized
    val ltpk: ByteArray
        get() = kv.get(K_LTPK)?.unhex() ?: Curve25519.ed25519PublicKey(ltskSeed).also { kv.put(K_LTPK, it.hex()) }

    @get:Synchronized
    val setupCode: String
        get() = kv.get(K_CODE)?.takeIf { Setup.isValidCode(it) } ?: Setup.newSetupCode(rnd).also { kv.put(K_CODE, it) }

    @get:Synchronized
    val setupId: String
        get() = kv.get(K_SETUP_ID)?.takeIf { it.length == 4 } ?: Setup.newSetupId(rnd).also { kv.put(K_SETUP_ID, it) }

    val setupHash: String get() = Setup.setupHash(setupId, deviceId)

    val qrPayload: String get() = Setup.payload(setupCode, Setup.CATEGORY_TELEVISION, Setup.FLAG_IP, setupId)

    // ---------------------------------------------------------------- emparejamientos

    @Synchronized
    fun pairings(): List<Pairing> = kv.keys().filter { it.startsWith(P_PAIR) }.sorted().mapNotNull { k ->
        val v = kv.get(k) ?: return@mapNotNull null
        val i = v.indexOf(':')
        if (i < 0) null else Pairing(k.removePrefix(P_PAIR), v.substring(0, i).unhex(), v.substring(i + 1) == "1")
    }

    @Synchronized fun pairing(id: String): Pairing? = pairings().firstOrNull { it.id == id }

    @Synchronized fun isPaired(): Boolean = kv.keys().any { it.startsWith(P_PAIR) }

    @Synchronized
    fun savePairing(p: Pairing) {
        kv.put(P_PAIR + p.id, p.ltpk.hex() + ":" + if (p.admin) "1" else "0")
    }

    @Synchronized fun removePairing(id: String) { kv.remove(P_PAIR + id) }

    @Synchronized
    fun clearPairings() {
        kv.keys().filter { it.startsWith(P_PAIR) }.forEach { kv.remove(it) }
    }

    /** Borra emparejamientos y estrena código y setup ID (el LTSK y el ID de dispositivo se conservan). */
    @Synchronized
    fun resetPairing() {
        clearPairings()
        kv.put(K_CODE, Setup.newSetupCode(rnd))
        kv.put(K_SETUP_ID, Setup.newSetupId(rnd))
    }

    // ---------------------------------------------------------------- número de configuración

    /** c#: sube (1..65535, vuelve a 1) cada vez que cambia la firma de la base de accesorios. */
    @Synchronized
    fun configNumber(signature: String): Int {
        val cur = kv.get(K_CN)?.toIntOrNull()
        if (cur == null) { kv.put(K_CN, "1"); kv.put(K_CN_SIG, signature); return 1 }
        if (kv.get(K_CN_SIG) == signature) return cur
        val next = if (cur >= 65535) 1 else cur + 1
        kv.put(K_CN, next.toString()); kv.put(K_CN_SIG, signature)
        return next
    }

    // ---------------------------------------------------------------- identificadores y nombres

    /** Identificador estable (≥1) de una entrada (paquete o "hdmi:…"); nunca se reutiliza. */
    @Synchronized
    fun identifierFor(key: String): Int {
        kv.get(P_ID + key)?.toIntOrNull()?.let { return it }
        val next = (kv.get(K_NEXT_ID)?.toIntOrNull() ?: 1)
        kv.put(P_ID + key, next.toString()); kv.put(K_NEXT_ID, (next + 1).toString())
        return next
    }

    @Synchronized fun name(slot: String): String? = kv.get(P_NAME + slot)
    @Synchronized fun setName(slot: String, v: String) { kv.put(P_NAME + slot, v) }

    /** 0 = visible, 1 = oculta. */
    @Synchronized fun visibility(identifier: Int): Int = kv.get(P_VIS + identifier)?.toIntOrNull() ?: 0
    @Synchronized fun setVisibility(identifier: Int, v: Int) { kv.put(P_VIS + identifier, v.toString()) }

    private companion object {
        const val K_DEVICE = "device_id"
        const val K_SEED = "ltsk_seed"
        const val K_LTPK = "ltpk"
        const val K_CODE = "setup_code"
        const val K_SETUP_ID = "setup_id"
        const val K_CN = "config_number"
        const val K_CN_SIG = "config_signature"
        const val K_NEXT_ID = "next_identifier"
        const val P_PAIR = "pair."
        const val P_ID = "id."
        const val P_NAME = "name."
        const val P_VIS = "vis."
    }
}
