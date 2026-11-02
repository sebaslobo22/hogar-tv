package live.hogar.browser

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.withFrameNanos
import live.hogar.design.FocusStyle
import live.hogar.design.FocusSurface
import live.hogar.design.GlassButton
import live.hogar.design.GlassSurface
import live.hogar.design.Hogar
import live.hogar.design.HogarIcon
import live.hogar.design.HogarText

/** Tarjeta de la página de inicio y de la rejilla de pestañas: crece un poco y se levanta; sin flote continuo. */
private val CardStyle = FocusStyle(scale = 1.07f, lift = Hogar.Elevation.Card, rim = true)

/** Interfaz (Compose) que se dibuja encima del WebView, transparente donde no hay nada. */
@Composable
fun BrowserRoot(c: BrowserController) {
    Box(Modifier.fillMaxSize()) {
        if (!c.fullscreen) {
            val t = c.active
            val err = t.error
            when {
                t.isStart -> StartPage(c)
                err != null -> ErrorPanel(c, err)
                else -> {
                    if (t.loading && c.overlay != Overlay.Toolbar) LoadBar(t)
                    if (c.overlay == Overlay.Toolbar) {
                        Box(
                            Modifier.fillMaxWidth().height(200.dp)
                                .background(Brush.verticalGradient(listOf(Color(0xAA000000), Color.Transparent))),
                        )
                        Toolbar(c, Modifier.padding(horizontal = Hogar.Space.SafeH, vertical = Hogar.Space.SafeV))
                    }
                }
            }
            if (c.overlay == Overlay.Tabs) TabsPanel(c)
            if (c.overlay == Overlay.Context) ContextMenu(c)
            PointerLayer(c)
        }
    }
}

// --- utilidades ------------------------------------------------------------------------------

/** Pasa el foco de Android a la interfaz y lo pone en [fr] (cuando aparece o cuando cambia [key]). */
@Composable
private fun RequestFocusOnShow(fr: FocusRequester, key: Any? = Unit, enabled: Boolean = true) {
    val view = LocalView.current
    LaunchedEffect(key) {
        if (!enabled) return@LaunchedEffect
        view.requestFocus()
        withFrameNanos { }
        runCatching { fr.requestFocus() }
    }
}

@Composable
private fun LoadBar(t: BrowserTab) {
    Box(
        Modifier.fillMaxWidth().height(4.dp).drawBehind {
            drawRect(Hogar.Color.Accent, size = Size(size.width * (t.progress / 100f), size.height))
        },
    )
}

// --- puntero ---------------------------------------------------------------------------------

/** Puntero vectorial con sombra horneada; se mueve con graphicsLayer (leer posición no recompone). */
@Composable
private fun PointerLayer(c: BrowserController) {
    Box(
        Modifier
            .size(44.dp)
            .graphicsLayer {
                val pad = 4.dp.toPx()
                translationX = c.px.floatValue - pad
                translationY = c.py.floatValue - pad
                alpha = if (c.pointerVisible) 1f else 0f
            }
            .drawWithCache {
                val d = 1.dp.toPx()
                val tip = 4 * d
                val arrow = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(0f, 19f * d)
                    lineTo(4.6f * d, 14.8f * d)
                    lineTo(7.6f * d, 22f * d)
                    lineTo(10.4f * d, 20.8f * d)
                    lineTo(7.4f * d, 13.8f * d)
                    lineTo(13.5f * d, 13.5f * d)
                    close()
                }
                onDrawBehind {
                    translate(tip + 1.5f * d, tip + 3f * d) { drawPath(arrow, Color.Black.copy(alpha = 0.20f)) }
                    translate(tip + 1f * d, tip + 2f * d) { drawPath(arrow, Color.Black.copy(alpha = 0.32f)) }
                    translate(tip, tip) {
                        drawPath(arrow, Color.White)
                        drawPath(arrow, Color(0xD9000000), style = Stroke(1.6f * d))
                    }
                }
            },
    )
}

// --- barra -----------------------------------------------------------------------------------

@Composable
private fun ToolButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    size: androidx.compose.ui.unit.Dp = 52.dp,
    iconSize: androidx.compose.ui.unit.Dp = 26.dp,
) {
    FocusSurface(
        modifier = modifier.size(size).alpha(if (enabled) 1f else 0.35f),
        shape = CircleShape, style = live.hogar.design.FocusStyles.Round,
        selected = selected, enabled = enabled, onClick = onClick,
    ) { col ->
        Box(Modifier.align(Alignment.Center)) { HogarIcon(icon, { col.primary() }, size = iconSize) }
    }
}

@Composable
private fun Toolbar(c: BrowserController, modifier: Modifier = Modifier) {
    val t = c.active
    val addr = remember { FocusRequester() }
    RequestFocusOnShow(addr, c.overlay, enabled = c.overlay == Overlay.Toolbar || t.isStart && c.overlay == Overlay.None)
    GlassSurface(modifier.fillMaxWidth(), shape = RoundedCornerShape(Hogar.Radius.Panel)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                ToolButton(BrowserIcons.Back, c::goBack, enabled = t.canGoBack)
                ToolButton(BrowserIcons.Forward, c::goForward, enabled = t.canGoForward)
                ToolButton(if (t.loading) BrowserIcons.Close else BrowserIcons.Reload, c::reloadOrStop, enabled = !t.isStart)
                AddressPill(c, Modifier.weight(1f), addr)
                ToolButton(
                    if (c.isFavorite) BrowserIcons.StarFilled else BrowserIcons.Star, c::toggleFavorite,
                    enabled = !t.isStart, selected = c.isFavorite,
                )
                ToolButton(BrowserIcons.Tabs, c::openTabs)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassButton(
                    stringResource(if (c.desktop) R.string.tb_desktop else R.string.tb_mobile), c::toggleDesktop,
                    icon = if (c.desktop) BrowserIcons.Desktop else BrowserIcons.Phone, selected = c.desktop,
                )
                ToolButton(BrowserIcons.Minus, c::zoomLess, size = 44.dp, iconSize = 22.dp, enabled = c.zoom > ZoomSteps.steps.first())
                HogarText(
                    stringResource(R.string.zoom_value, c.zoom), Hogar.Type.Callout,
                    Modifier.width(58.dp), maxLines = 1,
                )
                ToolButton(BrowserIcons.Plus, c::zoomMore, size = 44.dp, iconSize = 22.dp, enabled = c.zoom < ZoomSteps.steps.last())
                Spacer(Modifier.weight(1f))
                GlassButton(
                    stringResource(if (c.scrollMode) R.string.tb_scroll_mode else R.string.tb_pointer_mode), c::toggleScrollMode,
                    icon = if (c.scrollMode) BrowserIcons.ScrollMode else BrowserIcons.Pointer, selected = c.scrollMode,
                )
                GlassButton(stringResource(R.string.tb_home), c::goHome, icon = BrowserIcons.Home)
            }
        }
    }
}

@Composable
private fun AddressPill(c: BrowserController, modifier: Modifier, requester: FocusRequester) {
    val t = c.active
    val keyboard = LocalSoftwareKeyboardController.current
    var field by remember { mutableStateOf(TextFieldValue("")) }
    val fieldReq = remember { FocusRequester() }
    LaunchedEffect(c.editing) {
        if (c.editing) {
            field = TextFieldValue(t.url, TextRange(0, t.url.length))
            withFrameNanos { }
            runCatching { fieldReq.requestFocus() }
            keyboard?.show()
        }
    }
    FocusSurface(
        modifier = modifier.height(52.dp).focusRequester(requester),
        onClick = { if (!c.editing) c.editing = true },
    ) { col ->
        Row(
            Modifier.fillMaxHeight().padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HogarIcon(
                if (t.url.startsWith("https://")) BrowserIcons.Lock else BrowserIcons.Search,
                { col.secondary() }, size = 20.dp,
            )
            if (c.editing) {
                BasicTextField(
                    value = field, onValueChange = { field = it }, singleLine = true,
                    textStyle = Hogar.Type.Body.copy(color = Hogar.Color.TextOnFocused),
                    cursorBrush = SolidColor(Hogar.Color.Accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, keyboardType = KeyboardType.Uri),
                    keyboardActions = KeyboardActions(onGo = { c.navigate(field.text) }),
                    modifier = Modifier.weight(1f).focusRequester(fieldReq),
                )
            } else {
                val shown = UrlInput.display(t.url)
                HogarText(
                    shown.ifEmpty { stringResource(R.string.addr_hint) }, Hogar.Type.Body,
                    Modifier.weight(1f),
                    color = { if (shown.isEmpty()) col.tertiary() else col.primary() },
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// --- página de inicio ------------------------------------------------------------------------

@Composable
private fun StartPage(c: BrowserController) {
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF171B38), Hogar.Color.Background))),
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = Hogar.Space.SafeH, vertical = Hogar.Space.SafeV),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Toolbar(c)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item { HogarText(stringResource(R.string.start_favorites), Hogar.Type.Title, Modifier.padding(start = 6.dp, top = 6.dp)) }
                val rows = c.favorites.chunked(COLUMNS)
                items(rows.size) { r ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        rows[r].forEach { site -> SiteCard(c, site, Modifier.weight(1f)) }
                        repeat(COLUMNS - rows[r].size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                if (c.history.isNotEmpty()) {
                    item { HogarText(stringResource(R.string.start_recent), Hogar.Type.Title, Modifier.padding(start = 6.dp, top = 14.dp)) }
                    val recent = c.history.take(8)
                    items(recent.size) { i -> HistoryRow(c, recent[i]) }
                }
            }
        }
    }
}

private const val COLUMNS = 5

@Composable
private fun SiteCard(c: BrowserController, site: Site, modifier: Modifier) {
    FocusSurface(
        modifier = modifier.aspectRatio(5f / 3f),
        shape = RoundedCornerShape(Hogar.Radius.Card), style = CardStyle,
        onClick = { c.navigate(site.url) },
    ) { col ->
        Column(
            Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        ) {
            SiteIcon(c, site)
            HogarText(
                site.title.ifEmpty { UrlInput.hostOfUrl(site.url) }, Hogar.Type.Callout,
                color = { col.primary() }, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Icono del sitio a su tamaño real (nunca ampliado); si no hay uno nítido, una letra sobre color. */
@Composable
private fun SiteIcon(c: BrowserController, site: Site) {
    var bmp by remember(site.url) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(site.url) { bmp = c.icons.load(site.url) }
    val b = bmp
    val density = LocalDensity.current
    if (b != null && b.width >= MIN_ICON_PX) {
        val px = minOf(b.width, MAX_ICON_PX)
        Image(
            bitmap = b, contentDescription = null,
            modifier = Modifier.size(with(density) { px.toDp() }).clip(RoundedCornerShape(14.dp)),
            contentScale = ContentScale.Fit,
        )
    } else {
        val host = UrlInput.hostOfUrl(site.url)
        val hue = (host.hashCode().toLong() and 0x7fffffff) % 360
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(14.dp))
                .background(Color.hsv(hue.toFloat(), 0.55f, 0.62f)),
            contentAlignment = Alignment.Center,
        ) {
            HogarText(
                (site.title.firstOrNull() ?: host.firstOrNull() ?: '·').uppercaseChar().toString(),
                Hogar.Type.Title.copy(fontSize = 30.sp, color = Color.White),
            )
        }
    }
}

private const val MIN_ICON_PX = 64
private const val MAX_ICON_PX = 128

@Composable
private fun HistoryRow(c: BrowserController, v: Visit) {
    FocusSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Hogar.Radius.Row), style = live.hogar.design.FocusStyles.Row,
        onClick = { c.navigate(v.url) },
    ) { col ->
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            HogarText(
                v.title.ifEmpty { UrlInput.display(v.url) }, Hogar.Type.Body,
                Modifier.weight(1f), color = { col.primary() }, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            HogarText(
                UrlInput.hostOfUrl(v.url), Hogar.Type.Caption,
                color = { col.secondary() }, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// --- pestañas --------------------------------------------------------------------------------

@Composable
private fun TabsPanel(c: BrowserController) {
    val activeReq = remember { FocusRequester() }
    RequestFocusOnShow(activeReq)
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
        GlassSurface(
            Modifier.padding(horizontal = Hogar.Space.SafeH, vertical = Hogar.Space.SafeV).fillMaxWidth(),
        ) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                HogarText(stringResource(R.string.tabs_title), Hogar.Type.Title)
                val cells: List<BrowserTab?> = c.tabs.toList() + if (TabPolicy.canOpenNew(c.tabs.size)) listOf(null) else emptyList()
                cells.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        row.forEach { tab ->
                            if (tab == null) NewTabCard(c, Modifier.weight(1f))
                            else TabCard(
                                c, tab, Modifier.weight(1f),
                                if (tab.id == c.active.id) activeReq else null,
                            )
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TabCard(c: BrowserController, tab: BrowserTab, modifier: Modifier, requester: FocusRequester?) {
    val isActive = tab.id == c.active.id
    val shape = RoundedCornerShape(Hogar.Radius.Card)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FocusSurface(
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .then(if (requester != null) Modifier.focusRequester(requester) else Modifier),
            shape = shape, style = CardStyle,
            onClick = { c.switchTo(tab.id) },
        ) { col ->
            val snap = tab.snapshot
            if (snap != null) {
                Image(snap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Column(
                    Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    HogarIcon(if (tab.isStart) BrowserIcons.Compass else BrowserIcons.Search, { col.secondary() }, size = 44.dp)
                }
            }
            if (isActive) Box(Modifier.fillMaxSize().border(3.dp, Hogar.Color.Accent, shape))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HogarText(
                tab.title.ifEmpty { if (tab.isStart) stringResource(R.string.tabs_blank) else UrlInput.display(tab.url) },
                Hogar.Type.Callout, Modifier.weight(1f).padding(start = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            ToolButton(BrowserIcons.Close, { c.closeTab(tab.id) }, size = 40.dp, iconSize = 20.dp)
        }
    }
}

@Composable
private fun NewTabCard(c: BrowserController, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FocusSurface(
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            shape = RoundedCornerShape(Hogar.Radius.Card), style = CardStyle,
            onClick = { c.newTab() },
        ) { col ->
            Column(
                Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                HogarIcon(BrowserIcons.Plus, { col.primary() }, size = 40.dp)
                HogarText(stringResource(R.string.tabs_new), Hogar.Type.Callout, color = { col.primary() })
            }
        }
    }
}

// --- menú contextual y errores -----------------------------------------------------------------

@Composable
private fun ContextMenu(c: BrowserController) {
    val first = remember { FocusRequester() }
    RequestFocusOnShow(first, c.contextLink)
    val link = c.contextLink
    Box(Modifier.fillMaxSize().background(Color(0x66000000)), contentAlignment = Alignment.Center) {
        GlassSurface(Modifier.width(440.dp)) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (link != null) {
                    GlassButton(
                        stringResource(R.string.ctx_new_tab), c::openContextLinkInNewTab,
                        Modifier.fillMaxWidth().focusRequester(first), icon = BrowserIcons.Plus,
                    )
                }
                GlassButton(
                    stringResource(R.string.ctx_back), { c.closeOverlay(); c.goBack() },
                    Modifier.fillMaxWidth().then(if (link == null) Modifier.focusRequester(first) else Modifier),
                    icon = BrowserIcons.Back, enabled = c.active.canGoBack,
                )
                GlassButton(
                    stringResource(R.string.ctx_reload), { c.closeOverlay(); c.reloadOrStop() },
                    Modifier.fillMaxWidth(), icon = BrowserIcons.Reload,
                )
            }
        }
    }
}

@Composable
private fun ErrorPanel(c: BrowserController, message: String) {
    val retry = remember { FocusRequester() }
    RequestFocusOnShow(retry, message)
    Box(Modifier.fillMaxSize().background(Hogar.Color.Background), contentAlignment = Alignment.Center) {
        GlassSurface(Modifier.width(620.dp)) {
            Column(
                Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                HogarIcon(BrowserIcons.Warning, Hogar.Color.Warning, size = 48.dp)
                HogarText(stringResource(R.string.err_title), Hogar.Type.Title, textAlignModifier)
                HogarText(
                    message, Hogar.Type.Body.copy(textAlign = TextAlign.Center, color = Hogar.Color.TextSecondary),
                    textAlignModifier,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlassButton(stringResource(R.string.err_retry), c::retryAfterError, Modifier.focusRequester(retry), icon = BrowserIcons.Reload)
                    GlassButton(stringResource(R.string.err_home), c::goHome, icon = BrowserIcons.Home)
                }
            }
        }
    }
}

private val textAlignModifier = Modifier.fillMaxWidth()
