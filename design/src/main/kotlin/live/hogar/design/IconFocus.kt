package live.hogar.design

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import kotlin.math.ceil
import kotlin.math.max

/**
 * Enfoque de un icono tipo tvOS: crece con `.snappy` (el hueco de la rejilla es mayor que su crecimiento, no pisa a los vecinos). NO recorta,
 * NO usa sombra del sistema (la sombra va horneada, ver [SoftShadow]) y NO inclina el icono
 * entero: el parallax lo hacen las capas del propio icono con [FocusDriver.parallaxX]/[FocusDriver.parallaxY].
 * Va ANTES de focusable en la cadena.
 */
fun Modifier.hogarIconFocus(driver: FocusDriver, onFocus: (Boolean) -> Unit = {}): Modifier {
    val scale = driver.style.scale
    return this
        .onFocusChanged {
            val f = it.isFocused || it.hasFocus
            driver.onFocusChanged(f)
            onFocus(f)
        }
        .onPreviewKeyEvent { driver.onKey(it); false }
        .graphicsLayer {
            val s = 1f + (scale - 1f) * driver.amount.value.coerceIn(0f, 1.25f)
            scaleX = s
            scaleY = s
        }
}

/** Geometría pura de la sombra horneada. */
object SoftShadowMath {
    class Layout(val padPx: Int, val bmpW: Int, val bmpH: Int, val q: Int)

    /** La sombra se hornea a 1/[q] de resolución con un margen de 2×[blurPx] por lado. */
    fun layout(wPx: Int, hPx: Int, blurPx: Int, q: Int = 4): Layout {
        val pad = max(0, blurPx * 2)
        val bw = max(1, ceil((wPx + 2f * pad) / q).toInt())
        val bh = max(1, ceil((hPx + 2f * pad) / q).toInt())
        return Layout(pad, bw, bh, q)
    }
}

/**
 * Sombra suave horneada UNA vez: un rectángulo redondeado negro desenfocado con [BoxBlur] a un
 * cuarto de resolución. Se dibuja ampliada detrás del icono enfocado, con alfa = cantidad de foco.
 * Sustituye a `shadowElevation`, que cuesta ~6 ms por cuadro en este chip.
 */
object SoftShadow {
    class Baked(val image: ImageBitmap, val padPx: Int, val q: Int)

    fun create(wPx: Int, hPx: Int, cornerPx: Float, blurPx: Int, q: Int = 4): Baked {
        val l = SoftShadowMath.layout(wPx, hPx, blurPx, q)
        val bmp = Bitmap.createBitmap(l.bmpW, l.bmpH, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() }
        val off = l.padPx.toFloat() / q
        c.drawRoundRect(RectF(off, off, off + wPx.toFloat() / q, off + hPx.toFloat() / q), cornerPx / q, cornerPx / q, p)
        val px = IntArray(l.bmpW * l.bmpH)
        bmp.getPixels(px, 0, l.bmpW, 0, 0, l.bmpW, l.bmpH)
        BoxBlur.blur(px, l.bmpW, l.bmpH, radius = max(1, blurPx / q / 2))
        bmp.setPixels(px, 0, l.bmpW, 0, 0, l.bmpW, l.bmpH)
        return Baked(bmp.asImageBitmap(), l.padPx, q)
    }
}
