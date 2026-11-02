package live.hogar.receiver.mirror

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.Surface
import live.hogar.receiver.Log

/** Quien pinta en la pantalla completa: el decodificador de espejo o el reproductor de video. */
internal interface SurfaceConsumer {
    fun onSurface(surface: Surface?)
}

/**
 * Puente entre los hilos de red y la Activity de pantalla completa. Mantiene la superficie actual,
 * arranca/cierra la Activity y le pasa el tamano del video para el ajuste con barras negras.
 */
internal object ScreenHub {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var activity: MirrorActivity? = null
    @Volatile private var surface: Surface? = null
    @Volatile private var consumer: SurfaceConsumer? = null
    @Volatile private var width = 0
    @Volatile private var height = 0
    @Volatile private var pendingMessage: String? = null
    @Volatile var onUserExit: (() -> Unit)? = null

    fun setConsumer(c: SurfaceConsumer?) {
        consumer = c
        val s = surface
        if (c != null && s != null && s.isValid) c.onSurface(s)
    }

    /** Abre la pantalla completa si no esta abierta. Se puede llamar desde cualquier hilo. */
    fun show(context: Context) {
        if (activity != null) return
        main.post {
            try {
                val i = Intent(context, MirrorActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                context.startActivity(i)
            } catch (t: Throwable) {
                Log.e("no se pudo abrir la pantalla completa (falta permiso de 'mostrar sobre otras apps'?)", t)
            }
        }
    }

    fun finishScreen() {
        main.post { activity?.finish() }
    }

    fun setVideoSize(w: Int, h: Int) {
        width = w; height = h
        main.post { activity?.setVideoSize(w, h) }
    }

    /** Mensaje corto sobre la pantalla (p. ej. contenido protegido). */
    fun showMessage(text: String?) {
        pendingMessage = text
        main.post { activity?.showMessage(text) }
    }

    // ---- llamadas de la Activity ----
    fun attach(a: MirrorActivity) {
        activity = a
        if (width > 0) a.setVideoSize(width, height)
        pendingMessage?.let { a.showMessage(it) }
    }

    fun detach(a: MirrorActivity) {
        if (activity === a) activity = null
    }

    fun surfaceChanged(s: Surface?) {
        surface = s
        consumer?.onSurface(s)
    }

    fun userExit() {
        try { onUserExit?.invoke() } catch (t: Throwable) { Log.w("onUserExit", t) }
    }
}
