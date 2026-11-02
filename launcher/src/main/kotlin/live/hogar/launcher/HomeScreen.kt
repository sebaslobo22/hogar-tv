package live.hogar.launcher

import androidx.compose.ui.graphics.drawscope.DrawScope

import androidx.compose.animation.core.Animatable

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.text.format.DateFormat
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import live.hogar.design.Hogar
import live.hogar.design.HogarText
import live.hogar.design.LocalGlow
import live.hogar.design.SoftShadow
import live.hogar.design.TabButton
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.roundToInt

/** Memoria del último foco entre visitas a Audio / Este TV (vive mientras viva el proceso). */
private object HomeMemory { var lastPkg: String? = null }

/** Proporciones del Home (tvOS): el Top Shelf ocupa ~45 % de la altura; la fila enfocada se ancla a ~24 %. */
private const val ShelfFraction = 0.45f
private const val PivotFraction = 0.24f

@Composable
fun HomeScreen(onOpenSpecs: () -> Unit, onOpenAudio: () -> Unit, controlCenterRequests: Int = 0) {
    val appCtx = LocalContext.current.applicationContext
    val store = remember { LauncherStore(appCtx) }
    val repo = remember { AppRepository(appCtx) }
    val scope = rememberCoroutineScope()
    val installed by rememberInstalledApps(repo)
    val prefs by store.prefs.collectAsStateWithLifecycle<LauncherPrefs?>(null)

    val apps = installed
    val p = prefs
    if (apps == null || p == null) return // el fondo ya lo dibuja la raíz
    HomeContent(
        apps = apps,
        prefs = p,
        onUpdate = { t -> scope.launch { store.update(t) } },
        onOpenSpecs = onOpenSpecs,
        onOpenAudio = onOpenAudio,
        controlCenterRequests = controlCenterRequests,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeContent(
    apps: List<AppEntry>,
    prefs: LauncherPrefs,
    onUpdate: ((LauncherPrefs) -> LauncherPrefs) -> Unit,
    onOpenSpecs: () -> Unit,
    onOpenAudio: () -> Unit,
    controlCenterRequests: Int,
) {
    val ctx = LocalContext.current
    val appCtx = ctx.applicationContext
    val scope = rememberCoroutineScope()
    val dm = LocalView.current.resources.displayMetrics
    val density = LocalDensity.current.density
    val cfg = LocalConfiguration.current
    val screenW = cfg.screenWidthDp.toFloat()
    val screenH = cfg.screenHeightDp.toFloat()
    val pkgs = remember(apps) { apps.map { it.packageName } }
    val byPkg = remember(apps) { apps.associateBy { it.packageName } }
    val arranged = remember(pkgs, prefs) { LauncherLogic.arrange(pkgs, prefs) }
    // Favoritas primero y luego el resto: una sola rejilla de 5 por fila.
    val flat = remember(arranged) { IconGrid.flat(arranged.pinned, arranged.rest) }

    // Medidas de la rejilla (dp) y su desplazamiento (px).
    val geo = remember(screenW) { IconGrid.metrics(screenW - 2f * Hogar.Space.SafeH.value) }
    val shelfDp = screenH * ShelfFraction
    val pivotPx = screenH * PivotFraction * density
    val padTopPx = shelfDp * density
    val pitchPx = geo.rowPitch * density
    val padBottomDp = (screenH - screenH * PivotFraction - geo.rowPitch).coerceAtLeast(0f) + IconGrid.RowGapDp
    val gridState = rememberLazyGridState()
    val scrollNow = remember(gridState, pitchPx) {
        { IconGrid.currentScroll(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset, pitchPx) }
    }
    // Sombra suave horneada una vez (un mapa de bits borroso); sustituye a la elevación del sistema.
    val shadow = remember(geo, density) {
        SoftShadow.create(
            wPx = (geo.cellWidth * density).roundToInt(), hPx = (geo.iconHeight * density).roundToInt(),
            cornerPx = IconGeometry.cornerRadius(geo.cellWidth) * density, blurPx = (22f * density).roundToInt(),
        )
    }

    // Estado de foco: lo leen solo componentes pequeños; HomeContent no se recompone al mover el foco.
    val focus = remember { HomeFocus() }
    val menu = remember { MenuState() }
    val cc = remember { OverlayState() }
    // La tecla de perfil del mando pide abrir el Centro de control (ver MainActivity).
    LaunchedEffect(controlCenterRequests) { if (controlCenterRequests > 0) cc.open = true }
    val launch = remember { LaunchController(scope) }
    val requesters = remember { HashMap<String, FocusRequester>() }
    fun requester(pkg: String) = requesters.getOrPut(pkg) { FocusRequester() }
    val paletteTick = remember { mutableIntStateOf(0) }

    // Brillo de fondo = color de marca de la app enfocada (crossfade `.smooth` dentro del controlador).
    val glow = LocalGlow.current
    LaunchedEffect(glow) {
        snapshotFlow { focus.pkg to paletteTick.intValue }.collect { (pkg, _) ->
            val c = pkg?.let { AppArt.peekPalette(it) }?.glow
            glow?.setTarget(if (c != null) Color(c) else Hogar.Color.GlowDefault)
        }
    }
    // Paletas de marca de todas las apps, en segundo plano y de una en una; luego el arte de la fila 0.
    LaunchedEffect(apps) {
        withContext(Dispatchers.IO) {
            for (a in apps) {
                if (AppArt.peekPalette(a.packageName) == null) {
                    AppArt.palette(appCtx, a)
                    paletteTick.intValue++
                }
                yield()
            }
            for (pkg in flat.take(IconGrid.Columns)) {
                byPkg[pkg]?.let { AppArt.shelf(appCtx, it, screenW, shelfDp, geo.cellWidth) }
                yield()
            }
        }
    }

    // Como tvOS: al bajar de fila el Top Shelf se recoge y la fila enfocada sube a su ancla; en la fila 0 vuelve.
    LaunchedEffect(gridState, pitchPx) {
        snapshotFlow { focus.row }.collectLatest { r ->
            if (r >= 0) {
                val d = IconGrid.targetScroll(r, padTopPx, pitchPx, pivotPx) - scrollNow()
                if (abs(d) > 1f) gridState.animateScrollBy(d, Hogar.Motion.smooth())
            }
        }
    }

    // Foco inicial: el último visto (si sigue instalado y visible) o la primera.
    val firstPkg = flat.firstOrNull()
    var initialDone by remember { mutableStateOf(false) }
    LaunchedEffect(firstPkg) {
        if (!initialDone && firstPkg != null) {
            val want = HomeMemory.lastPkg?.takeIf { it in flat } ?: firstPkg
            val idx = flat.indexOf(want)
            if (IconGrid.rowOf(idx) > 0) gridState.scrollToItem(idx - idx % IconGrid.Columns)
            delay(60)
            if (runCatching { requester(want).requestFocus() }.isSuccess) {
                initialDone = true
                if (focus.pkg == null) focus.pkg = want
            }
        }
    }
    // Al cerrar el menú, el foco vuelve al icono.
    LaunchedEffect(Unit) {
        var last: String? = null
        snapshotFlow { menu.pkg }.collect { cur ->
            if (cur == null && last != null) {
                delay(60)
                val ok = runCatching { requester(last!!).requestFocus() }.isSuccess
                if (!ok) firstPkg?.let { runCatching { requester(it).requestFocus() } }
            }
            last = cur
        }
    }
    // Ciclo de vida: al volver de una app, el zoom se asienta de nuevo en su icono.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_PAUSE) launch.onPaused()
            if (e == Lifecycle.Event.ON_RESUME) launch.onResumed()
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    val markSeen = { onUpdate { LauncherLogic.markHintSeen(it) } }
    val doLaunch: (AppEntry, Rect) -> Unit = { app, rect ->
        HomeMemory.lastPkg = app.packageName
        if (!prefs.hintSeen) markSeen()
        val banner = app.packageName in prefs.originalLogo
        launch.start(
            LaunchInfo(app, rect, AppArt.peekPalette(app.packageName), AppArt.peekLayers(app.packageName, banner)),
        ) { SystemBits.launchApp(ctx, app) }
    }
    val doMenu: (AppEntry, Rect) -> Unit = { app, rect ->
        if (!prefs.hintSeen) markSeen()
        menu.dx = rect.center.x - dm.widthPixels / 2f
        menu.dy = rect.center.y - dm.heightPixels / 2f
        menu.pkg = app.packageName
    }

    // El desplazamiento lo decide la rejilla (ancla de fila), no el sistema de "traer a la vista".
    val noAutoScroll = remember {
        object : BringIntoViewSpec {
            override val scrollAnimationSpec = Hogar.Motion.smooth<Float>()
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = 0f
        }
    }

    Box(
        Modifier.fillMaxSize().onPreviewKeyEvent { e ->
            if ((e.key == Key.Menu || e.key == Key.Settings) && menu.pkg == null) {
                if (e.type == KeyEventType.KeyUp) cc.open = true
                true
            } else false
        },
    ) {
        // Contenido: se acerca un poco mientras una app se abre (profundidad).
        Box(Modifier.fillMaxSize().graphicsLayer { val s = 1f + 0.035f * launch.p.value; scaleX = s; scaleY = s }) {
            TopShelf(focus, byPkg, showHint = !prefs.hintSeen, shelfDp = shelfDp, widthDp = screenW, cellWidthDp = geo.cellWidth, scroll = scrollNow)
            if (flat.isEmpty()) {
                HogarText(
                    "No hay apps para mostrar", Hogar.Type.Headline.copy(color = Hogar.Color.TextSecondary),
                    Modifier.align(Alignment.BottomStart).padding(start = Hogar.Space.SafeH, bottom = Hogar.Space.L),
                )
            } else {
                CompositionLocalProvider(LocalBringIntoViewSpec provides noAutoScroll) {
                    AppGrid(
                        flat = flat, byPkg = byPkg, prefs = prefs, geo = geo, shadow = shadow, state = gridState,
                        shelfDp = shelfDp, padBottomDp = padBottomDp, focus = focus, requester = ::requester,
                        onLaunch = doLaunch, onMenu = doMenu,
                    )
                }
            }
            TopBar(
                onAudio = onOpenAudio, onSpecs = onOpenSpecs,
                onSettings = { SystemBits.openSettings(ctx) },
                onPastEnd = { cc.open = true },
                scroll = scrollNow, shelfPx = padTopPx,
                modifier = Modifier.align(Alignment.TopStart),
            )
        }
        LaunchOverlay(launch)
        HomeButtonNotice(Modifier.align(Alignment.BottomCenter))
    }

    AppMenuHost(
        state = menu, byPkg = byPkg, prefs = prefs, arranged = arranged, pkgs = pkgs, onUpdate = onUpdate,
        onOpen = { app -> doLaunch(app, Rect.Zero) },
    )
    ControlCenterHost(
        state = cc,
        clock = { val c = Calendar.getInstance(); ClockText.time(appCtx, c) to ClockText.date(c) },
        showHidden = prefs.showHidden,
        onToggleHidden = { onUpdate { LauncherLogic.setShowHidden(it, !it.showHidden) } },
        onAudio = onOpenAudio, onSpecs = onOpenSpecs,
    )
}

// ------------------------------------------------------------------ saludo y reloj

internal object ClockText {
    fun time(ctx: Context, c: Calendar): String {
        val h = c.get(Calendar.HOUR_OF_DAY)
        val m = c.get(Calendar.MINUTE)
        return if (DateFormat.is24HourFormat(ctx)) String.format("%02d:%02d", h, m)
        else String.format("%d:%02d", if (h % 12 == 0) 12 else h % 12, m)
    }

    fun date(c: Calendar): String =
        SpanishDate.long(c.get(Calendar.DAY_OF_WEEK), c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH))
}

/**
 * Barra superior estilo tvOS: línea de estado discreta (saludo y fecha) a la izquierda, pestañas
 * de solo texto al centro y la hora a la derecha. Se desvanece al recogerse el Top Shelf.
 */
@Composable
private fun TopBar(
    onAudio: () -> Unit,
    onSpecs: () -> Unit,
    onSettings: () -> Unit,
    onPastEnd: () -> Unit,
    scroll: () -> Float,
    shelfPx: Float,
    modifier: Modifier,
) {
    val ctx = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000L - now % 60_000L + 50L)
        }
    }
    val cal = remember(now) { Calendar.getInstance().apply { timeInMillis = now } }
    Box(
        modifier.fillMaxWidth()
            .padding(horizontal = Hogar.Space.SafeH, vertical = Hogar.Space.SafeV)
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.ModulateAlpha
                val c = IconGrid.shelfCollapse(scroll(), shelfPx)
                alpha = (1f - c * 1.8f).coerceIn(0f, 1f)
                translationY = -c * 18.dp.toPx()
            },
    ) {
        HogarText(
            "${Greeting.forHour(cal.get(Calendar.HOUR_OF_DAY))}  ·  ${ClockText.date(cal)}",
            Hogar.Type.Caption, Modifier.align(Alignment.CenterStart),
        )
        Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(Hogar.Space.Xxs), verticalAlignment = Alignment.CenterVertically) {
            TabButton("Audio", onAudio)
            TabButton("Este TV", onSpecs)
            TabButton(
                "Ajustes", onSettings,
                Modifier.onPreviewKeyEvent { e ->
                    // Flecha derecha más allá de la última pestaña = centro de control.
                    if (e.key == Key.DirectionRight) { if (e.type == KeyEventType.KeyDown) onPastEnd(); true } else false
                },
            )
        }
        HogarText(ClockText.time(ctx, cal), Hogar.Type.Callout.copy(fontSize = 20.sp), Modifier.align(Alignment.CenterEnd))
    }
}

// ------------------------------------------------------------------ Top Shelf

/**
 * Top Shelf (tvOS): para la app enfocada de la primera fila, su arte grande con degradado y el
 * nombre en grande. Se recoge hacia arriba al mismo ritmo que la rejilla cuando el foco baja.
 */
@Composable
private fun TopShelf(
    focus: HomeFocus,
    byPkg: Map<String, AppEntry>,
    showHint: Boolean,
    shelfDp: Float,
    widthDp: Float,
    cellWidthDp: Float,
    scroll: () -> Float,
) {
    Box(
        Modifier.fillMaxWidth().height(shelfDp.dp)
            .graphicsLayer { translationY = -scroll().coerceAtLeast(0f) },
    ) {
        ShelfBackdrop(focus.heroPkg, byPkg, shelfDp, widthDp, cellWidthDp)
        // Solo el texto hace fundido con capas (es pequeño); el fondo y el logo se funden dibujando.
        Crossfade(focus.heroPkg, animationSpec = Hogar.Motion.smooth(), label = "shelfTitle") { p ->
            val app = p?.let { byPkg[it] }
            if (app != null) ShelfTitle(app, showHint)
        }
    }
}

/**
 * Fondo y logo del Top Shelf con fundido en UNA sola pasada de dibujo (sin capas fuera de pantalla):
 * el anterior a pleno y el nuevo encima con alfa. Un Crossfade de composables costaba ~19 ms por
 * cuadro en este chip (dos fondos completos en buffers aparte).
 */
@Composable
private fun ShelfBackdrop(pkg: String?, byPkg: Map<String, AppEntry>, shelfDp: Float, widthDp: Float, cellWidthDp: Float) {
    val ctx = LocalContext.current.applicationContext
    val mix = remember { Animatable(1f) }
    var cur by remember { mutableStateOf<ShelfArt?>(null) }
    var prev by remember { mutableStateOf<ShelfArt?>(null) }
    LaunchedEffect(pkg) {
        val app = pkg?.let { byPkg[it] } ?: return@LaunchedEffect
        val next = AppArt.peekShelf(app.packageName)
            ?: withContext(Dispatchers.IO) { AppArt.shelf(ctx, app, widthDp, shelfDp, cellWidthDp) }
            ?: return@LaunchedEffect
        if (next === cur) return@LaunchedEffect
        prev = cur
        cur = next
        mix.snapTo(0f)
        mix.animateTo(1f, Hogar.Motion.smooth())
        prev = null
    }
    val glyphs = remember { HashMap<GlyphKind, androidx.compose.ui.graphics.vector.ImageVector>() }
    val painterCur = cur?.glyph?.let { rememberVectorPainter(glyphs.getOrPut(it) { glyphVector(it) }) }
    val painterPrev = prev?.glyph?.let { rememberVectorPainter(glyphs.getOrPut(it) { glyphVector(it) }) }
    // Pinceles creados una vez por arte (crear shaders por cuadro sube texturas en este chip).
    val brushes = remember { HashMap<ShelfArt, Brush>() }
    fun brushOf(a: ShelfArt) = brushes.getOrPut(a) {
        val top = Color(a.bgTop)
        val bottom = Color(a.bgBottom)
        Brush.verticalGradient(0f to top, 0.45f to lerp(top, bottom, 0.45f), 1f to bottom.copy(alpha = 0f))
    }
    Box(
        Modifier.fillMaxSize().drawBehind {
            val m = mix.value
            val p = prev
            val c = cur
            if (p != null && m < 1f) drawShelfArt(p, brushOf(p), painterPrev, 1f, 1f - m)
            if (c != null) drawShelfArt(c, brushOf(c), painterCur, if (p != null) m else 1f, m)
        },
    )
}

private fun DrawScope.drawShelfArt(a: ShelfArt, brush: Brush, glyph: androidx.compose.ui.graphics.vector.VectorPainter?, bgAlpha: Float, logoAlpha: Float) {
    val dst = IntSize(size.width.roundToInt().coerceAtLeast(1), size.height.roundToInt().coerceAtLeast(1))
    drawRect(brush, alpha = bgAlpha)
    drawImage(a.ambient, dstSize = dst, alpha = bgAlpha, filterQuality = FilterQuality.Low)
    val w = (size.width * a.boxW).roundToInt().coerceAtLeast(1)
    val h = (size.height * a.boxH).roundToInt().coerceAtLeast(1)
    val ox = (size.width * a.boxLeft).roundToInt()
    val oy = (size.height * a.boxTop).roundToInt()
    val logo = a.logo
    if (logo != null) {
        drawImage(logo, dstOffset = IntOffset(ox, oy), dstSize = IntSize(w, h), alpha = logoAlpha, filterQuality = FilterQuality.Medium)
    } else if (glyph != null) {
        translate(ox.toFloat(), oy.toFloat()) {
            with(glyph) { draw(Size(w.toFloat(), h.toFloat()), alpha = logoAlpha, colorFilter = ColorFilter.tint(Color.White)) }
        }
    }
}

@Composable
private fun ShelfTitle(app: AppEntry, showHint: Boolean) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.BottomStart).padding(start = Hogar.Space.SafeH, bottom = 26.dp)) {
            HogarText(
                app.label, Hogar.Type.LargeTitle.copy(fontSize = 44.sp, textAlign = TextAlign.Start),
                Modifier.fillMaxWidth(0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (showHint) HogarText("Mantén OK para opciones", Hogar.Type.Caption, Modifier.padding(top = 2.dp))
        }
    }
}

// ------------------------------------------------------------------ rejilla de iconos

@Composable
private fun AppGrid(
    flat: List<String>,
    byPkg: Map<String, AppEntry>,
    prefs: LauncherPrefs,
    geo: IconGrid.Metrics,
    shadow: SoftShadow.Baked,
    state: LazyGridState,
    shelfDp: Float,
    padBottomDp: Float,
    focus: HomeFocus,
    requester: (String) -> FocusRequester,
    onLaunch: (AppEntry, Rect) -> Unit,
    onMenu: (AppEntry, Rect) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(IconGrid.Columns),
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Hogar.Space.SafeH, end = Hogar.Space.SafeH, top = shelfDp.dp, bottom = padBottomDp.dp),
        horizontalArrangement = Arrangement.spacedBy(geo.gap.dp),
        verticalArrangement = Arrangement.spacedBy(IconGrid.RowGapDp.dp),
    ) {
        itemsIndexed(flat, key = { _, pkg -> pkg }) { i, pkg ->
            val app = byPkg[pkg] ?: return@itemsIndexed
            AppIcon(
                app = app, row = IconGrid.rowOf(i), hidden = pkg in prefs.hidden,
                useBanner = pkg in prefs.originalLogo,
                cellWidthDp = geo.cellWidth, shadow = shadow,
                requester = requester(pkg), focus = focus,
                onLaunch = onLaunch, onMenu = onMenu,
            )
        }
    }
}

// ------------------------------------------------------------------ datos

@Composable
private fun rememberInstalledApps(repo: AppRepository): State<List<AppEntry>?> {
    val ctx = LocalContext.current
    val tick = remember { mutableIntStateOf(0) }
    DisposableEffect(ctx) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { tick.intValue++ }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else ctx.registerReceiver(receiver, filter)
        onDispose { ctx.unregisterReceiver(receiver) }
    }
    val state = remember { mutableStateOf<List<AppEntry>?>(null) }
    val t = tick.intValue
    LaunchedEffect(t) {
        if (t > 0) delay(400) // agrupa ráfagas (actualizar = quitar + agregar)
        state.value = withContext(Dispatchers.IO) { repo.load() }
    }
    return state
}
