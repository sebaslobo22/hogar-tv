package live.hogar.design

import kotlin.math.roundToInt

import androidx.compose.ui.graphics.FilterQuality

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.LruCache
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Forma del brillo, compartida por el fondo (Compose) y la instantánea del vidrio (bitmap). */
internal object GlowSpec {
    const val CX1 = 0.40f; const val CY1 = 0.62f; const val R1 = 0.80f
    const val A1_CENTER = 0.78f; const val A1_MID = 0.30f
    const val CX2 = 0.96f; const val CY2 = 0.0f; const val R2 = 0.55f
    const val A2 = 0.30f
    /** Resolución de la instantánea de fondo (1/10 de 1920×1080). */
    const val SNAP_W = 192
    const val SNAP_H = 108
    const val BLUR_RADIUS = 5
}

/**
 * Fondo animado + instantánea desenfocada que usa el vidrio.
 *
 * El color del brillo cambia con un resorte `.smooth` (≈600 ms). Para cada color se renderiza
 * UNA vez una instantánea de baja resolución del fondo, se desenfoca con [BoxBlur] en un hilo
 * de fondo y se guarda en caché; el vidrio dibuja la región que queda detrás de él. Durante
 * el cambio se funden la instantánea anterior y la nueva.
 */
@Stable
class GlowController internal constructor(private val scope: CoroutineScope) {
    var prevColor by mutableStateOf(Hogar.Color.GlowDefault); private set
    var nextColor by mutableStateOf(Hogar.Color.GlowDefault); private set
    var prevBackdrop by mutableStateOf<ImageBitmap?>(null); private set
    var nextBackdrop by mutableStateOf<ImageBitmap?>(null); private set
    var rootSize by mutableStateOf(IntSize.Zero)
    /** 0 = todo en el estado anterior, 1 = todo en el nuevo. */
    val mix = Animatable(1f)

    private var job: Job? = null
    private var target: Int = GlowBackdrops.quantize(Hogar.Color.GlowDefault.toArgb())

    init { setTarget(Hogar.Color.GlowDefault, force = true) }

    fun colorNow(): Color = lerp(prevColor, nextColor, mix.value)

    fun setTarget(color: Color, force: Boolean = false) {
        val key = GlowBackdrops.quantize(color.toArgb())
        if (key == target && !force) return
        target = key
        job?.cancel()
        job = scope.launch {
            val bmp = GlowBackdrops.get(key)
            val cur = colorNow()
            val curBmp = if (mix.value > 0.5f) nextBackdrop else prevBackdrop
            prevColor = cur
            prevBackdrop = curBmp ?: bmp
            nextColor = Color(key)
            nextBackdrop = bmp
            mix.snapTo(0f)
            mix.animateTo(1f, Hogar.Motion.smooth())
        }
    }
}

/** Instantáneas desenfocadas por color (caché pequeña: ~83 KB cada una). */
internal object GlowBackdrops {
    private val cache = object : LruCache<Int, ImageBitmap>(24) {}

    /** Agrupa colores casi iguales (16 niveles por canal) para reutilizar instantáneas. */
    fun quantize(argb: Int): Int = (argb and 0xF0F0F0) or 0xFF000000.toInt() or 0x080808

    suspend fun get(key: Int): ImageBitmap =
        cache.get(key) ?: withContext(Dispatchers.Default) { render(key) }.also { cache.put(key, it) }

    private fun render(color: Int): ImageBitmap {
        val w = GlowSpec.SNAP_W
        val h = GlowSpec.SNAP_H
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = AndroidCanvas(bmp)
        c.drawColor(Hogar.Color.Background.toArgb())
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        fun withA(a: Float) = (color and 0xFFFFFF) or ((a * 255).toInt() shl 24)
        p.shader = RadialGradient(
            w * GlowSpec.CX1, h * GlowSpec.CY1, w * GlowSpec.R1,
            intArrayOf(withA(GlowSpec.A1_CENTER), withA(GlowSpec.A1_MID), withA(0f)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = RadialGradient(
            w * GlowSpec.CX2, h * GlowSpec.CY2, w * GlowSpec.R2,
            intArrayOf(withA(GlowSpec.A2), withA(0f)), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        BoxBlur.blur(px, w, h, GlowSpec.BLUR_RADIUS)
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        return bmp.asImageBitmap()
    }
}

val LocalGlow = compositionLocalOf<GlowController?> { null }

/** Dibuja el fondo: base oscura + brillo radial del color actual (sin asignaciones por cuadro, salvo el pincel al cambiar). */
internal fun DrawScope.drawGlow(g: GlowController) {
    // Una sola textura pequeña (ya desenfocada) estirada con filtro bilineal: el brillo es tan
    // suave que se ve igual que los degradados radiales, y cuesta una fracción en este chip.
    val next = g.nextBackdrop
    if (!PerfFlags.glow || next == null) { drawRect(Hogar.Color.Background); return }
    val dst = IntSize(size.width.roundToInt(), size.height.roundToInt())
    val m = g.mix.value
    val prev = g.prevBackdrop
    if (prev != null && m < 1f) {
        drawImage(prev, dstSize = dst, filterQuality = FilterQuality.Low)
        drawImage(next, dstSize = dst, alpha = m, filterQuality = FilterQuality.Low)
    } else {
        drawImage(next, dstSize = dst, filterQuality = FilterQuality.Low)
    }
}

/** Raíz de toda pantalla: provee el brillo y dibuja el fondo detrás del contenido. */
@Composable
fun HogarRoot(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val glow = remember { GlowController(scope) }
    CompositionLocalProvider(LocalGlow provides glow) {
        Box(
            modifier.fillMaxSize()
                .onSizeChanged { glow.rootSize = it }
                .drawBehind { drawGlow(glow) },
        ) { content() }
    }
}

/** Pide que el fondo brille con [color] mientras esta pantalla esté activa. */
@Composable
fun HogarGlow(color: Color) {
    val g = LocalGlow.current
    LaunchedEffect(g, color) { g?.setTarget(color) }
}
