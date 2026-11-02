package live.hogar.homekit

import java.util.Base64

/** Códigos de estado de HAP para /characteristics. */
internal object HapStatus {
    const val OK = 0
    const val INSUFFICIENT_PRIVILEGES = -70401
    const val COMMUNICATION_FAILURE = -70402
    const val BUSY = -70403
    const val READ_ONLY = -70404
    const val WRITE_ONLY = -70405
    const val NOTIFICATION_NOT_SUPPORTED = -70406
    const val OUT_OF_RESOURCE = -70407
    const val TIMEOUT = -70408
    const val NOT_FOUND = -70409
    const val INVALID_VALUE = -70410
}

/** Identificadores de instancia (iid) del único accesorio (aid 1). */
internal object Iid {
    const val AID = 1

    const val INFO = 1
    const val IDENTIFY = 2
    const val MANUFACTURER = 3
    const val MODEL = 4
    const val NAME = 5
    const val SERIAL = 6
    const val FIRMWARE = 7

    const val PROTOCOL = 8
    const val PROTOCOL_VERSION = 9

    const val TV = 10
    const val TV_CONFIGURED_NAME = 11
    const val TV_ACTIVE = 12
    const val TV_ACTIVE_ID = 13
    const val TV_SLEEP_DISCOVERY = 14
    const val TV_REMOTE_KEY = 15
    const val TV_POWER_MODE = 16
    const val TV_DISPLAY_ORDER = 17

    const val SPEAKER = 20
    const val SPK_MUTE = 21
    const val SPK_ACTIVE = 22
    const val SPK_VOLUME_TYPE = 23
    const val SPK_VOLUME_SELECTOR = 24
    const val SPK_VOLUME = 25

    // Entrada con identificador n (≥1): servicio 100 + 10n, y sus características a continuación.
    const val INPUT_NAME = 1
    const val INPUT_CONFIGURED_NAME = 2
    const val INPUT_TYPE = 3
    const val INPUT_IS_CONFIGURED = 4
    const val INPUT_CURRENT_VISIBILITY = 5
    const val INPUT_IDENTIFIER = 6
    const val INPUT_TARGET_VISIBILITY = 7

    fun inputService(identifier: Int) = 100 + 10 * identifier
    fun inputChar(identifier: Int, which: Int) = inputService(identifier) + which

    /** Mayor identificador de entrada posible: sus iid (100 + 10n) tienen que quedar por debajo de los servicios añadidos. */
    const val MAX_INPUT_IDENTIFIER = 989

    /** Identificador de entrada al que pertenece un iid, o null. */
    fun inputOf(iid: Int): Int? = if (iid in 110 until Extra.FIRST_IID) (iid - 100) / 10 else null
    fun inputPart(iid: Int): Int = (iid - 100) % 10

    // Servicios añadidos (iid nuevos, a partir de 10000; los anteriores no se tocan).
    const val LABEL = 10500
    const val LABEL_NAMESPACE = 10501
}

/** Servicios Luz nocturna (bombilla) e interruptores. Cada uno: servicio = base, nombre +1, On +2, brillo +3, nombre configurado +4. */
internal enum class Extra(val base: Int, val label: String, val slot: String) {
    NIGHT(10000, "Luz nocturna", "night"),
    MUSIC(10100, "Luz musical", "music"),
    RHYTHM(10200, "Ritmo", "rhythm"),
    NOW_PLAYING(10300, "Reproduciendo ahora", "nowplaying"),
    SAVER(10400, "Salvapantallas", "saver");

    val serviceIid get() = base
    val nameIid get() = base + 1
    val onIid get() = base + 2
    val brightnessIid get() = base + 3
    val configuredIid get() = base + 4

    companion object {
        const val FIRST_IID = 10000
        fun ofOn(iid: Int): Extra? = entries.firstOrNull { it.onIid == iid }
        fun ofConfiguredName(iid: Int): Extra? = entries.firstOrNull { it.configuredIid == iid }
    }
}

/** Botones del mando como interruptores programables sin estado. Servicio = base, evento +1, nombre +2, índice +3. */
internal enum class Button(val base: Int, val id: String, val label: String, val index: Int, val valid: List<Int>) {
    BLUE(10510, TvPowerIds.BLUE, "Botón azul", 1, listOf(0, 1, 2)),
    PROFILE(10520, TvPowerIds.PROFILE, "Botón perfil", 2, listOf(1, 2)),
    TV(10530, TvPowerIds.TV, "Botón TV", 3, listOf(2));

    val serviceIid get() = base
    val eventIid get() = base + 1
    val nameIid get() = base + 2
    val indexIid get() = base + 3

    companion object {
        fun byId(id: String): Button? = entries.firstOrNull { it.id == id }
        fun byEvent(iid: Int): Button? = entries.firstOrNull { it.eventIid == iid }
    }
}

/** Espejo de las constantes de live.hogar.core.TvPower (así las enumeraciones no dependen de Android ni de core en las pruebas). */
internal object TvPowerIds {
    const val BLUE = "blue"
    const val PROFILE = "profile"
    const val TV = "tv"
}

/** Una entrada del televisor (app o HDMI). */
internal data class InputDef(val identifier: Int, val key: String, val name: String, val type: Int) {
    companion object {
        const val TYPE_HDMI = 3
        const val TYPE_APPLICATION = 10
    }
}

internal class CharDef(
    val iid: Int,
    val type: String,
    val format: String,
    val perms: List<String>,
    val unit: String? = null,
    val min: Int? = null,
    val max: Int? = null,
    val step: Int? = null,
    val maxLen: Int? = null,
    val validValues: List<Int>? = null,
    /** Solo emite eventos (ProgrammableSwitchEvent): al leerla, el valor es null. */
    val eventOnly: Boolean = false,
) {
    val readable get() = "pr" in perms
    val writable get() = "pw" in perms
    val notifies get() = "ev" in perms

    /** Normaliza un valor recibido en un PUT; null si no es válido para este formato. */
    fun coerce(v: Any?): Any? = when (format) {
        "bool" -> when (v) {
            is Boolean -> v
            is Number -> when (v.toInt()) { 0 -> false; 1 -> true; else -> null }
            else -> null
        }
        "int", "uint8", "uint16", "uint32" -> {
            val n: Long? = when (v) {
                is Boolean -> if (v) 1L else 0L
                is Number -> if (v.toDouble() == Math.floor(v.toDouble())) v.toLong() else null
                else -> null
            }
            when {
                n == null -> null
                min != null && n < min -> null
                max != null && n > max -> null
                validValues != null && n.toInt() !in validValues -> null
                else -> n
            }
        }
        "string" -> (v as? String)?.takeIf { it.length <= (maxLen ?: 64) }
        "tlv8" -> (v as? String)
        else -> null
    }
}

internal class ServiceDef(
    val iid: Int,
    val type: String,
    val chars: List<CharDef>,
    val primary: Boolean = false,
    val linked: List<Int> = emptyList(),
)

/** Base de accesorios (aid 1): Información, Protocolo, Televisión + entradas + altavoz + luz nocturna, interruptores y botones. */
internal class AccessoryDb(val inputs: List<InputDef>) {
    val services: List<ServiceDef>
    private val byIid = HashMap<Int, CharDef>()

    init {
        val list = ArrayList<ServiceDef>()
        list += ServiceDef(
            Iid.INFO, "3E",
            listOf(
                CharDef(Iid.IDENTIFY, "14", "bool", listOf("pw")),
                CharDef(Iid.MANUFACTURER, "20", "string", listOf("pr")),
                CharDef(Iid.MODEL, "21", "string", listOf("pr")),
                CharDef(Iid.NAME, "23", "string", listOf("pr")),
                CharDef(Iid.SERIAL, "30", "string", listOf("pr")),
                CharDef(Iid.FIRMWARE, "52", "string", listOf("pr")),
            ),
        )
        list += ServiceDef(Iid.PROTOCOL, "A2", listOf(CharDef(Iid.PROTOCOL_VERSION, "37", "string", listOf("pr"))))
        list += ServiceDef(
            Iid.TV, "D8",
            listOf(
                CharDef(Iid.TV_CONFIGURED_NAME, "E3", "string", listOf("pr", "pw", "ev")),
                CharDef(Iid.TV_ACTIVE, "B0", "uint8", listOf("pr", "pw", "ev"), min = 0, max = 1, step = 1, validValues = listOf(0, 1)),
                CharDef(Iid.TV_ACTIVE_ID, "E7", "uint32", listOf("pr", "pw", "ev")),
                CharDef(Iid.TV_SLEEP_DISCOVERY, "E8", "uint8", listOf("pr", "ev"), min = 0, max = 1, step = 1, validValues = listOf(0, 1)),
                CharDef(Iid.TV_REMOTE_KEY, "E1", "uint8", listOf("pw"), min = 0, max = 15, step = 1),
                CharDef(Iid.TV_POWER_MODE, "DF", "uint8", listOf("pw"), min = 0, max = 1, step = 1, validValues = listOf(0, 1)),
                CharDef(Iid.TV_DISPLAY_ORDER, "136", "tlv8", listOf("pr", "pw", "ev")),
            ),
            primary = true,
            linked = inputs.map { Iid.inputService(it.identifier) } + Iid.SPEAKER,
        )
        for (input in inputs) {
            val id = input.identifier
            list += ServiceDef(
                Iid.inputService(id), "D9",
                listOf(
                    CharDef(Iid.inputChar(id, Iid.INPUT_NAME), "23", "string", listOf("pr")),
                    CharDef(Iid.inputChar(id, Iid.INPUT_CONFIGURED_NAME), "E3", "string", listOf("pr", "pw", "ev")),
                    CharDef(Iid.inputChar(id, Iid.INPUT_TYPE), "DB", "uint8", listOf("pr", "ev"), min = 0, max = 10, step = 1),
                    CharDef(Iid.inputChar(id, Iid.INPUT_IS_CONFIGURED), "D6", "uint8", listOf("pr", "pw", "ev"), min = 0, max = 1, step = 1, validValues = listOf(0, 1)),
                    CharDef(Iid.inputChar(id, Iid.INPUT_CURRENT_VISIBILITY), "135", "uint8", listOf("pr", "ev"), min = 0, max = 1, step = 1, validValues = listOf(0, 1)),
                    CharDef(Iid.inputChar(id, Iid.INPUT_IDENTIFIER), "E6", "uint32", listOf("pr")),
                    CharDef(Iid.inputChar(id, Iid.INPUT_TARGET_VISIBILITY), "134", "uint8", listOf("pr", "pw", "ev"), min = 0, max = 1, step = 1, validValues = listOf(0, 1)),
                ),
            )
        }
        list += ServiceDef(
            Iid.SPEAKER, "113",
            listOf(
                CharDef(Iid.SPK_MUTE, "11", "bool", listOf("pr", "pw", "ev")),
                CharDef(Iid.SPK_ACTIVE, "B0", "uint8", listOf("pr", "pw", "ev"), min = 0, max = 1, step = 1, validValues = listOf(0, 1)),
                CharDef(Iid.SPK_VOLUME_TYPE, "E9", "uint8", listOf("pr", "ev"), min = 0, max = 3, step = 1),
                CharDef(Iid.SPK_VOLUME_SELECTOR, "EA", "uint8", listOf("pw"), min = 0, max = 1, step = 1, validValues = listOf(0, 1)),
                CharDef(Iid.SPK_VOLUME, "119", "uint8", listOf("pr", "pw", "ev"), unit = "percentage", min = 0, max = 100, step = 1),
            ),
        )
        // Añadidos después de todo lo anterior: los iid existentes no cambian.
        val boolPerms = listOf("pr", "pw", "ev")
        for (e in Extra.entries) {
            val chars = ArrayList<CharDef>()
            chars += CharDef(e.nameIid, "23", "string", listOf("pr"))
            chars += CharDef(e.onIid, "25", "bool", boolPerms)
            if (e == Extra.NIGHT) {
                chars += CharDef(e.brightnessIid, "8", "int", boolPerms, unit = "percentage", min = 1, max = 100, step = 1)
            }
            chars += CharDef(e.configuredIid, "E3", "string", boolPerms)
            list += ServiceDef(e.serviceIid, if (e == Extra.NIGHT) "43" else "49", chars)
        }
        list += ServiceDef(Iid.LABEL, "CC", listOf(CharDef(Iid.LABEL_NAMESPACE, "CD", "uint8", listOf("pr"), min = 0, max = 1, step = 1)))
        for (b in Button.entries) {
            list += ServiceDef(
                b.serviceIid, "89",
                listOf(
                    CharDef(b.eventIid, "73", "uint8", listOf("pr", "ev"), min = 0, max = 2, step = 1, validValues = b.valid, eventOnly = true),
                    CharDef(b.nameIid, "23", "string", listOf("pr")),
                    CharDef(b.indexIid, "CB", "uint8", listOf("pr"), min = 1, max = 255, step = 1),
                ),
            )
        }
        services = list
        for (s in list) for (c in s.chars) byIid[c.iid] = c
    }

    fun char(iid: Int): CharDef? = byIid[iid]

    /** Firma estable del contenido (identificadores y nombres) para decidir cuándo sube c#. */
    fun signature(): String = "v2|" + inputs.joinToString(",") { "${it.identifier}:${it.type}:${it.name}" }

    /** JSON de GET /accessories. [valueOf] da el valor actual de cada característica legible. */
    fun accessoriesJson(valueOf: (Int) -> Any?): String {
        val services = services.map { s ->
            val m = LinkedHashMap<String, Any?>()
            m["iid"] = s.iid
            m["type"] = s.type
            if (s.primary) m["primary"] = true
            m["hidden"] = false
            if (s.linked.isNotEmpty()) m["linked"] = s.linked
            m["characteristics"] = s.chars.map { c -> charJson(c, valueOf) }
            m
        }
        return Json.write(mapOf("accessories" to listOf(mapOf("aid" to Iid.AID, "services" to services))))
    }

    fun charJson(c: CharDef, valueOf: (Int) -> Any?): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["iid"] = c.iid
        m["type"] = c.type
        m["perms"] = c.perms
        m["format"] = c.format
        if (c.eventOnly) m["value"] = null
        else if (c.readable) valueOf(c.iid)?.let { m["value"] = it }
        c.unit?.let { m["unit"] = it }
        c.min?.let { m["minValue"] = it }
        c.max?.let { m["maxValue"] = it }
        c.step?.let { m["minStep"] = it }
        if (c.format == "string" && c.maxLen != null) m["maxLen"] = c.maxLen
        c.validValues?.let { if (c.iid != Iid.TV_ACTIVE && c.iid != Iid.SPK_ACTIVE) m["valid-values"] = it }
        return m
    }

    companion object {
        /** Identificadores de un TLV8 de DisplayOrder (base64); null si no es válido. */
        fun parseDisplayOrder(b64: String): List<Int>? = try {
            val bytes = Base64.getDecoder().decode(b64)
            Tlv8.readList(bytes).filter { it.first == 0x01 }.map { (_, v) ->
                if (v.size != 4) return null
                (v[0].toInt() and 0xFF) or ((v[1].toInt() and 0xFF) shl 8) or ((v[2].toInt() and 0xFF) shl 16) or ((v[3].toInt() and 0xFF) shl 24)
            }
        } catch (e: IllegalArgumentException) { null }

        /** Orden guardado aplicado a las entradas existentes; las que falten van al final en su orden natural. */
        fun applyOrder(saved: List<Int>, current: List<Int>): List<Int> {
            val present = current.toSet()
            val head = saved.filter { it in present }.distinct()
            return head + current.filter { it !in head }
        }

        /** TLV8 de DisplayOrder (en base64): cada identificador como uint32 LE, separados por 0x00. */
        fun displayOrder(ids: List<Int>): String {
            val items = ArrayList<Pair<Int, ByteArray>>()
            ids.forEachIndexed { i, id ->
                if (i > 0) items += 0x00 to ByteArray(0)
                items += 0x01 to byteArrayOf(id.toByte(), (id shr 8).toByte(), (id shr 16).toByte(), (id shr 24).toByte())
            }
            return Base64.getEncoder().encodeToString(Tlv8.write(items))
        }
    }
}
