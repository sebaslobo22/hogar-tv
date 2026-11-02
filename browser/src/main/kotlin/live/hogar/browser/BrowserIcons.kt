package live.hogar.browser

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Iconos del navegador: mismo lenguaje que HogarIcons (rejilla 24, trazo 1,8, puntas redondas), vectores sin mapas de bits. */
internal object BrowserIcons {
    private class P(val d: String, val fill: Boolean = false)

    private fun icon(name: String, vararg paths: P): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (p in paths) {
            b.addPath(
                pathData = PathParser().parsePathString(p.d).toNodes(),
                fill = if (p.fill) SolidColor(Color.Black) else null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    val Back by lazy { icon("back", P("M14.5 5.2 L7.7 12 L14.5 18.8")) }
    val Forward by lazy { icon("forward", P("M9.5 5.2 L16.3 12 L9.5 18.8")) }
    val Reload by lazy { icon("reload", P("M18.5 12 A6.5 6.5 0 1 1 16.6 7.4"), P("M17.2 3.8 V7.4 H13.6")) }
    val Close by lazy { icon("close", P("M6.5 6.5 L17.5 17.5 M17.5 6.5 L6.5 17.5")) }
    private const val STAR = "M12 3.8 L14.5 9.2 L20.4 9.9 L16 13.9 L17.2 19.7 L12 16.8 L6.8 19.7 L8 13.9 L3.6 9.9 L9.5 9.2 Z"
    val Star by lazy { icon("star", P(STAR)) }
    val StarFilled by lazy { icon("star-filled", P(STAR, fill = true)) }
    val Tabs by lazy {
        icon(
            "tabs",
            P("M7.5 8.5 H18.5 A1.5 1.5 0 0 1 20 10 V18 A1.5 1.5 0 0 1 18.5 19.5 H7.5 A1.5 1.5 0 0 1 6 18 V10 A1.5 1.5 0 0 1 7.5 8.5 Z"),
            P("M9.2 5.4 H17.8"),
        )
    }
    val Plus by lazy { icon("plus", P("M12 5 V19 M5 12 H19")) }
    val Minus by lazy { icon("minus", P("M5 12 H19")) }
    val Desktop by lazy { icon("desktop", P("M4.5 5.5 H19.5 V15.5 H4.5 Z"), P("M9 19 H15 M12 15.5 V19")) }
    val Phone by lazy {
        icon(
            "phone",
            P("M8 3.8 H16 A1 1 0 0 1 17 4.8 V19.2 A1 1 0 0 1 16 20.2 H8 A1 1 0 0 1 7 19.2 V4.8 A1 1 0 0 1 8 3.8 Z"),
            P("M11 17.4 H13"),
        )
    }
    val Home by lazy { icon("home", P("M4 11 L12 4.2 L20 11 V19.2 H14.4 V14 H9.6 V19.2 H4 Z")) }
    val Pointer by lazy { icon("pointer", P("M6 4 L18 11.2 L12.2 12.6 L9.6 18.4 Z")) }
    val ScrollMode by lazy { icon("scroll", P("M12 4 V20 M8 8 L12 4 L16 8 M8 16 L12 20 L16 16")) }
    val Lock by lazy { icon("lock", P("M7 11 H17 V19 H7 Z M9 11 V8.5 A3 3 0 0 1 15 8.5 V11")) }
    val Search by lazy { icon("search", P("M4.5 10.5 A6 6 0 1 0 16.5 10.5 A6 6 0 1 0 4.5 10.5 Z"), P("M15 15 L20 20")) }
    val Warning by lazy { icon("warning", P("M12 4.5 L20.5 19 H3.5 Z M12 10 V14 M12 16.5 V16.6")) }
    val Compass by lazy {
        icon(
            "compass",
            P("M3.5 12 A8.5 8.5 0 1 0 20.5 12 A8.5 8.5 0 1 0 3.5 12 Z"),
            P("M15.6 8.4 L13.4 13.4 L8.4 15.6 L10.6 10.6 Z"),
        )
    }
}
