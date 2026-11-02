package live.hogar.receiver

import android.util.Log as AndroidLog

/** Registro con etiqueta unica "HogarReceiver"; nunca lanza (en pruebas JVM cae a stdout). */
internal object Log {
    const val TAG = "HogarReceiver"

    fun i(msg: String) = emit(AndroidLog.INFO, msg, null)
    fun w(msg: String, t: Throwable? = null) = emit(AndroidLog.WARN, msg, t)
    fun e(msg: String, t: Throwable? = null) = emit(AndroidLog.ERROR, msg, t)
    fun d(msg: String) = emit(AndroidLog.DEBUG, msg, null)

    private fun emit(level: Int, msg: String, t: Throwable?) {
        try {
            val text = if (t != null) "$msg: ${t.javaClass.simpleName}: ${t.message}\n${AndroidLog.getStackTraceString(t)}" else msg
            AndroidLog.println(level, TAG, text)
        } catch (_: Throwable) {
            println("$TAG: $msg ${t ?: ""}")
        }
    }
}
