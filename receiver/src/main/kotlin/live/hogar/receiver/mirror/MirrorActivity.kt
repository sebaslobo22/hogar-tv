package live.hogar.receiver.mirror

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import live.hogar.design.Hogar

/** Pantalla completa negra con una SurfaceView ajustada (aspect-fit). Sirve al espejo y al video enviado. */
class MirrorActivity : Activity() {
    private lateinit var frame: AspectFrame
    private lateinit var surfaceView: SurfaceView
    private lateinit var message: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { ScreenHub.surfaceChanged(holder.surface) }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { ScreenHub.surfaceChanged(holder.surface) }
            override fun surfaceDestroyed(holder: SurfaceHolder) { ScreenHub.surfaceChanged(null) }
        })
        message = TextView(this).apply {
            setTextColor(Hogar.Color.Text.toArgb())
            setBackgroundColor(Hogar.Color.Glass.toArgb())
            setPadding(48, 28, 48, 28)
            textSize = 26f
            typeface = androidx.core.content.res.ResourcesCompat.getFont(context, live.hogar.design.R.font.hogar_text_semibold) ?: Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        frame = AspectFrame(this)
        frame.setBackgroundColor(Color.BLACK)
        frame.addView(surfaceView)
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        root.addView(frame, FrameLayout.LayoutParams(-1, -1))
        root.addView(message, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        setContentView(root)
        hideSystemUi()
        ScreenHub.attach(this)
    }

    private fun hideSystemUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    fun setVideoSize(w: Int, h: Int) { frame.setAspect(w, h) }

    fun showMessage(text: String?) {
        if (text.isNullOrEmpty()) message.visibility = View.GONE
        else { message.text = text; message.visibility = View.VISIBLE }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            ScreenHub.userExit()
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        ScreenHub.detach(this)
        super.onDestroy()
    }

    /** Centra el hijo con la proporcion del video (barras negras en el resto). */
    private class AspectFrame(context: Context) : FrameLayout(context) {
        private var vw = 0
        private var vh = 0
        fun setAspect(w: Int, h: Int) { if (w != vw || h != vh) { vw = w; vh = h; requestLayout() } }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val child = getChildAt(0) ?: return
            val pw = r - l
            val ph = b - t
            var cw = pw
            var ch = ph
            if (vw > 0 && vh > 0) {
                if (pw.toLong() * vh > ph.toLong() * vw) cw = (ph.toLong() * vw / vh).toInt() else ch = (pw.toLong() * vh / vw).toInt()
            }
            val left = (pw - cw) / 2
            val top = (ph - ch) / 2
            child.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY))
            child.layout(left, top, left + cw, top + ch)
        }
    }
}
