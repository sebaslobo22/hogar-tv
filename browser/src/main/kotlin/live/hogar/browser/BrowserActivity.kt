package live.hogar.browser

import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.ui.platform.ComposeView

/**
 * Navegador de Hogar para TV: WebView del sistema + puntero virtual con el mando. El WebView vive en
 * una View clásica y la interfaz Compose se dibuja encima, transparente.
 */
class BrowserActivity : ComponentActivity() {
    private lateinit var controller: BrowserController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        val web = FrameLayout(this)
        val video = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            visibility = View.GONE
        }
        controller = BrowserController(this, web, video)
        val ui = ComposeView(this).apply { setContent { BrowserRoot(controller) } }
        root.addView(web, FrameLayout.LayoutParams(-1, -1))
        root.addView(video, FrameLayout.LayoutParams(-1, -1))
        root.addView(ui, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = controller.onBack()
        })
        controller.start(intent)
    }

    // Lint marca dispatchKeyEvent de ComponentActivity como API restringida; es el punto estándar para interceptar teclas.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        controller.handleKey(event) || super.dispatchKeyEvent(event)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        controller.handleIntent(intent)
    }

    override fun onPause() {
        controller.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        controller.onResume()
    }

    override fun onDestroy() {
        controller.onDestroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() {
        super.onLowMemory()
        controller.onLowMemory()
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) controller.onLowMemory()
    }

    /** Mantiene la pantalla encendida mientras hay video a pantalla completa. */
    fun setKeepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
