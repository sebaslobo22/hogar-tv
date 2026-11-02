package live.hogar.nowplaying

/** Elemento crudo de la cola de la sesión (sin tipos de Android: se prueba en la JVM). [icon] es un Bitmap opcional. */
class RawQueueItem(val id: Long, val title: String, val subtitle: String, val iconUri: String?, val icon: Any?)

/** Fila de la cola tal como se muestra. */
class QueueRow(val id: Long, val title: String, val subtitle: String, val iconUri: String?, val icon: Any?, val current: Boolean)

/** Cola de la sesión. [rows] vacía = la app no la comparte. */
class QueueState(val title: String? = null, val rows: List<QueueRow> = emptyList()) {
    val available: Boolean get() = rows.isNotEmpty()
    val currentIndex: Int get() = rows.indexOfFirst { it.current }
}

object QueueMapper {
    const val MAX_ROWS = 300
    const val UNKNOWN_ID = -1L

    /**
     * Marca la fila vigente: la de [activeId] (PlaybackState.activeQueueItemId) o, si la sesión no
     * lo informa, la primera cuyo título coincida con [currentTitle]. Sin título y sin id, ninguna.
     */
    fun map(raw: List<RawQueueItem>, activeId: Long, currentTitle: String?): List<QueueRow> {
        val items = raw.take(MAX_ROWS).filter { it.title.isNotBlank() || it.subtitle.isNotBlank() }
        var cur = if (activeId != UNKNOWN_ID) items.indexOfFirst { it.id == activeId } else -1
        if (cur < 0 && !currentTitle.isNullOrBlank()) cur = items.indexOfFirst { it.title.trim().equals(currentTitle.trim(), ignoreCase = true) }
        return items.mapIndexed { i, r -> QueueRow(r.id, r.title.trim(), r.subtitle.trim(), r.iconUri?.takeIf { it.isNotBlank() }, r.icon, i == cur) }
    }

    /** Lado en px de una miniatura: nunca mayor que el original (no se amplía) ni que el hueco. */
    fun thumbSidePx(nativePx: Int, slotPx: Int): Int = if (nativePx <= 0) 0 else minOf(nativePx, slotPx)
}

/**
 * Qué acepta la sesión según las acciones de su PlaybackState. Barajar y repetir no existen en el
 * PlaybackState del sistema: son las banderas de PlaybackStateCompat (repetir 1<<18, barajar
 * 1<<19 o 1<<23) y se envían como acciones personalizadas del mismo convenio.
 */
object SessionCaps {
    private const val SET_REPEAT_MODE = 1L shl 18
    private const val SET_SHUFFLE_MODE_ENABLED = 1L shl 19
    private const val SET_SHUFFLE_MODE = 1L shl 23
    private const val SKIP_TO_QUEUE_ITEM = 1L shl 12

    fun canRepeat(actions: Long) = actions and SET_REPEAT_MODE != 0L
    fun canShuffle(actions: Long) = actions and (SET_SHUFFLE_MODE_ENABLED or SET_SHUFFLE_MODE) != 0L
    fun canSkipToQueueItem(actions: Long) = actions and SKIP_TO_QUEUE_ITEM != 0L

    const val ACTION_SET_SHUFFLE = "android.support.v4.media.session.action.SET_SHUFFLE_MODE"
    const val ARG_SHUFFLE = "android.support.v4.media.session.ARGUMENT_SHUFFLE_MODE"
    const val ACTION_SET_REPEAT = "android.support.v4.media.session.action.SET_REPEAT_MODE"
    const val ARG_REPEAT = "android.support.v4.media.session.ARGUMENT_REPEAT_MODE"

    /** Ciclo de repetición: ninguna → todas → una. */
    fun nextRepeat(mode: Int) = when (mode) { 0 -> 2; 2 -> 1; else -> 0 }
}
