package live.hogar.design

import androidx.compose.ui.text.font.Font

import android.os.Build
import androidx.compose.animation.core.SpringSpec
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color as ComposeColor

/**
 * Sistema de diseño de Hogar, calcado del lenguaje de tvOS 26 (Liquid Glass): vidrio
 * translúcido sobre un fondo que brilla con el color de la app enfocada, enfoque que levanta
 * con parallax, y resortes de SwiftUI. Todas las pantallas usan estos tokens; nadie define
 * colores, tamaños ni animaciones por su cuenta.
 *
 * Lienzo de referencia: 1920×1080 px a densidad 320 = 960×540 dp. Margen seguro de TV
 * (overscan) = [Space.SafeH] / [Space.SafeV].
 */
object Hogar {

    /** Colores. Texto secundario = blanco translúcido, como tvOS. */
    object Color {
        val Background = ComposeColor(0xFF08080C)
        val Text = ComposeColor(0xFFF5F5F7)
        val TextSecondary = ComposeColor(0xB3EBEBF5)
        val TextTertiary = ComposeColor(0x66EBEBF5)
        /** Relleno de vidrio en reposo (blanco translúcido sobre el fondo). */
        val Glass = ComposeColor(0x29FFFFFF)
        /** Relleno de un elemento enfocado: casi blanco opaco. */
        val GlassFocused = ComposeColor(0xF2FFFFFF)
        val GlassStroke = ComposeColor(0x33FFFFFF)
        val TextOnFocused = ComposeColor(0xFF1D1D1F)
        val Accent = ComposeColor(0xFF0A84FF)
        val Positive = ComposeColor(0xFF30D158)
        val Warning = ComposeColor(0xFFFFD60A)
        val Negative = ComposeColor(0xFFFF453A)
        /** Brillo de fondo cuando ninguna app está enfocada. */
        val GlowDefault = ComposeColor(0xFF3B4C9B)
        /** Sin fondo de pantalla real: vidrio opaco de reserva. */
        val GlassOpaque = ComposeColor(0xFF1B1B22)
    }

    /** Radios de esquina (tvOS usa curvas continuas y muy generosas). */
    object Radius {
        val Icon = 22.dp
        val Card = 22.dp
        val Row = 18.dp
        val Tray = 30.dp
        val Panel = 32.dp
        val Pill = 999.dp
    }

    object Space {
        val Xxs = 4.dp
        val Xs = 8.dp
        val S = 14.dp
        val M = 22.dp
        val L = 36.dp
        /** Margen seguro de TV: 5 % de 1920×1080 = 48 / 27 dp. */
        val SafeH = 48.dp
        val SafeV = 27.dp
    }

    /** Alturas de sombra (levantamiento al enfocar). */
    object Elevation {
        val Rest = 0.dp
        val Pill = 10.dp
        val Row = 8.dp
        val Card = 26.dp
        val Panel = 14.dp
    }

    /** Tipografía: sans del sistema, títulos grandes y en negrita, secundario translúcido. */
    /**
     * Jerarquía tipográfica de Apple: familia "Display" (tamaño óptico para 20 sp o más) y "Text"
     * (cuerpo). San Francisco en la copia local, Inter en la pública (mismas proporciones). Tracking
     * como la tabla de Apple: negativo en el cuerpo, casi neutro en títulos medianos y más cerrado en
     * los muy grandes.
     */
    object Type {
        val LargeTitle = TextStyle(fontSize = 48.sp, fontFamily = HogarFonts.Display, fontWeight = FontWeight.Bold, color = Color.Text, letterSpacing = (-0.6).sp)
        val Clock = TextStyle(fontSize = 64.sp, fontFamily = HogarFonts.Display, fontWeight = FontWeight.Light, color = Color.Text, letterSpacing = (-1.2).sp)
        val Title = TextStyle(fontSize = 30.sp, fontFamily = HogarFonts.Display, fontWeight = FontWeight.Bold, color = Color.Text, letterSpacing = (-0.2).sp)
        val Headline = TextStyle(fontSize = 22.sp, fontFamily = HogarFonts.Display, fontWeight = FontWeight.SemiBold, color = Color.Text, letterSpacing = 0.sp)
        val Greeting = TextStyle(fontSize = 24.sp, fontFamily = HogarFonts.Display, fontWeight = FontWeight.Medium, color = Color.TextSecondary, letterSpacing = 0.sp)
        val Body = TextStyle(fontSize = 18.sp, fontFamily = HogarFonts.Text, fontWeight = FontWeight.Normal, color = Color.Text, letterSpacing = (-0.35).sp)
        val Callout = TextStyle(fontSize = 16.sp, fontFamily = HogarFonts.Text, fontWeight = FontWeight.Medium, color = Color.Text, letterSpacing = (-0.25).sp)
        val Caption = TextStyle(fontSize = 14.sp, fontFamily = HogarFonts.Text, fontWeight = FontWeight.Medium, color = Color.TextSecondary, letterSpacing = (-0.1).sp)
        val Label = TextStyle(fontSize = 15.sp, fontFamily = HogarFonts.Text, fontWeight = FontWeight.SemiBold, color = Color.Text, letterSpacing = (-0.15).sp)
        val Mono = TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = Color.TextSecondary)
    }

    /**
     * Resortes de SwiftUI (response = "duration" de Apple; amortiguación = 1 - bounce):
     *  - `.smooth`  response 0,50, damping 1,00  (sin rebote; fondos, pantallas, scroll)
     *  - `.snappy`  response 0,50, damping 0,85  (bounce 0,15; foco, zoom de lanzamiento)
     *  - `.bouncy`  response 0,50, damping 0,70  (bounce 0,30; menús que aparecen)
     *  - `.spring`  response 0,55, damping 0,825 (resorte por defecto de SwiftUI)
     * Conversión a Compose en [SpringMath]; aquí no hay otra constante de animación.
     */
    object Motion {
        val Smooth = HogarSpring(0.5f, 1f)
        val Snappy = HogarSpring(0.5f, 0.85f)
        val Bouncy = HogarSpring(0.5f, 0.70f)
        val Default = HogarSpring(0.55f, 0.825f)
        /** Reacción inmediata al pulsar una flecha (inclinación hacia donde se va). */
        val Quick = HogarSpring(0.22f, 0.80f)

        fun <T> smooth(): SpringSpec<T> = Smooth.spec()
        fun <T> snappy(): SpringSpec<T> = Snappy.spec()
        fun <T> bouncy(): SpringSpec<T> = Bouncy.spec()
        fun <T> spring(): SpringSpec<T> = Default.spec()
        fun <T> quick(): SpringSpec<T> = Quick.spec()

        /** Compatibilidad con código previo. */
        fun <T> focus(): SpringSpec<T> = snappy()
        fun <T> page(): SpringSpec<T> = smooth()
        const val FocusScale = 1.10f
    }

    /** Blur real solo desde Android 12; Hogar no lo usa (vidrio con fondo pre-desenfocado). */
    val supportsBlur: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
}

/** Utilidad de lectura diferida de color (se evalúa en la fase de dibujo, sin recomponer). */
typealias ColorLambda = () -> ComposeColor

internal fun ColorLambda.asProducer(): ColorProducer = ColorProducer { this() }

/** Familias tipográficas de Hogar (archivos en res/font; ver design/build.gradle.kts). */
object HogarFonts {
    val Text = FontFamily(
        Font(R.font.hogar_text_regular, FontWeight.Normal),
        Font(R.font.hogar_text_medium, FontWeight.Medium),
        Font(R.font.hogar_text_semibold, FontWeight.SemiBold),
        Font(R.font.hogar_text_bold, FontWeight.Bold),
    )
    val Display = FontFamily(
        Font(R.font.hogar_display_light, FontWeight.Light),
        Font(R.font.hogar_display_regular, FontWeight.Normal),
        Font(R.font.hogar_display_medium, FontWeight.Medium),
        Font(R.font.hogar_display_semibold, FontWeight.SemiBold),
        Font(R.font.hogar_display_bold, FontWeight.Bold),
    )
}
