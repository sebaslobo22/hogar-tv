package live.hogar.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/**
 * Cómo se ve el enfoque de un elemento (motor de enfoque de tvOS):
 *  - [scale]: crece con resorte `.snappy`;
 *  - [lift]: sombra que lo levanta;
 *  - [idleTilt]: grados de flote parallax lento mientras está enfocado y quieto;
 *  - [kickTilt]: grados de inclinación rápida hacia la flecha pulsada, antes de moverse;
 *  - [dim]: cuánto se oscurece cuando NO está enfocado;
 *  - [sheen]: franja de luz que lo cruza una vez al enfocar;
 *  - [rim]: filo de luz interior al enfocar.
 */
@Immutable
data class FocusStyle(
    val scale: Float,
    val lift: Dp,
    val idleTilt: Float = 0f,
    val kickTilt: Float = 0f,
    val dim: Float = 0f,
    val sheen: Boolean = false,
    val rim: Boolean = false,
    /** Al recibir el foco, la inclinación arranca en la dirección de la flecha y se asienta (parallax de llegada). */
    val arrival: Boolean = false,
)

object FocusStyles {
    /** Tarjetas de app: 1,10×, parallax completo. */
    val Card = FocusStyle(scale = 1.10f, lift = Hogar.Elevation.Card, idleTilt = 3.5f, kickTilt = 4.5f, dim = 0.20f, sheen = true, rim = true)
    /** Botones tipo píldora. */
    val Pill = FocusStyle(scale = 1.06f, lift = Hogar.Elevation.Pill, kickTilt = 0f)
    /** Filas de lista. */
    val Row = FocusStyle(scale = 1.02f, lift = Hogar.Elevation.Row)
    /** Controles redondos (reproductor). */
    val Round = FocusStyle(scale = 1.14f, lift = Hogar.Elevation.Pill)
    /** Icono de app tipo tvOS: 1,14×, sin sombra del sistema (la sombra va horneada), parallax por capas. */
    val Icon = FocusStyle(scale = 1.14f, lift = 0.dp, kickTilt = 4.5f, dim = 0.24f, arrival = true)
    /** Pestañas de la barra superior: crecen poco y se vuelven blancas. */
    val Tab = FocusStyle(scale = 1.08f, lift = 0.dp)
}

/**
 * Estado animado del enfoque de UN elemento. Todo se lee en la fase de dibujo/capa, de modo
 * que enfocar no recompone nada fuera de este elemento (ni siquiera el elemento mismo).
 */
@Stable
class FocusDriver internal constructor(private val scope: CoroutineScope, val style: FocusStyle) {
    /** 0 = sin foco, 1 = enfocado (con resorte `.snappy`; puede pasarse un poco de 1). */
    val amount = Animatable(0f)
    /** Igual que [amount] pero con `.smooth` (sin rebote): para fundidos de texto. */
    val smoothAmount = Animatable(0f)
    internal val kickX = Animatable(0f)
    internal val kickY = Animatable(0f)
    internal val sheen = Animatable(1f)
    internal val driftX = mutableFloatStateOf(0f)
    internal val driftY = mutableFloatStateOf(0f)
    internal val driftT = mutableFloatStateOf(0f)

    private var focused = false
    private var idleJob: Job? = null
    private var kickJob: Job? = null

    internal fun onFocusChanged(f: Boolean) {
        if (f == focused) return
        focused = f
        scope.launch { amount.animateTo(if (f) 1f else 0f, Hogar.Motion.snappy()) }
        if (style.arrival) scope.launch { smoothAmount.animateTo(if (f) 1f else 0f, Hogar.Motion.smooth()) }
        idleJob?.cancel()
        if (f) {
            if (style.arrival) arrive()
            if (style.sheen) scope.launch {
                sheen.snapTo(0f)
                sheen.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
            }
            // El flote continuo de tvOS responde al panel táctil del Siri Remote; con cruceta y en
            // este chip obligaba a redibujar la pantalla entera sin parar. Se omite a propósito.
            if (style.idleTilt > 0f && ContinuousFloat) idleJob = scope.launch { idleLoop() }
        }
    }

    /** Flote lento (dos senos de periodos distintos); solo corre mientras hay foco. */
    private suspend fun idleLoop() {
        delay(450)
        val t0 = androidx.compose.runtime.withFrameNanos { it }
        val tilt = style.idleTilt
        while (true) {
            androidx.compose.runtime.withFrameNanos { t ->
                val s = (t - t0) / 1_000_000_000f
                driftX.floatValue = sin(s * (2f * PI.toFloat() / 6.3f)) * tilt * 0.55f
                driftY.floatValue = sin(s * (2f * PI.toFloat() / 5.1f) + 1.3f) * tilt
                driftT.floatValue = sin(s * (2f * PI.toFloat() / 4.2f) + 0.4f)
            }
        }
    }

    /** Inclinación horizontal normalizada (-1..1): positiva = hacia la derecha. */
    fun parallaxX(): Float = if (style.kickTilt > 0f) (kickY.value / style.kickTilt).coerceIn(-1.2f, 1.2f) else 0f

    /** Inclinación vertical normalizada (-1..1): positiva = hacia abajo. */
    fun parallaxY(): Float = if (style.kickTilt > 0f) (-kickX.value / style.kickTilt).coerceIn(-1.2f, 1.2f) else 0f

    /** Llegada con el impulso de la flecha que movió el foco hasta aquí; se asienta con `.snappy`. */
    private fun arrive() {
        val fresh = android.os.SystemClock.uptimeMillis() - lastPressAt < 450L
        val dx = if (fresh) lastDx else 0f
        val dy = if (fresh) lastDy else 0f
        lastPressAt = 0L
        if (dx == 0f && dy == 0f) return
        kickJob?.cancel()
        kickJob = scope.launch {
            kickY.snapTo(dx * style.kickTilt * 0.7f)
            kickX.snapTo(-dy * style.kickTilt * 0.7f)
            launch { kickY.animateTo(0f, Hogar.Motion.snappy()) }
            kickX.animateTo(0f, Hogar.Motion.snappy())
        }
    }

    /** Inclinación rápida hacia la flecha pulsada, que luego se asienta sola. */
    internal fun onKey(e: KeyEvent) {
        if (!focused || style.kickTilt <= 0f || e.type != KeyEventType.KeyDown) return
        var ky = 0f
        var kx = 0f
        when (e.key) {
            Key.DirectionRight -> ky = TiltSign * style.kickTilt
            Key.DirectionLeft -> ky = -TiltSign * style.kickTilt
            Key.DirectionDown -> kx = -TiltSign * style.kickTilt
            Key.DirectionUp -> kx = TiltSign * style.kickTilt
            else -> return
        }
        if (style.arrival) {
            lastDx = if (ky > 0f) 1f else if (ky < 0f) -1f else 0f
            lastDy = if (kx < 0f) 1f else if (kx > 0f) -1f else 0f
            lastPressAt = android.os.SystemClock.uptimeMillis()
        }
        kickJob?.cancel()
        kickJob = scope.launch {
            launch { kickY.animateTo(ky, Hogar.Motion.quick()) }
            kickX.animateTo(kx, Hogar.Motion.quick())
            delay(110)
            launch { kickY.animateTo(0f, Hogar.Motion.smooth()) }
            kickX.animateTo(0f, Hogar.Motion.smooth())
        }
    }

    private companion object {
        /** Última flecha pulsada (el foco es único): la usa el icono que recibe el foco para su llegada. */
        var lastDx = 0f
        var lastDy = 0f
        var lastPressAt = 0L
        /** Signo de rotationX/Y en graphicsLayer; si en el TV se ve al revés, cambiar a -1f. */
        const val TiltSign = 1f
        /** Flote continuo en reposo (ver onFocusChanged). */
        const val ContinuousFloat = false
    }
}

@Composable
fun rememberFocusDriver(style: FocusStyle): FocusDriver {
    val scope = rememberCoroutineScope()
    return remember(style) { FocusDriver(scope, style) }
}

/** Colores de contenido que pasan de claro (reposo) a oscuro (foco blanco), leídos al dibujar. */
@Stable
class FocusColors(private val d: FocusDriver) {
    private fun a() = d.amount.value.coerceIn(0f, 1f)
    fun primary(): Color = androidx.compose.ui.graphics.lerp(Hogar.Color.Text, Hogar.Color.TextOnFocused, a())
    fun secondary(): Color = androidx.compose.ui.graphics.lerp(Hogar.Color.TextSecondary, Hogar.Color.TextOnFocused.copy(alpha = 0.65f), a())
    /** Pestañas: gris translúcido en reposo y oscuro sobre blanco al enfocar. */
    fun tab(): Color = androidx.compose.ui.graphics.lerp(Hogar.Color.TextSecondary, Hogar.Color.TextOnFocused, a())
    fun tertiary(): Color = androidx.compose.ui.graphics.lerp(Hogar.Color.TextTertiary, Hogar.Color.TextOnFocused.copy(alpha = 0.5f), a())
}

/**
 * Enfoque al estilo tvOS: crece, se levanta con sombra, flota con parallax, destella y se
 * oscurece si no está enfocado. Va ANTES de focusable/clickable en la cadena:
 * `Modifier.hogarFocus(driver, shape).clickable { }`.
 */
fun Modifier.hogarFocus(
    driver: FocusDriver,
    shape: Shape,
    onFocus: (Boolean) -> Unit = {},
): Modifier {
    val style = driver.style
    return this
        .onFocusChanged {
            val f = it.isFocused || it.hasFocus
            driver.onFocusChanged(f)
            onFocus(f)
        }
        .onPreviewKeyEvent { driver.onKey(it); false }
        .graphicsLayer {
            val a = driver.amount.value
            val s = 1f + (style.scale - 1f) * a
            scaleX = s
            scaleY = s
            if (style.idleTilt > 0f || style.kickTilt > 0f) {
                rotationX = driver.kickX.value + driver.driftX.floatValue * a
                rotationY = driver.kickY.value + driver.driftY.floatValue * a
                translationY = -(a * 2.5f + driver.driftT.floatValue * a * 2f) * density
                cameraDistance = 16f * density
            }
            shadowElevation = if (PerfFlags.shadow) style.lift.toPx() * a.coerceIn(0f, 1.2f) else 0f
            ambientShadowColor = Color.Black
            spotShadowColor = Color.Black
            this.shape = shape
            clip = true
        }
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val rimPx = 1.5.dp.toPx()
            val hiR = size.width * 0.75f
            val hi = Brush.radialGradient(
                listOf(Color.White.copy(alpha = 0.26f), Color.Transparent),
                center = Offset(hiR, hiR), radius = hiR,
            )
            val hiSize = Size(hiR * 2, hiR * 2)
            val band = size.width * 0.45f
            val sheenBrush = Brush.linearGradient(
                0f to Color.Transparent,
                0.5f to Color.White.copy(alpha = 0.30f),
                1f to Color.Transparent,
                start = Offset.Zero, end = Offset(band, band * 0.35f),
            )
            val bandSize = Size(band, size.height)
            onDrawWithContent {
                drawContent()
                val a = driver.amount.value.coerceIn(0f, 1f)
                // El atenuado de las tarjetas lo aplica su capa (alfa), sin otra pasada encima.
                if (a > 0.02f) {
                    if (style.idleTilt > 0f) {
                        // Brillo especular que sigue a la inclinación.
                        val nx = ((driver.kickY.value + driver.driftY.floatValue * a) / 6f).coerceIn(-1f, 1f)
                        val ny = ((driver.kickX.value + driver.driftX.floatValue * a) / 6f).coerceIn(-1f, 1f)
                        val cx = size.width * (0.5f + 0.42f * nx)
                        val cy = size.height * (0.30f - 0.30f * ny)
                        translate(cx - hiR, cy - hiR) { drawRect(hi, size = hiSize, alpha = a) }
                    }
                    if (style.rim) drawOutline(outline, Color.White.copy(alpha = 0.34f * a), style = Stroke(rimPx))
                }
                if (style.sheen) {
                    val p = driver.sheen.value
                    if (p < 0.999f && a > 0.02f) {
                        translate(-band + p * (size.width + band * 2f), 0f) {
                            drawRect(sheenBrush, size = bandSize, alpha = a)
                        }
                    }
                }
            }
        }
}
