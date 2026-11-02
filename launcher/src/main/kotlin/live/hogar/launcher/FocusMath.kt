package live.hogar.launcher

/* Geometría pura de la navegación por foco (sin Android): se prueba en la JVM. */

/** Dirección de una flecha del mando; [viewDirection] es el valor de `View.FOCUS_*` correspondiente. */
internal enum class FocusDir(val viewDirection: Int) {
    UP(33), DOWN(130), LEFT(17), RIGHT(66);

    companion object {
        /** Desde un `KeyEvent.KEYCODE_DPAD_*`; null si no es una flecha. */
        fun ofKeyCode(code: Int): FocusDir? = when (code) {
            19 -> UP
            20 -> DOWN
            21 -> LEFT
            22 -> RIGHT
            else -> null
        }
    }
}

internal data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

internal object FocusMath {
    /**
     * Índice del mejor candidato en [dir] respecto a [from], o null si no hay ninguno. Misma idea que el
     * FocusFinder de Android: el candidato debe estar del lado de la flecha; se prefieren los que se solapan
     * con el "haz" de la fuente y, entre ellos, el de menor 13·(distancia en el eje)² + (desvío lateral)².
     */
    fun nearest(from: Box, candidates: List<Box>, dir: FocusDir): Int? {
        var best = -1
        var bestBeam = false
        var bestScore = Long.MAX_VALUE
        for ((i, c) in candidates.withIndex()) {
            if (c == from || !isCandidate(from, c, dir)) continue
            val beam = inBeam(from, c, dir)
            val major = majorDistance(from, c, dir).toLong()
            val minor = minorDistance(from, c, dir).toLong()
            val score = 13L * major * major + minor * minor
            val better = best < 0 || (beam && !bestBeam) || (beam == bestBeam && score < bestScore)
            if (better) { best = i; bestBeam = beam; bestScore = score }
        }
        return if (best < 0) null else best
    }

    private fun isCandidate(s: Box, d: Box, dir: FocusDir): Boolean = when (dir) {
        FocusDir.LEFT -> (s.right > d.right || s.left >= d.right) && s.left > d.left
        FocusDir.RIGHT -> (s.left < d.left || s.right <= d.left) && s.right < d.right
        FocusDir.UP -> (s.bottom > d.bottom || s.top >= d.bottom) && s.top > d.top
        FocusDir.DOWN -> (s.top < d.top || s.bottom <= d.top) && s.bottom < d.bottom
    }

    /** ¿El candidato se solapa con la franja de la fuente en el eje perpendicular a la flecha? */
    private fun inBeam(s: Box, d: Box, dir: FocusDir): Boolean = when (dir) {
        FocusDir.LEFT, FocusDir.RIGHT -> d.bottom > s.top && d.top < s.bottom
        FocusDir.UP, FocusDir.DOWN -> d.right > s.left && d.left < s.right
    }

    /** Hueco entre el borde de salida de la fuente y el borde de entrada del candidato (mínimo 1). */
    private fun majorDistance(s: Box, d: Box, dir: FocusDir): Int = maxOf(1, when (dir) {
        FocusDir.LEFT -> s.left - d.right
        FocusDir.RIGHT -> d.left - s.right
        FocusDir.UP -> s.top - d.bottom
        FocusDir.DOWN -> d.top - s.bottom
    })

    private fun minorDistance(s: Box, d: Box, dir: FocusDir): Int = when (dir) {
        FocusDir.LEFT, FocusDir.RIGHT -> Math.abs(s.centerY - d.centerY)
        FocusDir.UP, FocusDir.DOWN -> Math.abs(s.centerX - d.centerX)
    }
}
