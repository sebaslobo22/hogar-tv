package live.hogar.launcher.pointer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Círculo del puntero (estilo iPadOS) y aviso de cristal, en ventanas TYPE_ACCESSIBILITY_OVERLAY
 * (no piden permiso). El círculo se mueve solo con translationX/Y: no hay relayout por cuadro.
 * Con el puntero oculto no queda ninguna ventana añadida.
 */
internal class PointerOverlay(private val context: Context) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density

    private var layer: FrameLayout? = null
    private var dot: PointerDotView? = null
    private var hiding = false

    private var toastView: TextView? = null
    private val removeToast = Runnable { dropToast() }

    val isShown: Boolean get() = layer != null && !hiding

    fun show(x: Float, y: Float) {
        hiding = false
        if (layer == null) {
            val d = PointerDotView(context)
            val l = FrameLayout(context)
            l.addView(d, FrameLayout.LayoutParams(d.sizePx, d.sizePx))
            val ok = runCatching { wm.addView(l, fullScreenParams()) }.isSuccess
            if (!ok) return
            layer = l
            dot = d
        }
        dot?.animate()?.cancel()
        dot?.alpha = 1f
        moveTo(x, y)
    }

    fun moveTo(x: Float, y: Float) {
        val d = dot ?: return
        val half = d.sizePx / 2f
        d.translationX = x - half
        d.translationY = y - half
    }

    /** Pequeño pulso al tocar. */
    fun pulse() {
        val d = dot ?: return
        d.animate().cancel()
        d.scaleX = 0.8f
        d.scaleY = 0.8f
        d.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
    }

    fun hide() {
        val d = dot ?: return
        if (hiding) return
        hiding = true
        d.animate().alpha(0f).setDuration(180).withEndAction { if (hiding) removeNow() }.start()
    }

    fun removeNow() {
        hiding = false
        dot?.animate()?.cancel()
        layer?.let { runCatching { wm.removeViewImmediate(it) } }
        layer = null
        dot = null
    }

    fun showToast(text: String) {
        main.removeCallbacks(removeToast)
        dropToast()
        val tv = TextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = androidx.core.content.res.ResourcesCompat.getFont(context, live.hogar.design.R.font.hogar_text_semibold) ?: Typeface.DEFAULT_BOLD
            setPadding(dp(22), dp(12), dp(22), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(0xD91C1C1E.toInt())
                setStroke(dp(1), 0x33FFFFFF)
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            OVERLAY_FLAGS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(72)
        }
        if (runCatching { wm.addView(tv, lp) }.isSuccess) {
            toastView = tv
            main.postDelayed(removeToast, 2200)
        }
    }

    private fun dropToast() {
        toastView?.let { runCatching { wm.removeViewImmediate(it) } }
        toastView = null
    }

    fun release() {
        main.removeCallbacks(removeToast)
        dropToast()
        removeNow()
    }

    private fun fullScreenParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        OVERLAY_FLAGS,
        PixelFormat.TRANSLUCENT,
    )

    private fun dp(v: Int) = (v * density).toInt()

    private companion object {
        const val OVERLAY_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
    }
}

/** Círculo de ≈26 dp: relleno blanco translúcido, aro oscuro fino y sombra suave horneada. */
internal class PointerDotView(context: Context) : View(context) {
    private val d = context.resources.displayMetrics.density
    private val radius = 13f * d
    private val pad = 9f * d
    val sizePx: Int = ((radius + pad) * 2).toInt()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(176, 255, 255, 255) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.3f * d
        color = Color.argb(96, 0, 0, 0)
    }
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(10, 0, 0, 0) }
    private val hole = Path()

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        hole.reset()
        hole.addCircle(cx, cy, radius, Path.Direction.CW)
        // Sombra solo por fuera del círculo, para que el relleno translúcido no se ensucie.
        canvas.save()
        canvas.clipOutPath(hole)
        for (i in 1..7) canvas.drawCircle(cx, cy + 2f * d, radius + i * 1.1f * d, shade)
        canvas.restore()
        canvas.drawCircle(cx, cy, radius, fill)
        canvas.drawCircle(cx, cy, radius - ring.strokeWidth / 2f, ring)
    }
}
