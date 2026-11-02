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
        /** Flecha/Aceptar: mueve el puntero si está activo y, si no, el foco por accesibilidad (cualquier app). */
        data class Dpad(val keyCode: Int) : Action
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
        ARROW_UP -> Action.Dpad(KEYCODE_DPAD_UP)
        ARROW_DOWN -> Action.Dpad(KEYCODE_DPAD_DOWN)
        ARROW_LEFT -> Action.Dpad(KEYCODE_DPAD_LEFT)
        ARROW_RIGHT -> Action.Dpad(KEYCODE_DPAD_RIGHT)
        SELECT -> Action.Dpad(KEYCODE_DPAD_CENTER)
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

    // ---- Luz nocturna, luz musical, Reproduciendo ahora y salvapantallas (valores por defecto: pruebas)
    fun nightLightOn(): Boolean = false
    fun setNightLightOn(on: Boolean) {}
    /** 1..100. */
    fun nightLightIntensity(): Int = 50
    fun setNightLightIntensity(v: Int) {}
    /** 0 = apagado, 1 = paleta, 2 = paleta + ritmo. */
    fun lightMode(): Int = 0
    fun setLightMode(mode: Int) {}
    fun nowPlayingOpen(): Boolean = false
    fun setNowPlayingOpen(open: Boolean) {}
    fun screensaverOn(): Boolean = true
    fun setScreensaverOn(on: Boolean) {}
}

/** Correspondencias puras entre los ajustes de Hogar y las características de HomeKit. */
internal object Mappings {
    // Luz nocturna: modos de NightMode.id en SharedPreferences "nightlight".
    const val NIGHT_OFF = "off"
    const val NIGHT_ALWAYS = "always"

    /** On de la bombilla: siempre encendida es On; apagada, Off; con horario, lo que diga el filtro ahora mismo. */
    fun nightOn(mode: String?, scheduleActive: Boolean?): Boolean = when (mode) {
        null, NIGHT_OFF -> false
        NIGHT_ALWAYS -> true
        else -> scheduleActive == true
    }

    fun brightness(intensity: Int): Int = intensity.coerceIn(1, 100)

    // Iluminación: LightPrefs.mode.
    const val LIGHT_OFF = 0
    const val LIGHT_PALETTE = 1
    const val LIGHT_RHYTHM = 2

    fun musicOn(mode: Int) = mode >= LIGHT_PALETTE
    fun rhythmOn(mode: Int) = mode == LIGHT_RHYTHM

    /** Luz musical: encender pasa a Paleta (si ya estaba con ritmo se queda); apagar apaga también el ritmo. */
    fun afterMusic(mode: Int, on: Boolean): Int =
        if (on) (if (mode == LIGHT_OFF) LIGHT_PALETTE else mode) else LIGHT_OFF

    /** Ritmo: encender implica Luz musical; apagar deja la Paleta puesta. */
    fun afterRhythm(mode: Int, on: Boolean): Int =
        if (on) LIGHT_RHYTHM else (if (mode == LIGHT_RHYTHM) LIGHT_PALETTE else mode)

    // Salvapantallas de música: NowPlayingPrefs.autoMode (0 = solo música, 1 = siempre, 2 = nunca).
    const val SAVER_NEVER = 2

    fun saverOn(setting: Int) = setting != SAVER_NEVER
    fun afterSaver(setting: Int, on: Boolean): Int = if (on) (if (setting == SAVER_NEVER) 0 else setting) else SAVER_NEVER
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
            Iid.TV_DISPLAY_ORDER -> AccessoryDb.displayOrder(AccessoryDb.applyOrder(store.displayOrder(), db.inputs.map { it.identifier }))
            Iid.SPK_MUTE -> backend.isMuted()
            Iid.SPK_VOLUME_TYPE -> 3 // absoluta
            Iid.SPK_VOLUME -> backend.volume().coerceIn(0, 100)
            Iid.LABEL_NAMESPACE -> 1 // numerales arábigos
            else -> readExtra(iid) ?: readInput(iid)
        }
    }

    private fun readExtra(iid: Int): Any? {
        Button.entries.firstOrNull { iid == it.nameIid || iid == it.indexIid }?.let { b ->
            return if (iid == b.nameIid) b.label else b.index
        }
        for (e in Extra.entries) {
            when (iid) {
                e.nameIid -> return e.label
                e.configuredIid -> return store.name(e.slot) ?: e.label
                e.brightnessIid -> return if (e == Extra.NIGHT) Mappings.brightness(backend.nightLightIntensity()) else null
                e.onIid -> return when (e) {
                    Extra.NIGHT -> backend.nightLightOn()
                    Extra.MUSIC -> Mappings.musicOn(backend.lightMode())
                    Extra.RHYTHM -> Mappings.rhythmOn(backend.lightMode())
                    Extra.NOW_PLAYING -> backend.nowPlayingOpen()
                    Extra.SAVER -> backend.screensaverOn()
                }
            }
        }
        return null
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
            Iid.TV_DISPLAY_ORDER -> {
                // El dueño reordena las entradas en Casa: se guarda (solo identificadores que existen).
                val ids = AccessoryDb.parseDisplayOrder(v as String) ?: return HapStatus.INVALID_VALUE
                store.setDisplayOrder(ids.filter { it in inputsById })
            }
            Iid.SPK_MUTE -> backend.setMuted(v as Boolean)
            Iid.SPK_VOLUME -> backend.setVolume(n!!)
            Iid.SPK_VOLUME_SELECTOR -> backend.volumeStep(up = n == 0)
            else -> {
                writeExtra(iid, v, n, now)?.let { return it }
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

    /** Escribe en Luz nocturna e interruptores. null = no es una de ellas; si no, el código [HapStatus]. */
    private fun writeExtra(iid: Int, v: Any, n: Int?, now: Long): Int? {
        Extra.ofConfiguredName(iid)?.let { e ->
            store.setName(e.slot, (v as String).trim().ifEmpty { e.label })
            return HapStatus.OK
        }
        if (iid == Extra.NIGHT.brightnessIid) { backend.setNightLightIntensity(n!!); return HapStatus.OK }
        val e = Extra.ofOn(iid) ?: return null
        val on = v as Boolean
        when (e) {
            Extra.NIGHT -> backend.setNightLightOn(on)
            Extra.MUSIC -> backend.setLightMode(Mappings.afterMusic(backend.lightMode(), on))
            Extra.RHYTHM -> backend.setLightMode(Mappings.afterRhythm(backend.lightMode(), on))
            Extra.NOW_PLAYING -> {
                backend.setNowPlayingOpen(on)
                holds[iid] = Hold(on, now + SHORT_HOLD_MS)
            }
            Extra.SAVER -> backend.setScreensaverOn(on)
        }
        // La luz musical y el ritmo comparten un ajuste: ambos cambian a la vez.
        return HapStatus.OK
    }

    /** Valor actual de las características con eventos. */
    private fun evented(): List<Int> = buildList {
        add(Iid.TV_CONFIGURED_NAME); add(Iid.TV_ACTIVE); add(Iid.TV_ACTIVE_ID); add(Iid.TV_DISPLAY_ORDER)
        add(Iid.SPK_MUTE); add(Iid.SPK_ACTIVE); add(Iid.SPK_VOLUME); add(Iid.SPK_VOLUME_TYPE); add(Iid.TV_SLEEP_DISCOVERY)
        for (e in Extra.entries) {
            add(e.onIid); add(e.configuredIid)
            if (e == Extra.NIGHT) add(e.brightnessIid)
        }
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
        const val SHORT_HOLD_MS = 3_000L
    }
}
