package live.hogar.nowplaying

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import live.hogar.design.BoxBlur

/** Todo lo que dibuja la pantalla para una carátula, calculado UNA vez por canción. */
class PreparedArt(
    /** Carátula cuadrada con esquinas redondeadas, a [side] px. */
    val art: ImageBitmap,
    val side: Int,
    /** Sombra suave horneada, de lado [side] + 2·[pad]. */
    val shadow: ImageBitmap,
    val pad: Int,
    /** 4–6 colores del fondo animado (vivos y oscuros, ordenados para contraste). */
    val mesh: List<Int>,
    /** Color dominante (brillo del vidrio). */
    val accent: Int,
)

object ArtworkRender {
    /** 12 dp sobre una carátula de 440 dp. */
    private const val CORNER = 12f / 440f
    private const val SHADOW_DIV = 4

    /** Hilo de fondo. [targetPx] = lado deseado en pantalla; si [src] es menor, se amplía suavizada. */
    fun prepare(src: Bitmap?, targetPx: Int): PreparedArt {
        val px = src?.let { samplePixels(it) } ?: IntArray(0)
        val colors = Palette.dominant(px)
        val mesh = MeshPalette.build(px)
        if (src == null) {
            val empty = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            return PreparedArt(empty.asImageBitmap(), 0, empty.asImageBitmap(), 0, mesh, colors[0])
        }
        // Siempre al tamaño de pantalla: si la imagen es menor se amplía con filtro (el diseño no cambia).
        val side = targetPx.coerceAtLeast(1)
        val pad = (side * 0.16f).toInt()
        return PreparedArt(rounded(src, side), side, shadow(side, pad), pad, mesh, colors[0])
    }

    private fun samplePixels(b: Bitmap): IntArray {
        val s = Bitmap.createScaledBitmap(b, 24, 24, true)
        val px = IntArray(24 * 24)
        s.getPixels(px, 0, 24, 0, 0, 24, 24)
        if (s !== b) s.recycle()
        return px
    }

    private fun rounded(src: Bitmap, side: Int): ImageBitmap {
        val crop = ArtworkMath.squareCrop(src.width, src.height)
        val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val m = Matrix()
        m.setRectToRect(
            RectF(crop.x.toFloat(), crop.y.toFloat(), (crop.x + crop.side).toFloat(), (crop.y + crop.side).toFloat()),
            RectF(0f, 0f, side.toFloat(), side.toFloat()), Matrix.ScaleToFit.FILL,
        )
        shader.setLocalMatrix(m)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader }
        val r = side * CORNER
        Canvas(out).drawRoundRect(RectF(0f, 0f, side.toFloat(), side.toFloat()), r, r, p)
        return out.asImageBitmap()
    }

    /** Sombra a 1/4 de resolución (se estira con filtro bilineal: ya es borrosa). */
    private fun shadow(side: Int, pad: Int): ImageBitmap {
        val total = side + 2 * pad
        val w = (total / SHADOW_DIV).coerceAtLeast(8)
        val bmp = Bitmap.createBitmap(w, w, Bitmap.Config.ARGB_8888)
        val k = w.toFloat() / total
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 0, 0, 0) }
        val inset = pad * k
        val dy = pad * 0.30f * k
        val r = side * CORNER * k
        Canvas(bmp).drawRoundRect(RectF(inset, inset + dy, w - inset, w - inset + dy), r, r, p)
        val px = IntArray(w * w)
        bmp.getPixels(px, 0, w, 0, 0, w, w)
        BoxBlur.blur(px, w, w, (pad * k * 0.45f).toInt().coerceAtLeast(2))
        bmp.setPixels(px, 0, w, 0, 0, w, w)
        return bmp.asImageBitmap()
    }
}
