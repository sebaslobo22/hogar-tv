package live.hogar.player

/** Cola de reproducción (álbum). Sin Android: se prueba en la JVM. */
class PlayQueue<T>(items: List<T> = emptyList(), startIndex: Int = 0) {
    private var list: List<T> = items
    var index: Int = if (items.isEmpty()) 0 else startIndex.coerceIn(0, items.lastIndex)
        private set

    val size: Int get() = list.size
    val items: List<T> get() = list
    val current: T? get() = list.getOrNull(index)
    val hasNext: Boolean get() = index + 1 < list.size
    val hasPrevious: Boolean get() = index > 0
    fun peekNext(): T? = list.getOrNull(index + 1)

    /** Avanza y devuelve la pista nueva; al final devuelve null y no se mueve. */
    fun next(): T? {
        if (!hasNext) return null
        index++
        return current
    }

    fun previous(): T? {
        if (!hasPrevious) return null
        index--
        return current
    }

    fun jumpTo(i: Int): T? {
        if (list.isEmpty()) return null
        index = i.coerceIn(0, list.lastIndex)
        return current
    }

    fun replace(items: List<T>, startIndex: Int = 0) {
        list = items
        index = if (items.isEmpty()) 0 else startIndex.coerceIn(0, items.lastIndex)
    }
}

/** Orden "natural": "pista 2" antes que "pista 10". Ignora mayúsculas. */
fun naturalCompare(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            var ei = i
            while (ei < a.length && a[ei].isDigit()) ei++
            var ej = j
            while (ej < b.length && b[ej].isDigit()) ej++
            val na = a.substring(i, ei).trimStart('0')
            val nb = b.substring(j, ej).trimStart('0')
            if (na.length != nb.length) return na.length - nb.length
            val c = na.compareTo(nb)
            if (c != 0) return c
            i = ei
            j = ej
        } else {
            val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (c != 0) return c
            i++
            j++
        }
    }
    return (a.length - i) - (b.length - j)
}

/** "1234-ABCD:Música/Jazz" -> "Jazz"; "primary:Music" -> "Music". */
fun folderLabel(documentId: String): String {
    val afterColon = documentId.substringAfter(':', documentId)
    val last = afterColon.trimEnd('/').substringAfterLast('/')
    return last.ifBlank { "Almacenamiento" }
}
