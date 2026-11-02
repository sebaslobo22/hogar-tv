package live.hogar.nowplaying

import android.util.Log
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import kotlin.math.abs

internal const val FocusTag = "HogarFocus"

internal enum class NpDir { Left, Right, Up, Down }

/**
 * Mapa de foco puro (sin Compose) de los controles de "Reproduciendo ahora".
 *
 * Lado (letra o cola): fila 0 = anterior / pausa / siguiente; fila 1 = TV, letra, cola, AirPlay, luz.
 * Centrado (ancho): una sola fila visual TV, anterior, pausa, siguiente, letra, cola, AirPlay, luz.
 * Izquierda/derecha se mueven dentro de la fila y se quedan en los extremos; arriba/abajo saltan al
 * botón de la otra fila con el centro X más cercano. `-1` = el foco se queda donde está.
 */
internal object NpFocusMap {
    const val PREV = 0
    const val PLAY = 1
    const val NEXT = 2
    const val TV = 3
    const val LYRICS = 4
    const val QUEUE = 5
    const val AIRPLAY = 6
    const val LIGHT = 7
    const val COUNT = 8
    val NAMES = arrayOf("anterior", "pausa", "siguiente", "tv", "letra", "cola", "airplay", "luz")

    private val SideTop = intArrayOf(PREV, PLAY, NEXT)
    private val SideBottom = intArrayOf(TV, LYRICS, QUEUE, AIRPLAY, LIGHT)
    private val WideOnly = intArrayOf(TV, PREV, PLAY, NEXT, LYRICS, QUEUE, AIRPLAY, LIGHT)

    fun rows(wide: Boolean): List<IntArray> = if (wide) listOf(WideOnly) else listOf(SideTop, SideBottom)

    /** Candidato con el centro X más cercano a [fromX]; empate: el primero. `NaN` (sin medir) se ignora. */
    fun nearestByX(fromX: Float, xs: FloatArray, candidates: IntArray): Int {
        var best = -1
        var bestD = Float.MAX_VALUE
        for (c in candidates) {
            val x = xs[c]
            if (x.isNaN() || fromX.isNaN()) continue
            val d = abs(x - fromX)
            if (d < bestD) { bestD = d; best = c }
        }
        return if (best >= 0) best else candidates.first()
    }

    fun target(from: Int, dir: NpDir, wide: Boolean, xs: FloatArray): Int {
        val rows = rows(wide)
        val r = rows.indexOfFirst { from in it }
        if (r < 0) return -1
        val row = rows[r]
        val i = row.indexOf(from)
        return when (dir) {
            NpDir.Left -> if (i > 0) row[i - 1] else -1
            NpDir.Right -> if (i < row.lastIndex) row[i + 1] else -1
            NpDir.Up -> if (r > 0) nearestByX(xs[from], xs, rows[r - 1]) else -1
            NpDir.Down -> if (r < rows.lastIndex) nearestByX(xs[from], xs, rows[r + 1]) else -1
        }
    }
}

/** Estado de foco de los controles: requesters fijos, centros X medidos y último control enfocado. */
internal class NpFocus {
    val req = Array(NpFocusMap.COUNT) { FocusRequester() }
    val xs = FloatArray(NpFocusMap.COUNT) { Float.NaN }
    var wide = true
    /** Control con foco ahora (-1 = ninguno). */
    var focusedId = -1
    /** Último control enfocado: a él vuelve el foco cuando reaparecen los controles. */
    var last = NpFocusMap.PLAY

    @OptIn(ExperimentalComposeUiApi::class)
    fun destination(id: Int, dir: NpDir): FocusRequester {
        val t = NpFocusMap.target(id, dir, wide, xs)
        return if (t < 0) FocusRequester.Cancel else req[t]
    }
}

internal fun FocusRequester.tryFocus(): Boolean = runCatching { requestFocus() }.isSuccess

/** Conecta un botón al mapa: requester, flechas explícitas, enfocable solo si [enabled], y medida de su centro X. */
internal fun Modifier.npNode(plan: NpFocus, id: Int, enabled: () -> Boolean): Modifier = this
    .focusRequester(plan.req[id])
    .focusProperties { npProps(plan, id, enabled()) }
    .onGloballyPositioned { plan.xs[id] = it.positionInRoot().x + it.size.width / 2f }
    .onFocusChanged {
        if (it.isFocused || it.hasFocus) {
            if (plan.focusedId != id) Log.d(FocusTag, "foco -> ${NpFocusMap.NAMES[id]}")
            plan.focusedId = id; plan.last = id
        } else if (plan.focusedId == id) {
            plan.focusedId = -1
            Log.d(FocusTag, "foco <- ${NpFocusMap.NAMES[id]} (perdido)")
        }
    }

private fun FocusProperties.npProps(plan: NpFocus, id: Int, enabled: Boolean) {
    canFocus = enabled
    left = plan.destination(id, NpDir.Left)
    right = plan.destination(id, NpDir.Right)
    up = plan.destination(id, NpDir.Up)
    down = plan.destination(id, NpDir.Down)
}
