package live.hogar.homekit

/** Valores de la característica RemoteKey y qué hace cada uno en este TV. */
internal object RemoteKeys {
    const val REWIND = 0
    const val FAST_FORWARD = 1
    const val NEXT_TRACK = 2
    const val PREVIOUS_TRACK = 3
    const val ARROW_UP = 4
    const val ARROW_DOWN = 5
    const val ARROW_LEFT = 6
    const val ARROW_RIGHT = 7
    const val SELECT = 8
    const val BACK = 9
    const val EXIT = 10
    const val PLAY_PAUSE = 11
    const val INFORMATION = 15

    // Códigos de android.view.KeyEvent (fijos; aquí no se importa Android para poder probar en JVM).
    const val KEYCODE_DPAD_UP = 19
    const val KEYCODE_DPAD_DOWN = 20
    const val KEYCODE_DPAD_LEFT = 21
    const val KEYCODE_DPAD_RIGHT = 22
    const val KEYCODE_DPAD_CENTER = 23
    const val KEYCODE_MEDIA_PLAY_PAUSE = 85
    const val KEYCODE_MEDIA_NEXT = 87
    const val KEYCODE_MEDIA_PREVIOUS = 88
    const val KEYCODE_MEDIA_REWIND = 89
    const val KEYCODE_MEDIA_FAST_FORWARD = 90

    sealed interface Action {
        /** Tecla multimedia que se entrega con AudioManager.dispatchMediaKeyEvent. */
        data class Media(val keyCode: Int) : Action
        /** Flecha/Aceptar: solo funciona si el modo puntero de Hogar está activo. */
        data class Pointer(val keyCode: Int) : Action
        data object Back : Action
        data object Home : Action
        data object Info : Action
        data object None : Action
    }

    fun actionFor(key: Int): Action = when (key) {
        REWIND -> Action.Media(KEYCODE_MEDIA_REWIND)
        FAST_FORWARD -> Action.Media(KEYCODE_MEDIA_FAST_FORWARD)
        NEXT_TRACK -> Action.Media(KEYCODE_MEDIA_NEXT)
        PREVIOUS_TRACK -> Action.Media(KEYCODE_MEDIA_PREVIOUS)
        ARROW_UP -> Action.Pointer(KEYCODE_DPAD_UP)
        ARROW_DOWN -> Action.Pointer(KEYCODE_DPAD_DOWN)
        ARROW_LEFT -> Action.Pointer(KEYCODE_DPAD_LEFT)
        ARROW_RIGHT -> Action.Pointer(KEYCODE_DPAD_RIGHT)
        SELECT -> Action.Pointer(KEYCODE_DPAD_CENTER)
        BACK -> Action.Back
        EXIT -> Action.Home
        PLAY_PAUSE -> Action.Media(KEYCODE_MEDIA_PLAY_PAUSE)
        INFORMATION -> Action.Info
        else -> Action.None
    }
}

/** Lo que el servidor necesita del TV. La implementación real (Android) vive en [AndroidTvBackend]. */
internal interface TvBackend {
    fun isAwake(): Boolean
    fun sleep(): Boolean
    fun wake()
    /** Paquete de la app en primer plano, o null si no se sabe. */
    fun foregroundKey(): String?
    fun launch(key: String): Boolean
    fun remoteKey(code: Int): Boolean
    /** 0..100. */
    fun volume(): Int
    fun setVolume(percent: Int)
    fun isMuted(): Boolean
    fun setMuted(muted: Boolean)
    fun volumeStep(up: Boolean)
    fun openSettings()
}

/**
 * Lógica de la Televisión sin Android: lee y escribe características contra un [TvBackend],
 * recuerda el último valor para detectar cambios (eventos) y mantiene valores "optimistas"
 * unos segundos tras una orden (Casa no debe ver el interruptor volver atrás mientras la
 * pantalla tarda en apagarse).
 */
internal class TvController(
    val db: AccessoryDb,
    private val store: HomeKitStore,
    private val backend: TvBackend,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Hold(val value: Any, val until: Long)

    private val holds = HashMap<Int, Hold>()
    private val snapshot = HashMap<Int, Any?>()
    private var lastActiveId = db.inputs.firstOrNull()?.identifier ?: 1

    private val inputsById = db.inputs.associateBy { it.identifier }

    fun defaultName(): String = store.name("tv") ?: TV_NAME

    @Synchronized
    fun read(iid: Int): Any? {
        holds[iid]?.let { h -> if (clock() < h.until) return h.value else holds.remove(iid) }
        return when (iid) {
            Iid.MANUFACTURER -> "Hogar"
            Iid.MODEL -> "Hogar TV"
            Iid.NAME -> TV_NAME
            Iid.SERIAL -> store.deviceId.replace(":", "")
            Iid.FIRMWARE -> "1.0.0"
            Iid.PROTOCOL_VERSION -> "1.1.0"
            Iid.TV_CONFIGURED_NAME -> defaultName()
            Iid.TV_ACTIVE, Iid.SPK_ACTIVE -> if (backend.isAwake()) 1 else 0
            Iid.TV_ACTIVE_ID -> activeIdentifier()
            Iid.TV_SLEEP_DISCOVERY -> 1
            Iid.TV_DISPLAY_ORDER -> AccessoryDb.displayOrder(db.inputs.map { it.identifier })
            Iid.SPK_MUTE -> backend.isMuted()
            Iid.SPK_VOLUME_TYPE -> 3 // absoluta
            Iid.SPK_VOLUME -> backend.volume().coerceIn(0, 100)
            else -> readInput(iid)
        }
    }

    private fun readInput(iid: Int): Any? {
        val id = Iid.inputOf(iid) ?: return null
        val input = inputsById[id] ?: return null
        return when (Iid.inputPart(iid)) {
            Iid.INPUT_NAME -> input.name
            Iid.INPUT_CONFIGURED_NAME -> store.name("in.$id") ?: input.name
            Iid.INPUT_TYPE -> input.type
            Iid.INPUT_IS_CONFIGURED -> 1
            Iid.INPUT_CURRENT_VISIBILITY, Iid.INPUT_TARGET_VISIBILITY -> store.visibility(id)
            Iid.INPUT_IDENTIFIER -> id
            else -> null
        }
    }

    /** La entrada activa es la del paquete en primer plano; si no es una entrada conocida se mantiene la última. */
    private fun activeIdentifier(): Int {
        val fg = backend.foregroundKey()
        if (fg != null) db.inputs.firstOrNull { it.key == fg }?.let { lastActiveId = it.identifier }
        return lastActiveId
    }

    /** Aplica una escritura de un controlador. Devuelve un código [HapStatus]. */
    @Synchronized
    fun write(iid: Int, raw: Any?): Int {
        val def = db.char(iid) ?: return HapStatus.NOT_FOUND
        if (!def.writable) return HapStatus.READ_ONLY
        val v = def.coerce(raw) ?: return HapStatus.INVALID_VALUE
        val n = (v as? Long)?.toInt()
        val now = clock()
        when (iid) {
            Iid.IDENTIFY -> Unit
            Iid.TV_CONFIGURED_NAME -> store.setName("tv", (v as String).trim().ifEmpty { TV_NAME })
            Iid.TV_ACTIVE, Iid.SPK_ACTIVE -> {
                val want = n!!
                val awake = backend.isAwake()
                if (want == 0 && awake) backend.sleep() else if (want == 1 && !awake) backend.wake()
                holds[Iid.TV_ACTIVE] = Hold(want, now + HOLD_MS)
                holds[Iid.SPK_ACTIVE] = Hold(want, now + HOLD_MS)
            }
            Iid.TV_ACTIVE_ID -> {
                val input = inputsById[n!!] ?: return HapStatus.INVALID_VALUE
                if (!backend.isAwake()) {
                    backend.wake()
                    holds[Iid.TV_ACTIVE] = Hold(1, now + HOLD_MS)
                    holds[Iid.SPK_ACTIVE] = Hold(1, now + HOLD_MS)
                }
                if (!backend.launch(input.key)) return HapStatus.COMMUNICATION_FAILURE
                holds[Iid.TV_ACTIVE_ID] = Hold(input.identifier, now + HOLD_MS)
                lastActiveId = input.identifier
            }
            Iid.TV_REMOTE_KEY -> backend.remoteKey(n!!)
            Iid.TV_POWER_MODE -> backend.openSettings()
            Iid.TV_DISPLAY_ORDER -> Unit // el orden lo fija Hogar; se acepta y se ignora
            Iid.SPK_MUTE -> backend.setMuted(v as Boolean)
            Iid.SPK_VOLUME -> backend.setVolume(n!!)
            Iid.SPK_VOLUME_SELECTOR -> backend.volumeStep(up = n == 0)
            else -> {
                val id = Iid.inputOf(iid) ?: return HapStatus.READ_ONLY
                if (id !in inputsById) return HapStatus.NOT_FOUND
                when (Iid.inputPart(iid)) {
                    Iid.INPUT_CONFIGURED_NAME -> store.setName("in.$id", (v as String).trim().ifEmpty { inputsById[id]!!.name })
                    Iid.INPUT_TARGET_VISIBILITY -> store.setVisibility(id, n!!)
                    Iid.INPUT_IS_CONFIGURED -> Unit
                    else -> return HapStatus.READ_ONLY
                }
            }
        }
        return HapStatus.OK
    }

    /** Valor actual de las características con eventos. */
    private fun evented(): List<Int> = buildList {
        add(Iid.TV_CONFIGURED_NAME); add(Iid.TV_ACTIVE); add(Iid.TV_ACTIVE_ID); add(Iid.TV_DISPLAY_ORDER)
        add(Iid.SPK_MUTE); add(Iid.SPK_ACTIVE); add(Iid.SPK_VOLUME); add(Iid.SPK_VOLUME_TYPE); add(Iid.TV_SLEEP_DISCOVERY)
        for (i in db.inputs) {
            add(Iid.inputChar(i.identifier, Iid.INPUT_CONFIGURED_NAME))
            add(Iid.inputChar(i.identifier, Iid.INPUT_CURRENT_VISIBILITY))
            add(Iid.inputChar(i.identifier, Iid.INPUT_TARGET_VISIBILITY))
            add(Iid.inputChar(i.identifier, Iid.INPUT_IS_CONFIGURED))
            add(Iid.inputChar(i.identifier, Iid.INPUT_TYPE))
        }
    }

    /** Relee el estado y devuelve los iid que cambiaron desde la última vez (la primera vez solo toma la foto). */
    @Synchronized
    fun refresh(): List<Int> {
        val first = snapshot.isEmpty()
        val changed = ArrayList<Int>()
        for (iid in evented()) {
            val v = read(iid)
            if (!first && snapshot[iid] != v) changed += iid
            snapshot[iid] = v
        }
        return changed
    }

    fun accessoriesJson(): String = db.accessoriesJson { read(it) }

    companion object {
        const val TV_NAME = "TV Daewoo"
        const val HOLD_MS = 6_000L
    }
}
