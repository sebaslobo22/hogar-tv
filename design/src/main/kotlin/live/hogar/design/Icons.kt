package live.hogar.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Banco de iconos propio de Hogar: vectores dibujados a mano en una rejilla de 24×24 con
 * trazo de 1,8 (puntas y uniones redondas), en el estilo de SF Symbols. Sin mapas de
 * bits. Se tiñen al dibujar con [HogarIcon].
 */
object HogarIcons {
    private class P(val d: String, val fill: Boolean = false)

    /** Peso "regular" de SF Symbols sobre rejilla de 24: trazo 1,8, puntas y uniones redondas. */
    private const val STROKE = 1.8f

    private fun icon(name: String, vararg paths: P): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (p in paths) {
            b.addPath(
                pathData = PathParser().parsePathString(p.d).toNodes(),
                fill = if (p.fill) SolidColor(Color.Black) else null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    /** Altavoz: caja + cono, geométrico y con esquinas redondeadas por la unión. */
    private const val SPEAKER = "M3.8 9.7 H7.2 L11.8 5.6 V18.4 L7.2 14.3 H3.8 Z"
    private const val EYE = "M2.4 12 C4.7 7.8 8 5.8 12 5.8 C16 5.8 19.3 7.8 21.6 12 C19.3 16.2 16 18.2 12 18.2 C8 18.2 4.7 16.2 2.4 12 Z"
    private const val PUPIL = "M9.2 12 A2.8 2.8 0 1 0 14.8 12 A2.8 2.8 0 1 0 9.2 12 Z"

    /** Engranaje de 8 dientes trapezoidales (radio 7,2 a 9,4) más el círculo central. */
    private fun gearPath(): String {
        val sb = StringBuilder()
        val teeth = 8
        val step = 2 * PI / teeth
        for (i in 0 until teeth) {
            val a = i * step
            val pts = listOf(
                a - step * 0.36 to 7.2, a - step * 0.20 to 9.4, a + step * 0.20 to 9.4, a + step * 0.36 to 7.2,
            )
            for ((j, pt) in pts.withIndex()) {
                val x = 12.0 + pt.second * cos(pt.first)
                val y = 12.0 + pt.second * sin(pt.first)
                sb.append(if (i == 0 && j == 0) "M" else "L").append(String.format(java.util.Locale.US, "%.2f %.2f ", x, y))
            }
        }
        return sb.append("Z").toString()
    }

    val Gear by lazy { icon("gear", P(gearPath()), P("M9.2 12 A2.8 2.8 0 1 0 14.8 12 A2.8 2.8 0 1 0 9.2 12 Z")) }
    val Audio by lazy {
        icon("audio", P(SPEAKER), P("M15.5 9.1 A4.2 4.2 0 0 1 15.5 14.9"), P("M18.2 6.4 A8 8 0 0 1 18.2 17.6"))
    }
    val Volume by lazy { icon("volume", P(SPEAKER), P("M15.5 9.1 A4.2 4.2 0 0 1 15.5 14.9")) }
    val AirPlay by lazy {
        icon(
            "airplay",
            P("M7 17 H5.5 A2.5 2.5 0 0 1 3 14.5 V6.5 A2.5 2.5 0 0 1 5.5 4 H18.5 A2.5 2.5 0 0 1 21 6.5 V14.5 A2.5 2.5 0 0 1 18.5 17 H17"),
            P("M12 14.6 L17 20.4 H7 Z", fill = true),
        )
    }
    val Tv by lazy {
        icon(
            "tv",
            P("M5.5 4.5 H18.5 A2.5 2.5 0 0 1 21 7 V15 A2.5 2.5 0 0 1 18.5 17.5 H5.5 A2.5 2.5 0 0 1 3 15 V7 A2.5 2.5 0 0 1 5.5 4.5 Z"),
            P("M8.5 20.4 H15.5"),
        )
    }
    val Wifi by lazy {
        icon(
            "wifi",
            P("M9.2 16.4 A4 4 0 0 1 14.8 16.4"), P("M6.3 13.5 A8 8 0 0 1 17.7 13.5"),
            P("M3.5 10.7 A12 12 0 0 1 20.5 10.7"), P("M12 19.4 H12.01"),
        )
    }
    val Hdmi by lazy {
        icon("hdmi", P("M4 7.2 H20 V13.4 L17.2 16.8 H6.8 L4 13.4 Z"), P("M9 10.6 V12.8 M12 10.6 V12.8 M15 10.6 V12.8"), P("M9 20 H15"))
    }
    val Play by lazy { icon("play", P("M8.2 5.8 L18.4 12 L8.2 18.2 Z", fill = true)) }
    val Pause by lazy { icon("pause", P("M8.3 6.6 H9.7 V17.4 H8.3 Z", fill = true), P("M14.3 6.6 H15.7 V17.4 H14.3 Z", fill = true)) }
    val Forward by lazy { icon("forward", P("M5.6 5.8 L15.6 12 L5.6 18.2 Z", fill = true), P("M19 5.8 V18.2")) }
    val Back by lazy { icon("back", P("M18.4 5.8 L8.4 12 L18.4 18.2 Z", fill = true), P("M5 5.8 V18.2")) }
    val Check by lazy { icon("check", P("M5 12.8 L9.8 17.4 L19 6.8")) }
    val Plus by lazy { icon("plus", P("M12 5 V19 M5 12 H19")) }
    val Eye by lazy { icon("eye", P(EYE), P(PUPIL)) }
    val EyeSlash by lazy { icon("eye.slash", P(EYE), P(PUPIL), P("M4.5 4.5 L19.5 19.5")) }
    val Pin by lazy { icon("pin", P("M9 4.4 H15 L14.2 10.2 L17.2 13.6 H6.8 L9.8 10.2 Z"), P("M12 14 V20")) }
    val ArrowLeft by lazy { icon("arrow.left", P("M19 12 H5 M11 6 L5 12 L11 18")) }
    val ArrowRight by lazy { icon("arrow.right", P("M5 12 H19 M13 6 L19 12 L13 18")) }
    val Power by lazy { icon("power", P("M12 3.6 V11.4"), P("M6.8 6.9 A8 8 0 1 0 17.2 6.9")) }
    val Search by lazy { icon("search", P("M4 10.5 A6.5 6.5 0 1 0 17 10.5 A6.5 6.5 0 1 0 4 10.5 Z"), P("M15.4 15.4 L20 20")) }
    val Folder by lazy {
        icon("folder", P("M3 8.2 A2.2 2.2 0 0 1 5.2 6 H9.2 L11.2 8.4 H18.8 A2.2 2.2 0 0 1 21 10.6 V16.8 A2.2 2.2 0 0 1 18.8 19 H5.2 A2.2 2.2 0 0 1 3 16.8 Z"))
    }
    val Music by lazy {
        icon("music", P("M9 17.4 V5.8 L19 3.8 V15.4"), P("M3.6 17.4 A2.7 2.7 0 1 0 9 17.4 A2.7 2.7 0 1 0 3.6 17.4 Z"), P("M13.6 15.4 A2.7 2.7 0 1 0 19 15.4 A2.7 2.7 0 1 0 13.6 15.4 Z"))
    }
    val Stop by lazy { icon("stop", P("M7.4 7.4 H16.6 V16.6 H7.4 Z", fill = true)) }
    val Info by lazy { icon("info", P("M3 12 A9 9 0 1 0 21 12 A9 9 0 1 0 3 12 Z"), P("M12 11 V16.4"), P("M12 7.7 H12.01")) }
    /** Runa de Bluetooth genérica (trazo continuo). */
    val Bluetooth by lazy { icon("bluetooth", P("M6.6 7.8 L17 16.4 L12 20.6 V3.4 L17 7.6 L6.6 16.2")) }
    val Headphones by lazy {
        icon(
            "headphones",
            P("M4 15 V12 A8 8 0 0 1 20 12 V15"),
            P("M4 14.6 H7 V19.4 H5.6 A1.6 1.6 0 0 1 4 17.8 Z"),
            P("M20 14.6 H17 V19.4 H18.4 A1.6 1.6 0 0 0 20 17.8 Z"),
        )
    }
    val Chevron by lazy { icon("chevron", P("M9.5 5.2 L16.3 12 L9.5 18.8")) }
}

/**
 * Dibuja [icon] teñido con el color que devuelva [tint]. El color se lee al DIBUJAR, así un
 * icono puede cambiar con el foco sin recomponer.
 */
@Composable
fun HogarIcon(icon: ImageVector, tint: ColorLambda, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    val painter: Painter = rememberVectorPainter(icon)
    Box(
        modifier.size(size).drawBehind {
            with(painter) { draw(this@drawBehind.size, colorFilter = ColorFilter.tint(tint())) }
        },
    )
}

@Composable
fun HogarIcon(icon: ImageVector, tint: Color = Hogar.Color.Text, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    val t = remember(tint) { { tint } }
    HogarIcon(icon, t, modifier, size)
}
