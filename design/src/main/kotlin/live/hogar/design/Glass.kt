package live.hogar.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.roundToInt

private val ZeroF: () -> Float = { 0f }
private val NoX: () -> Float = { Float.NaN }

/**
 * Liquid Glass SIN desenfoque en tiempo real (el SoC no lo aguanta y Android 11 no tiene
 * RenderEffect). Capas, de abajo arriba:
 *  1. la región de la instantánea desenfocada del fondo ([GlowController]) que queda detrás
 *     del elemento (se mapea por posición en pantalla; se funde durante los cambios de color);
 *  2. relleno blanco translúcido + aclarado superior (vidrio en reposo) y, si [brighten] > 0,
 *     más luz (bandeja de la fila enfocada);
 *  3. brillo suave que se desplaza hacia el elemento enfocado ([highlightX], px en raíz);
 *  4. borde especular de 1 dp con degradado (más luz arriba-izquierda y abajo-derecha).
 *
 * [brighten] y [highlightX] son lambdas que se leen al dibujar: animarlos no recompone.
 */
fun Modifier.liquidGlass(
    shape: Shape,
    brighten: () -> Float = ZeroF,
    highlightX: () -> Float = NoX,
    clip: Boolean = true,
): Modifier = composed {
    val glow = LocalGlow.current
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    (if (clip) Modifier.clip(shape) else Modifier)
        .onGloballyPositioned { coords[0] = it }
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val strokePx = 1.dp.toPx()
            val specular = Brush.linearGradient(
                0f to Color.White.copy(alpha = 0.55f),
                0.35f to Color.White.copy(alpha = 0.10f),
                0.70f to Color.White.copy(alpha = 0.04f),
                1f to Color.White.copy(alpha = 0.30f),
                start = Offset.Zero,
                end = Offset(size.width, size.height),
            )
            // Relleno + luz superior en un solo degradado, creado una vez (crear shaders por cuadro
            // sube texturas de degradado en cada frame en este chip).
            val fill = Brush.verticalGradient(
                0f to Color.White.copy(alpha = Hogar.Color.Glass.alpha + 0.10f),
                0.6f to Hogar.Color.Glass,
            )
            val hiR = (size.height * 1.4f).coerceIn(80.dp.toPx(), 220.dp.toPx())
            val hiBrush = Brush.radialGradient(
                listOf(Color.White.copy(alpha = 0.20f), Color.Transparent),
                center = Offset(hiR, hiR), radius = hiR,
            )
            val hiSize = Size(hiR * 2, hiR * 2)
            onDrawBehind {
                // Sobre un fondo tan suave como el brillo, el vidrio translúcido se ve igual que uno
                // desenfocado; dibujar la instantánea detrás era lo más caro de cada cuadro.
                if (glow == null || !PerfFlags.glass) drawRect(Hogar.Color.GlassOpaque)
                // Una sola pasada (este chip paga cada capa translúcida a pantalla completa).
                drawRect(fill)
                val b = brighten()
                if (b > 0.001f) drawRect(Color.White, alpha = 0.11f * b)
                val hx = highlightX()
                val c = coords[0]
                if (PerfFlags.highlight && !hx.isNaN() && c != null && c.isAttached) {
                    val local = hx - c.positionInRoot().x
                    translate(local - hiR, size.height / 2f - hiR) {
                        drawRect(hiBrush, size = hiSize, alpha = (0.35f + 0.65f * b.coerceIn(0f, 1f)))
                    }
                }
                drawOutline(outline, specular, style = Stroke(strokePx))
            }
        }
}

/** Dibuja la región de la instantánea desenfocada que corresponde a este elemento. */
private fun DrawScope.drawBackdrop(g: GlowController, c: LayoutCoordinates?) {
    val next = g.nextBackdrop
    val root = g.rootSize
    if (next == null || c == null || !c.isAttached || root.width == 0 || root.height == 0) {
        drawRect(Hogar.Color.GlassOpaque)
        return
    }
    val pos = c.positionInRoot()
    val sx = next.width.toFloat() / root.width
    val sy = next.height.toFloat() / root.height
    val ox = (pos.x * sx).toInt().coerceIn(0, next.width - 1)
    val oy = (pos.y * sy).toInt().coerceIn(0, next.height - 1)
    val sw = ceil(size.width * sx).toInt().coerceIn(1, next.width - ox)
    val sh = ceil(size.height * sy).toInt().coerceIn(1, next.height - oy)
    val srcOff = IntOffset(ox, oy)
    val srcSize = IntSize(sw, sh)
    val dst = IntSize(size.width.roundToInt().coerceAtLeast(1), size.height.roundToInt().coerceAtLeast(1))
    val m = g.mix.value
    val prev = g.prevBackdrop
    if (prev != null && m < 1f) {
        drawImage(prev, srcOff, srcSize, IntOffset.Zero, dst, filterQuality = FilterQuality.Low)
        drawImage(next, srcOff, srcSize, IntOffset.Zero, dst, alpha = m, filterQuality = FilterQuality.Low)
    } else {
        drawImage(next, srcOff, srcSize, IntOffset.Zero, dst, filterQuality = FilterQuality.Low)
    }
}

/** Panel de vidrio (menús, bandejas, tarjetas de información). */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Hogar.Radius.Panel),
    brighten: () -> Float = ZeroF,
    highlightX: () -> Float = NoX,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.liquidGlass(shape, brighten, highlightX), content = content)
}
