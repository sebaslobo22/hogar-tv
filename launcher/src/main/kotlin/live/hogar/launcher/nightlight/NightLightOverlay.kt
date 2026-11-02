package live.hogar.launcher.nightlight

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.view.View
import android.view.WindowManager

/**
 * Velo de pantalla completa en TYPE_ACCESSIBILITY_OVERLAY: un View liso con un solo color ARGB
 * (nada se dibuja por cuadro; la composición lo resuelve barato). No toma foco ni toques.
 * El fundido anima solo la transparencia de la vista. Sin velo, no queda ninguna ventana.
 */
internal class NightLightOverlay(private val context: Context) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: View? = null
    private var anim: ValueAnimator? = null
    private var color = 0

    val isAttached: Boolean get() = view != null

    /** Pone (o quita) el velo con fundido de hasta [fullFadeMs]; cambiar de color es inmediato. */
    fun setTarget(visible: Boolean, argb: Int, fullFadeMs: Long) {
        if (visible) show(argb, fullFadeMs) else hide(fullFadeMs)
    }

    private fun show(argb: Int, fullFadeMs: Long) {
        var v = view
        if (v == null) {
            v = View(context).apply { alpha = 0f; setBackgroundColor(argb) }
            if (!runCatching { wm.addView(v, params()) }.isSuccess) return
            view = v
            color = argb
        } else if (argb != color) {
            v.setBackgroundColor(argb)
            color = argb
        }
        fadeTo(v, 1f, fullFadeMs, removeAtEnd = false)
    }

    private fun hide(fullFadeMs: Long) {
        val v = view ?: return
        fadeTo(v, 0f, fullFadeMs, removeAtEnd = true)
    }

    private fun fadeTo(v: View, to: Float, fullMs: Long, removeAtEnd: Boolean) {
        val running = anim
        // Ya va hacia ese destino: no reiniciar el fundido (el reloj de 5 min llama seguido).
        if (running != null && running.isRunning && lastTarget == to) return
        running?.cancel()
        lastTarget = to
        if (v.alpha == to) {
            if (removeAtEnd) removeNow()
            return
        }
        val a = ValueAnimator.ofFloat(v.alpha, to).apply {
            duration = FadeMath.durationMs(v.alpha, to, fullMs)
            addUpdateListener { v.alpha = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled && removeAtEnd) removeNow()
                }
            })
        }
        anim = a
        a.start()
    }

    private var lastTarget = -1f

    fun release() {
        anim?.cancel()
        anim = null
        removeNow()
    }

    private fun removeNow() {
        view?.let { runCatching { wm.removeViewImmediate(it) } }
        view = null
        lastTarget = -1f
    }

    private fun params() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }
}
