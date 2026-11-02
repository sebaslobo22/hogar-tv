package live.hogar.nowplaying.light

import kotlin.math.floor

/** Cómo se manda la paleta a la lámpara. Se persiste por dispositivo; el valor por defecto es [GRADIENT] ("Degradado"). */
enum class LightMethod(val label: String) {
    /** (a) razer/DreamView con el indicador de degradado: la lámpara interpola los colores. */
    GRADIENT("Degradado"),
    /** (a') razer con tramos planos. */
    RAZER("Tramos razer"),
    /** (b) ptReal: paquetes BLE por segmento. */
    PTREAL("Tramos BLE"),
    /** (c) colorwc: un color cada vez, ciclando. */
    COLORS("Colores");

    fun next() = values()[(ordinal + 1) % values().size]
    val segmented get() = this != COLORS

    companion object {
        fun parse(s: String?) = values().firstOrNull { it.name == s } ?: GRADIENT
    }
}

/** Reparto de la paleta por los tramos de la lámpara y su rotación. Puro (JVM). */
object SegmentMath {
    /** Tramos que se mandan por razer (el degradado de hardware no depende de este número). */
    const val RAZER_SEGMENTS = 12

    /** Color en la posición [pos] de la paleta tratada como anillo (0 → colors[0], 1 → colors[1], … n → colors[0]). */
    fun sampleCyclic(colors: List<LampColor>, pos: Float): LampColor {
        val n = colors.size
        if (n == 1) return colors[0]
        val base = floor(pos)
        val i = Math.floorMod(base.toInt(), n)
        return Fade.lerp(colors[i], colors[(i + 1) % n], pos - base)
    }

    /**
     * Color RGB24 de cada uno de [n] tramos: la paleta se reparte uniformemente a lo largo de la lámpara
     * (de abajo arriba) y [shift] (en unidades de color) la desplaza en anillo. El brillo propio de cada
     * color ([LampColor.level]) y [gain] 0..1 se hornean en el RGB.
     */
    fun gradient(colors: List<LampColor>, n: Int, shift: Float, gain: Float = 1f): IntArray {
        require(n >= 1)
        val span = colors.size.toFloat()
        return IntArray(n) { i ->
            val c = sampleCyclic(colors, shift + if (n == 1) 0f else span * i / n)
            val k = (c.level * gain).coerceIn(0f, 1f)
            ((c.r * k).toInt().coerceIn(0, 255) shl 16) or ((c.g * k).toInt().coerceIn(0, 255) shl 8) or (c.b * k).toInt().coerceIn(0, 255)
        }
    }

    /** Arcoíris fijo de la "Prueba de tramos": rojo, naranja, amarillo, verde, cian, azul, violeta. */
    val RAINBOW = intArrayOf(0xFF0000, 0xFF7F00, 0xFFFF00, 0x00FF00, 0x00FFFF, 0x0000FF, 0xAA00FF)

    fun rainbow(n: Int): IntArray = IntArray(n) { i -> RAINBOW[(i * RAINBOW.size / n).coerceIn(0, RAINBOW.size - 1)] }

    /** Paquetes razer / ptReal de un cuadro ya repartido. Vacío para [LightMethod.COLORS]. */
    fun frame(method: LightMethod, rgb: IntArray): List<String> = when (method) {
        LightMethod.GRADIENT -> listOf(GoveeProtocol.razer(Razer.segments(rgb, gradient = true)))
        LightMethod.RAZER -> listOf(GoveeProtocol.razer(Razer.segments(rgb, gradient = false)))
        LightMethod.PTREAL -> listOf(GoveeProtocol.ptReal(PtReal.packets(rgb)))
        LightMethod.COLORS -> emptyList()
    }

    fun segmentCount(method: LightMethod) = if (method == LightMethod.PTREAL) PtReal.SEGMENTS else RAZER_SEGMENTS
}
