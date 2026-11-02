package live.hogar.launcher

/** Preferencias del usuario sobre su pantalla de inicio (lo que se guarda en disco). */
data class LauncherPrefs(
    /** Paquetes fijados, en el orden en que se muestran. */
    val pinned: List<String> = emptyList(),
    val hidden: Set<String> = emptySet(),
    /** Orden manual de la fila "Apps"; lo no listado va al final, alfabético. */
    val order: List<String> = emptyList(),
    val showHidden: Boolean = false,
    /** Paquetes cuya tarjeta usa el banner original de la app en vez de la tarjeta Hogar. */
    val originalLogo: Set<String> = emptySet(),
    /** La pista "Mantén OK para opciones" solo se muestra hasta que el usuario usa el menú o abre una app. */
    val hintSeen: Boolean = false,
)

data class Arrangement(val pinned: List<String>, val rest: List<String>)

/** Lógica pura (sin Android) de fijar, ocultar y reordenar. */
object LauncherLogic {

    /** Apps de relleno de fábrica (Skyworth/MediaTek) que se ocultan hasta que el usuario decida. */
    val FactoryFiller = setOf(
        "com.coocaa.demonow.lightining", "com.mediatek.androidbox", "com.smartdevice.dailyshortcut",
        "com.smartdevice.epopplayer", "com.smartdevice.dailymanage", "com.smartdevice.dtv.icast",
        "com.smartdevice.myapp", "com.smartdevice.recommendation", "com.Smartdevice.emanual",
        "org.liskovsoft.androidtv.rukeyboard",
    )

    /** [installed] llega ya en orden alfabético. */
    fun arrange(installed: List<String>, prefs: LauncherPrefs): Arrangement {
        val inst = installed.toSet()
        fun visible(p: String) = prefs.showHidden || p !in prefs.hidden
        val pinned = prefs.pinned.distinct().filter { it in inst && visible(it) }
        val pinnedAll = prefs.pinned.toSet()
        val candidates = installed.filter { it !in pinnedAll && visible(it) }
        val candSet = candidates.toSet()
        val ordered = prefs.order.distinct().filter { it in candSet }
        val orderedSet = ordered.toSet()
        return Arrangement(pinned, ordered + candidates.filter { it !in orderedSet })
    }

    fun togglePin(prefs: LauncherPrefs, pkg: String): LauncherPrefs =
        if (pkg in prefs.pinned) prefs.copy(pinned = prefs.pinned - pkg)
        else prefs.copy(pinned = prefs.pinned + pkg, hidden = prefs.hidden - pkg)

    fun toggleHidden(prefs: LauncherPrefs, pkg: String): LauncherPrefs =
        if (pkg in prefs.hidden) prefs.copy(hidden = prefs.hidden - pkg)
        else prefs.copy(hidden = prefs.hidden + pkg, pinned = prefs.pinned - pkg)

    fun toggleOriginalLogo(prefs: LauncherPrefs, pkg: String): LauncherPrefs =
        if (pkg in prefs.originalLogo) prefs.copy(originalLogo = prefs.originalLogo - pkg)
        else prefs.copy(originalLogo = prefs.originalLogo + pkg)

    fun markHintSeen(prefs: LauncherPrefs): LauncherPrefs = if (prefs.hintSeen) prefs else prefs.copy(hintSeen = true)

    fun setShowHidden(prefs: LauncherPrefs, value: Boolean): LauncherPrefs = prefs.copy(showHidden = value)

    /** Mueve [pkg] [delta] lugares dentro de su fila (-1 izquierda, +1 derecha). En el borde no hace nada. */
    fun move(installed: List<String>, prefs: LauncherPrefs, pkg: String, delta: Int): LauncherPrefs {
        val a = arrange(installed, prefs)
        val inPinned = pkg in a.pinned
        val section = when {
            inPinned -> a.pinned
            pkg in a.rest -> a.rest
            else -> return prefs
        }
        val i = section.indexOf(pkg)
        val j = i + delta
        if (j !in section.indices) return prefs
        val moved = section.toMutableList().also { it[i] = it[j]; it[j] = pkg }
        val movedSet = moved.toSet()
        return if (inPinned) prefs.copy(pinned = moved + prefs.pinned.filter { it !in movedSet })
        else prefs.copy(order = moved + prefs.order.filter { it !in movedSet })
    }

    fun encode(list: Collection<String>): String = list.joinToString("\n")
    fun decode(raw: String?): List<String> = raw?.split('\n')?.filter { it.isNotEmpty() }.orEmpty()
}
