package live.hogar.browser

/** Reglas de pestañas (sin Android): tope de pestañas y de WebViews vivos con expulsión por uso reciente. */
object TabPolicy {
    const val MAX_TABS = 6
    const val MAX_LIVE = 3

    /** Marca [id] como el más reciente (al final). */
    fun touch(order: List<Int>, id: Int): List<Int> = order.filter { it != id } + id

    /**
     * Pestañas cuyo WebView hay que destruir para quedar en [maxLive]. [lruOrder] va del menos al más
     * reciente. Nunca se expulsa la activa; solo cuentan las que están vivas.
     */
    fun evictions(lruOrder: List<Int>, live: Set<Int>, active: Int, maxLive: Int = MAX_LIVE): List<Int> {
        val excess = live.size - maxLive
        if (excess <= 0) return emptyList()
        val ordered = lruOrder.filter { it in live } + live.filter { it !in lruOrder }.sorted()
        return ordered.filter { it != active }.take(excess)
    }

    /** Índice de la pestaña que queda activa al cerrar la del índice [closed] (la vecina de la derecha, o la última). */
    fun activeAfterClose(sizeBefore: Int, closed: Int): Int? {
        if (sizeBefore <= 1) return null
        return closed.coerceAtMost(sizeBefore - 2)
    }

    fun canOpenNew(count: Int): Boolean = count < MAX_TABS
}
