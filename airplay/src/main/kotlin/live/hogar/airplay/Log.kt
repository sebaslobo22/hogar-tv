package live.hogar.airplay

/** Registro mínimo: en Android va a Logcat; en pruebas JVM no hace nada (o a `sink`). */
internal object APLog {
    @Volatile var sink: ((String) -> Unit)? = null

    fun d(msg: String) = emit(msg, false)
    fun w(msg: String) = emit(msg, true)

    private fun emit(msg: String, warn: Boolean) {
        sink?.invoke(msg)
        try {
            if (warn) android.util.Log.w("HogarAirPlay", msg) else android.util.Log.d("HogarAirPlay", msg)
        } catch (_: Throwable) {
            // JVM sin Android: se ignora.
        }
    }
}

/** Error de protocolo con mensaje ya listo para mostrar al usuario (en español). */
internal class AirPlayException(val userMessage: String, cause: Throwable? = null) :
    Exception(userMessage, cause)
