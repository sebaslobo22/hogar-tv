package live.hogar.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import live.hogar.design.FocusDriver
import live.hogar.design.FocusStyles
import live.hogar.design.Hogar
import live.hogar.design.HogarIcons
import live.hogar.design.HogarText
import live.hogar.design.SoftShadow
import live.hogar.design.hogarIconFocus
import live.hogar.design.rememberFocusDriver
import kotlin.math.roundToInt

/** Información del foco que HomeContent comparte con la rejilla, el Top Shelf y el fondo. */
@androidx.compose.runtime.Stable
internal class HomeFocus {
    var pkg by mutableStateOf<String?>(null)
    /** Fila de la rejilla con el foco (-1 = ninguna, p. ej. foco en la barra superior). */
    var row by androidx.compose.runtime.mutableIntStateOf(-1)
    /** Última app de la fila 0 que tuvo el foco: la que muestra el Top Shelf. */
    var heroPkg by mutableStateOf<String?>(null)
}

private val LabelStyle = Hogar.Type.Callout.copy(fontSize = 15.sp, textAlign = TextAlign.Center)

/**
 * Cara del icono, en capas: fondo, primer plano y brillo. Cada capa de imagen es su propia
 * capa gráfica (se mueve sin redibujar a las demás); el contenedor las recorta con la esquina
 * continua y se atenúa por alfa cuando no tiene foco (sin rectángulo encima).
 */
@Composable
private fun IconFace(
    layers: LayeredIcon?,
    pal: CardPalette,
    driver: FocusDriver,
    shape: RoundedCornerShape,
    baseAlpha: Float,
) {
    val dim = FocusStyles.Icon.dim
    Box(
        Modifier.fillMaxSize().graphicsLayer {
            clip = true
            this.shape = shape
            alpha = baseAlpha * (1f - dim * (1f - driver.amount.value.coerceIn(0f, 1f)))
        },
    ) {
        // Fondo: imagen 5:3 o degradado de color. Se mueve poco y trae sobreancho para no mostrar bordes.
        val back = layers?.back
        val top = layers?.backTop ?: pal.top
        val bottom = layers?.backBottom ?: pal.bottom
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer {
                    val a = driver.amount.value.coerceIn(0f, 1f)
                    translationX = Parallax.shiftDp(driver.parallaxX(), Parallax.BackFactor) * density * a
                    translationY = Parallax.shiftDp(driver.parallaxY(), Parallax.BackFactor) * density * a
                    scaleX = IconGeometry.BackOverscan
                    scaleY = IconGeometry.BackOverscan
                }
                .drawWithCache {
                    val brush = if (back == null) {
                        Brush.linearGradient(listOf(Color(top), Color(bottom)), Offset.Zero, Offset(size.width, size.height))
                    } else null
                    val dst = IntSize(size.width.roundToInt().coerceAtLeast(1), size.height.roundToInt().coerceAtLeast(1))
                    onDrawBehind {
                        if (back != null) drawImage(back, dstSize = dst, filterQuality = FilterQuality.Medium)
                        else drawRect(brush!!)
                    }
                },
        )
        // Primer plano: el logo (recortado, tamaño tvOS, nunca ampliado) o el glifo vectorial propio.
        val front = layers?.front
        val glyph = layers?.glyph
        val glyphPainter = glyph?.let { rememberVectorPainter(glyphVector(it)) }
        if (front != null || glyphPainter != null) {
            val fw = layers?.frontW ?: 0f
            val fh = layers?.frontH ?: 0f
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer {
                        val a = driver.amount.value.coerceIn(0f, 1f)
                        translationX = Parallax.shiftDp(driver.parallaxX(), Parallax.FrontFactor) * density * a
                        translationY = Parallax.shiftDp(driver.parallaxY(), Parallax.FrontFactor) * density * a
                    }
                    .drawBehind {
                        val w = (size.width * fw).roundToInt().coerceAtLeast(1)
                        val h = (size.height * fh).roundToInt().coerceAtLeast(1)
                        val ox = ((size.width - w) / 2f).roundToInt()
                        val oy = ((size.height - h) / 2f).roundToInt()
                        if (front != null) {
                            drawImage(front, dstOffset = IntOffset(ox, oy), dstSize = IntSize(w, h), filterQuality = FilterQuality.Medium)
                        } else if (glyphPainter != null) {
                            translate(ox.toFloat(), oy.toFloat()) {
                                with(glyphPainter) { draw(Size(w.toFloat(), h.toFloat()), colorFilter = ColorFilter.tint(Color.White)) }
                            }
                        }
                    },
            )
        }
        // Brillo especular que sigue al parallax; solo se dibuja con foco.
        Box(
            Modifier.fillMaxSize().drawWithCache {
                val r = size.width * 0.62f
                val hi = Brush.radialGradient(listOf(Color.White.copy(alpha = 0.30f), Color.Transparent), center = Offset(r, r), radius = r)
                val hiSize = Size(r * 2, r * 2)
                onDrawBehind {
                    val a = driver.amount.value.coerceIn(0f, 1f)
                    if (a > 0.02f) {
                        val cx = size.width * (0.5f + 0.40f * driver.parallaxX().coerceIn(-1f, 1f))
                        val cy = size.height * (0.28f + 0.28f * driver.parallaxY().coerceIn(-1f, 1f))
                        translate(cx - r, cy - r) { drawRect(hi, size = hiSize, alpha = a) }
                    }
                }
            },
        )
    }
}

/**
 * Icono de app tipo tvOS: 5:3, sin texto, en capas con parallax. El nombre aparece debajo solo
 * con foco. OK = abrir; mantener OK = menú. El icono no recompone al enfocar: el foco anima solo
 * capas de dibujo ([hogarIconFocus]).
 */
@Composable
internal fun AppIcon(
    app: AppEntry,
    row: Int,
    hidden: Boolean,
    useBanner: Boolean,
    cellWidthDp: Float,
    shadow: SoftShadow.Baked,
    requester: FocusRequester,
    focus: HomeFocus,
    onLaunch: (AppEntry, Rect) -> Unit,
    onMenu: (AppEntry, Rect) -> Unit,
) {
    val ctx = LocalContext.current.applicationContext
    val pkg = app.packageName
    var palette by remember(pkg) { mutableStateOf(AppArt.peekPalette(pkg)) }
    var layers by remember(pkg, useBanner) { mutableStateOf(AppArt.peekLayers(pkg, useBanner)) }
    LaunchedEffect(pkg, useBanner) {
        if (palette == null) palette = withContext(Dispatchers.IO) { AppArt.palette(ctx, app) }
        if (layers == null) layers = withContext(Dispatchers.IO) { AppArt.layers(ctx, app, cellWidthDp, useBanner) }
    }
    val pal = palette ?: remember(pkg) { BrandPalettes.derive(null, pkg) }

    val driver = rememberFocusDriver(FocusStyles.Icon)
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val longFired = remember { BooleanArray(1) }
    val launch by rememberUpdatedState(onLaunch)
    val menu by rememberUpdatedState(onMenu)
    val shape = remember(cellWidthDp) { RoundedCornerShape(IconGeometry.cornerRadius(cellWidthDp).dp) }
    val iconH = IconGeometry.heightForWidth(cellWidthDp).dp

    fun bounds(): Rect = coords[0]?.takeIf { it.isAttached }?.boundsInRoot() ?: Rect.Zero

    Column(Modifier.width(cellWidthDp.dp).height(iconH + IconGrid.LabelSpaceDp.dp)) {
        Box(
            Modifier.size(cellWidthDp.dp, iconH)
                .focusRequester(requester)
                .hogarIconFocus(driver) { f ->
                    if (f) {
                        focus.pkg = pkg
                        focus.row = row
                        if (row == 0) focus.heroPkg = pkg
                    }
                }
                .onGloballyPositioned { coords[0] = it }
                .onPreviewKeyEvent { e ->
                    when (e.key) {
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            when (e.type) {
                                KeyEventType.KeyDown -> if (e.nativeKeyEvent.repeatCount >= 1 && !longFired[0]) {
                                    longFired[0] = true
                                    menu(app, bounds())
                                }
                                KeyEventType.KeyUp -> if (longFired[0]) longFired[0] = false else launch(app, bounds())
                            }
                            true
                        }
                        else -> false
                    }
                }
                .pointerInput(pkg) {
                    detectTapGestures(onTap = { launch(app, bounds()) }, onLongPress = { menu(app, bounds()) })
                }
                .focusable()
                // Sombra horneada (una imagen borrosa), ampliada con el icono y visible según el foco.
                .drawBehind {
                    val a = driver.amount.value.coerceIn(0f, 1f)
                    if (a > 0.01f) {
                        val pad = shadow.padPx.toFloat()
                        val w = (size.width + 2 * pad).roundToInt()
                        val h = (size.height + 2 * pad).roundToInt()
                        drawImage(
                            shadow.image, dstOffset = IntOffset((-pad).roundToInt(), (-pad + size.height * 0.12f).roundToInt()),
                            dstSize = IntSize(w, h), alpha = a * 0.62f, filterQuality = FilterQuality.Low,
                        )
                    }
                },
        ) {
            IconFace(layers, pal, driver, shape, if (hidden) 0.55f else 1f)
            if (hidden) {
                HogarText(
                    "Oculta", Hogar.Type.Caption,
                    Modifier.align(Alignment.TopEnd).padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(Hogar.Radius.Pill))
                        .padding(horizontal = 8.dp, vertical = 1.dp),
                )
            }
        }
        // Nombre: aparece (fundido y deslizamiento con resorte .smooth) solo con foco.
        HogarText(
            app.label, LabelStyle,
            Modifier.fillMaxWidth().padding(top = 12.dp).graphicsLayer {
                val a = driver.smoothAmount.value.coerceIn(0f, 1f)
                alpha = a
                translationY = (a - 1f) * 6.dp.toPx()
            },
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Vector propio de cada glifo genérico (engranaje, TV, bluetooth, carpeta, nota). */
internal fun glyphVector(k: GlyphKind) = when (k) {
    GlyphKind.Gear -> HogarIcons.Gear
    GlyphKind.Tv -> HogarIcons.Tv
    GlyphKind.Bluetooth -> HogarIcons.Bluetooth
    GlyphKind.Folder -> HogarIcons.Folder
    GlyphKind.Note -> HogarIcons.Music
}
