package live.hogar.design

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape

/**
 * Texto de Hogar. [color] se evalúa al dibujar (cambia con el foco sin recomponer); si es
 * null se usa el color del [style].
 */
@Composable
fun HogarText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: ColorLambda? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style,
        overflow = overflow,
        maxLines = maxLines,
        color = color?.let { c -> ColorProducer { c() } },
    )
}

/**
 * Superficie enfocable base: vidrio en reposo que se vuelve blanco al enfocar, con el
 * [FocusStyle] dado. El contenido recibe [FocusColors] para teñirse según el foco.
 * Con [onClick] = null solo es enfocable (filas informativas).
 */
@Composable
fun FocusSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Hogar.Radius.Pill),
    style: FocusStyle = FocusStyles.Pill,
    glass: Boolean = true,
    selected: Boolean = false,
    enabled: Boolean = true,
    onFocusChanged: (Boolean) -> Unit = {},
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.(FocusColors) -> Unit,
) {
    val driver = rememberFocusDriver(style)
    val colors = remember(driver) { FocusColors(driver) }
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .hogarFocus(driver, shape, onFocusChanged)
            .then(if (glass) Modifier.liquidGlass(shape, clip = false) else Modifier)
            .drawBehind {
                val a = driver.amount.value.coerceIn(0f, 1f)
                if (selected) drawRect(Color.White.copy(alpha = 0.16f * (1f - a)))
                if (a > 0.003f) drawRect(Hogar.Color.GlassFocused.copy(alpha = 0.95f * a))
            }
            .then(
                if (onClick != null) Modifier.clickable(interaction, indication = null, enabled = enabled, onClick = onClick)
                else Modifier.focusable(enabled),
            ),
        content = { content(colors) },
    )
}

/** Botón de vidrio con icono opcional (píldora de tvOS). */
@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    shape: Shape = RoundedCornerShape(Hogar.Radius.Pill),
    style: FocusStyle = FocusStyles.Pill,
    selected: Boolean = false,
    enabled: Boolean = true,
    trailing: ImageVector? = null,
    onFocusChanged: (Boolean) -> Unit = {},
) {
    FocusSurface(
        modifier = modifier,
        shape = shape, style = style, selected = selected, enabled = enabled,
        onFocusChanged = onFocusChanged, onClick = onClick,
    ) { c ->
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) HogarIcon(icon, { c.primary() }, size = 20.dp)
            HogarText(text, Hogar.Type.Label.copy(fontSize = 16.sp), color = { c.primary() }, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (trailing != null) HogarIcon(trailing, { c.primary() }, size = 18.dp)
        }
    }
}

/** Pestaña de la barra superior (tvOS): solo texto; al enfocar se vuelve una cápsula blanca con texto oscuro. */
@Composable
fun TabButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocusChanged: (Boolean) -> Unit = {},
) {
    FocusSurface(
        modifier = modifier,
        style = FocusStyles.Tab, glass = false,
        onFocusChanged = onFocusChanged, onClick = onClick,
    ) { c ->
        HogarText(
            text, Hogar.Type.Callout.copy(fontSize = 17.sp),
            Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            color = { c.tab() }, maxLines = 1,
        )
    }
}

/** Botón redondo solo con icono (reproductor). */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    iconSize: Dp = 26.dp,
    selected: Boolean = false,
    onFocusChanged: (Boolean) -> Unit = {},
) {
    FocusSurface(
        modifier.size(size),
        shape = CircleShape,
        style = FocusStyles.Round, selected = selected, onFocusChanged = onFocusChanged, onClick = onClick,
    ) { c ->
        Box(Modifier.align(Alignment.Center)) { HogarIcon(icon, { c.primary() }, size = iconSize) }
    }
}

/** Encabezado de pantalla secundaria: icono en vidrio + título grande. */
@Composable
fun ScreenTitle(title: String, icon: ImageVector, modifier: Modifier = Modifier, subtitle: String? = null) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        GlassSurface(Modifier.size(56.dp), shape = RoundedCornerShape(Hogar.Radius.Icon)) {
            Box(Modifier.align(Alignment.Center)) { HogarIcon(icon, Hogar.Color.Text, size = 28.dp) }
        }
        Column {
            HogarText(title, Hogar.Type.Title)
            if (subtitle != null) HogarText(subtitle, Hogar.Type.Caption)
        }
    }
}
