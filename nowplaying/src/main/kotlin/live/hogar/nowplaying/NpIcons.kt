package live.hogar.nowplaying

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Glifos del visor "Reproduciendo ahora" que el banco de iconos de Hogar no tiene. Mismo
 * lenguaje que `HogarIcons`: rejilla de 24×24, trazo de 1,8 con puntas y uniones redondas
 * (estilo SF Symbols). Privados de este módulo.
 */
internal object NpIcons {
    private class P(val d: String, val fill: Boolean = false, val width: Float = STROKE)

    private const val STROKE = 1.8f

    private fun icon(name: String, vararg paths: P): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (p in paths) {
            b.addPath(
                pathData = PathParser().parsePathString(p.d).toNodes(),
                fill = if (p.fill) SolidColor(Color.Black) else null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = p.width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    /** quote.bubble: globo con comillas ("letra"). */
    val Lyrics by lazy {
        icon(
            "quote.bubble",
            P("M6.2 4.6 H17.8 A2.7 2.7 0 0 1 20.5 7.3 V13.7 A2.7 2.7 0 0 1 17.8 16.4 H11.4 L7.2 20 V16.4 H6.2 A2.7 2.7 0 0 1 3.5 13.7 V7.3 A2.7 2.7 0 0 1 6.2 4.6 Z"),
            P("M8.1 9.2 H10 V11.1 C10 11.9 9.6 12.4 8.9 12.6", width = 1.6f),
            P("M12.6 9.2 H14.5 V11.1 C14.5 11.9 14.1 12.4 13.4 12.6", width = 1.6f),
        )
    }

    /** list.bullet: tres filas con viñeta ("cola"). */
    val Queue by lazy {
        icon(
            "list.bullet",
            P("M3.6 7 A1.3 1.3 0 1 0 6.2 7 A1.3 1.3 0 1 0 3.6 7 Z", fill = true, width = 0.6f), P("M9.4 7 H20.4"),
            P("M3.6 12 A1.3 1.3 0 1 0 6.2 12 A1.3 1.3 0 1 0 3.6 12 Z", fill = true, width = 0.6f), P("M9.4 12 H20.4"),
            P("M3.6 17 A1.3 1.3 0 1 0 6.2 17 A1.3 1.3 0 1 0 3.6 17 Z", fill = true, width = 0.6f), P("M9.4 17 H20.4"),
        )
    }

    /** airplayaudio: dos arcos concéntricos y el triángulo. */
    val AirPlayAudio by lazy {
        icon(
            "airplayaudio",
            P("M5.7 16.3 A8.2 8.2 0 1 1 18.3 16.3"),
            P("M8.5 14 A4.6 4.6 0 1 1 15.5 14"),
            P("M12 15.4 L16.6 20.6 H7.4 Z", fill = true),
        )
    }

    /** lamp.floor: pantalla trapezoidal, poste y base ("iluminación"). */
    val Lamp by lazy {
        icon(
            "lamp.floor",
            P("M8.2 3.4 H15.8 L18 10.2 H6 Z"),
            P("M12 10.2 V20"),
            P("M8.4 20.6 H15.6"),
        )
    }

    /** shuffle: dos flechas que se cruzan. */
    val Shuffle by lazy {
        icon(
            "shuffle",
            P("M3.5 7 H6.4 C11.2 7 12.4 17 17.4 17 H20"), P("M17.6 14.6 L20.2 17 L17.6 19.4"),
            P("M3.5 17 H6.4 C7.8 17 8.8 16.3 9.7 15.2"), P("M14.2 8.8 C15 7.8 16 7 17.4 7 H20"), P("M17.6 4.6 L20.2 7 L17.6 9.4"),
        )
    }

    private val REPEAT = arrayOf(
        P("M4 12 V11 A4.5 4.5 0 0 1 8.5 6.5 H19.6"), P("M17 4 L19.7 6.5 L17 9"),
        P("M20 12 V13 A4.5 4.5 0 0 1 15.5 17.5 H4.4"), P("M7 15 L4.3 17.5 L7 20"),
    )

    /** repeat: bucle. */
    val Repeat by lazy { icon("repeat", *REPEAT) }

    /** repeat.1: bucle con un 1. */
    val RepeatOne by lazy { icon("repeat.1", *REPEAT, P("M10.9 10.6 L12.4 9.5 V14.5", width = 1.6f)) }

    /** Forma de onda de la insignia Lossless (barras redondeadas de altura variable). */
    val Waveform by lazy {
        icon(
            "waveform",
            P("M3.2 10 V14", width = 2.2f), P("M6.9 7 V17", width = 2.2f), P("M10.6 4 V20", width = 2.2f),
            P("M14.3 7 V17", width = 2.2f), P("M18 9.4 V14.6", width = 2.2f), P("M21.4 11.2 V12.8", width = 2.2f),
        )
    }
}
