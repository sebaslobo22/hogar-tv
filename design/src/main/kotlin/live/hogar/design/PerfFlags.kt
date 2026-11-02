package live.hogar.design

/**
 * Interruptores para medir en el TV el costo de cada efecto, sin recompilar:
 * `adb shell setprop debug.hogar.off glass,shadow,glow` y reiniciar la app. Vacío = todo activo.
 */
object PerfFlags {
    private val off: Set<String> by lazy {
        runCatching {
            val get = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            (get.invoke(null, "debug.hogar.off") as String).split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        }.getOrDefault(emptySet())
    }
    val glass get() = "glass" !in off
    val shadow get() = "shadow" !in off
    val glow get() = "glow" !in off
    val highlight get() = "highlight" !in off
}
