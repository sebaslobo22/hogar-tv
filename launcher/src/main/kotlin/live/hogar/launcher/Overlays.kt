package live.hogar.launcher

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import live.hogar.design.GlassButton
import live.hogar.design.GlassSurface
import live.hogar.design.Hogar
import live.hogar.design.HogarIcon
import live.hogar.design.HogarIcons
import live.hogar.design.HogarText
import live.hogar.launcher.nightlight.NightLightPanel
import live.hogar.launcher.nightlight.NightLightRow

// ------------------------------------------------------------------ lanzamiento de apps

@Stable
internal class LaunchInfo(val app: AppEntry, val rect: Rect, val palette: CardPalette?, val layers: LayeredIcon?)

/**
 * Zoom de lanzamiento: un panel con el degradado de la tarjeta crece desde sus límites hasta
 * llenar la pantalla con resorte `.snappy`; a mitad de camino arranca la app (fundido, sin
 * animación del sistema). Al volver, el panel se encoge hasta la tarjeta (asentamiento inverso).
 */
@Stable
internal class LaunchController(private val scope: CoroutineScope) {
    var info by mutableStateOf<LaunchInfo?>(null)
        private set
    val p = Animatable(0f)
    private var left = false
    private var job: Job? = null

    fun start(i: LaunchInfo, go: () -> Boolean) {
        if (info != null) return
        info = i
        left = false
        job = scope.launch {
            p.snapTo(0f)
            val zoom = launch { p.animateTo(1f, Hogar.Motion.snappy()) }
            while (p.value < 0.55f) withFrameNanos { }
            if (go()) return@launch
            zoom.cancel()
            p.animateTo(0f, Hogar.Motion.snappy())
            info = null
        }
    }

    fun onPaused() { if (info != null) left = true }

    fun onResumed() {
        if (info == null || !left) return
        left = false
        job?.cancel()
        job = scope.launch {
            delay(60)
            p.animateTo(0f, Hogar.Motion.snappy())
            info = null
        }
    }
}

@Composable
internal fun LaunchOverlay(c: LaunchController) {
    val i = c.info ?: return
    val pal = i.palette
    val ly = i.layers
    val top = Color(ly?.takeIf { it.back == null }?.backTop ?: pal?.top ?: 0xFF22222A.toInt())
    val bottom = Color(ly?.takeIf { it.back == null }?.backBottom ?: pal?.bottom ?: 0xFF0C0C10.toInt())
    Box(
        Modifier.fillMaxSize().drawBehind {
            val p = c.p.value
            val a = (p * 4f).coerceIn(0f, 1f)
            if (a <= 0.001f) return@drawBehind
            val r = i.rect
            val l = r.left * (1f - p)
            val t = r.top * (1f - p)
            val rt = r.right + (size.width - r.right) * p
            val bt = r.bottom + (size.height - r.bottom) * p
            val w = rt - l
            val h = bt - t
            val corner = IconGeometry.CornerFraction * r.width * (1f - p)
            drawRoundRect(
                Brush.linearGradient(listOf(top, bottom), start = Offset(l, t), end = Offset(rt, bt)),
                topLeft = Offset(l, t), size = Size(w, h), cornerRadius = CornerRadius(corner), alpha = a,
            )
            val back = ly?.back
            // Nunca se amplía un raster: la capa de fondo se desvanece en cuanto el panel supera su tamaño real.
            val backFit = if (back != null) ((back.width - w) / (back.width * 0.08f) + 1f).coerceIn(0f, 1f) else 0f
            if (back != null && p < 0.95f && backFit > 0.01f) {
                drawImage(
                    back, srcOffset = IntOffset.Zero, srcSize = IntSize(back.width, back.height),
                    dstOffset = IntOffset(l.toInt(), t.toInt()), dstSize = IntSize(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1)),
                    alpha = a * (1f - p) * backFit,
                )
            }
            // El primer plano se desvanece pronto: ampliarlo a pantalla completa lo pixelaría.
            val front = ly?.front
            val fa = a * (1f - (p * 2.5f).coerceAtMost(1f))
            if (front != null && fa > 0.01f) {
                // Sin ampliar: nunca más grande que los píxeles reales del logo.
                val fw = (w * (ly?.frontW ?: 0f)).toInt().coerceIn(1, front.width)
                val fh = (h * (ly?.frontH ?: 0f)).toInt().coerceIn(1, front.height)
                drawImage(
                    front, srcOffset = IntOffset.Zero, srcSize = IntSize(front.width, front.height),
                    dstOffset = IntOffset((l + (w - fw) / 2f).toInt(), (t + (h - fh) / 2f).toInt()), dstSize = IntSize(fw, fh),
                    alpha = fa,
                )
            }
        },
    )
}

// ------------------------------------------------------------------ menú de la tarjeta

@Stable
internal class MenuState {
    var pkg by mutableStateOf<String?>(null)
    /** Desplazamiento del centro de la tarjeta respecto al centro de la pantalla (px). */
    var dx = 0f
    var dy = 0f
}

@Composable
internal fun AppMenuHost(
    state: MenuState,
    byPkg: Map<String, AppEntry>,
    prefs: LauncherPrefs,
    arranged: live.hogar.launcher.Arrangement,
    pkgs: List<String>,
    onUpdate: ((LauncherPrefs) -> LauncherPrefs) -> Unit,
    onOpen: (AppEntry) -> Unit,
) {
    val pkg = state.pkg ?: return
    val app = byPkg[pkg]
    if (app == null) { state.pkg = null; return }
    val ctx = LocalContext.current.applicationContext
    val close = { state.pkg = null }
    val pinned = pkg in prefs.pinned
    val hidden = pkg in prefs.hidden
    val original = pkg in prefs.originalLogo
    val section = if (pkg in arranged.pinned) arranged.pinned else arranged.rest
    val idx = section.indexOf(pkg)
    val first = remember { FocusRequester() }
    val pop = remember { Animatable(0f) }
    var hasBanner by remember(pkg) { mutableStateOf(!AppArt.hasNoBanner(pkg)) }
    LaunchedEffect(pkg) {
        pop.animateTo(1f, Hogar.Motion.bouncy())
    }
    LaunchedEffect(pkg) {
        delay(60); runCatching { first.requestFocus() }
        hasBanner = withContext(Dispatchers.IO) { AppArt.hasBanner(ctx, app) }
    }

    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().drawBehind { drawRect(Color.Black.copy(alpha = 0.55f * pop.value.coerceIn(0f, 1f))) },
            contentAlignment = Alignment.Center,
        ) {
            GlassSurface(
                Modifier.width(380.dp).graphicsLayer {
                    val p = pop.value
                    val s = 0.55f + 0.45f * p
                    scaleX = s; scaleY = s
                    translationX = state.dx * (1f - p)
                    translationY = state.dy * (1f - p)
                    alpha = (p * 1.6f).coerceIn(0f, 1f)
                },
                shape = RoundedCornerShape(Hogar.Radius.Panel),
            ) {
                Column(Modifier.padding(Hogar.Space.M), verticalArrangement = Arrangement.spacedBy(Hogar.Space.Xs)) {
                    HogarText(
                        app.label, Hogar.Type.Headline, Modifier.padding(start = 6.dp, bottom = 6.dp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    val full = Modifier.fillMaxWidth()
                    val shape = RoundedCornerShape(Hogar.Radius.Row)
                    GlassButton("Abrir", { close(); onOpen(app) }, full.focusRequester(first), HogarIcons.Play, shape)
                    GlassButton(
                        if (pinned) "Quitar de Favoritas" else "Fijar en Favoritas",
                        { onUpdate { LauncherLogic.togglePin(it, pkg) }; close() }, full, HogarIcons.Pin, shape,
                    )
                    GlassButton("Mover a la izquierda", { onUpdate { LauncherLogic.move(pkgs, it, pkg, -1) } }, full, HogarIcons.ArrowLeft, shape, enabled = idx > 0)
                    GlassButton("Mover a la derecha", { onUpdate { LauncherLogic.move(pkgs, it, pkg, 1) } }, full, HogarIcons.ArrowRight, shape, enabled = idx in 0 until section.lastIndex)
                    GlassButton(
                        if (hidden) "Dejar de ocultar" else "Ocultar",
                        { onUpdate { LauncherLogic.toggleHidden(it, pkg) }; close() }, full,
                        if (hidden) HogarIcons.Eye else HogarIcons.EyeSlash, shape,
                    )
                    GlassButton(
                        if (original) "Icono en capas" else "Banner de TV",
                        { onUpdate { LauncherLogic.toggleOriginalLogo(it, pkg) } }, full, HogarIcons.Tv, shape,
                        enabled = original || hasBanner,
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ centro de control

@Stable
internal class OverlayState {
    var open by mutableStateOf(false)
    /** Panel de ajustes de la luz nocturna (se abre desde el Centro de control). */
    var nightOpen by mutableStateOf(false)
}

/**
 * Panel de vidrio que entra desde la derecha: hora, red, atajo a AirPlay, Este TV y
 * "Mostrar ocultas". Se abre con la flecha derecha pasando la última píldora o con la tecla
 * MENÚ/Ajustes del mando.
 */
@Composable
internal fun ControlCenterHost(
    state: OverlayState,
    clock: () -> Pair<String, String>,
    showHidden: Boolean,
    onToggleHidden: () -> Unit,
    onAudio: () -> Unit,
    onSpecs: () -> Unit,
) {
    NightLightPanel(state.nightOpen) { state.nightOpen = false }
    if (!state.open) return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var serviceOn by remember { mutableStateOf(isHomeServiceEnabled(ctx)) }
    val p = remember { Animatable(0f) }
    val first = remember { FocusRequester() }
    var net by remember { mutableStateOf(SystemBits.network(ctx)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        net = SystemBits.network(ctx)
    }
    LaunchedEffect(Unit) {
        launch { p.animateTo(1f, Hogar.Motion.smooth()) }
        delay(80); runCatching { first.requestFocus() }
        net = withContext(Dispatchers.IO) { SystemBits.network(ctx) }
    }
    fun close(after: (() -> Unit)? = null) {
        scope.launch {
            p.animateTo(0f, Hogar.Motion.smooth())
            state.open = false
            after?.invoke()
        }
    }
    val (time, date) = remember { clock() }
    val shape = RoundedCornerShape(Hogar.Radius.Row)

    Dialog(onDismissRequest = { close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize()
                .drawBehind { drawRect(Color.Black.copy(alpha = 0.40f * p.value.coerceIn(0f, 1f))) }
                .onPreviewKeyEvent { e ->
                    when {
                        e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft -> { close(); true }
                        (e.key == Key.Menu || e.key == Key.Settings) -> { if (e.type == KeyEventType.KeyUp) close(); true }
                        else -> false
                    }
                },
        ) {
            GlassSurface(
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(320.dp)
                    .padding(top = Hogar.Space.SafeV, bottom = Hogar.Space.SafeV, end = 24.dp)
                    .graphicsLayer {
                        translationX = (1f - p.value) * (size.width + 48.dp.toPx())
                        alpha = p.value.coerceIn(0f, 1f)
                    },
                shape = RoundedCornerShape(Hogar.Radius.Panel),
            ) {
                Column(Modifier.padding(Hogar.Space.M), verticalArrangement = Arrangement.spacedBy(Hogar.Space.Xs)) {
                    HogarText(time, Hogar.Type.Title.copy(fontSize = 40.sp))
                    HogarText(date, Hogar.Type.Caption, Modifier.padding(bottom = Hogar.Space.S))

                    val wifiText = when (net.kind) {
                        SystemBits.NetKind.Wifi -> net.ssid ?: "Conectado por Wi-Fi"
                        SystemBits.NetKind.Ethernet -> "Cable de red"
                        SystemBits.NetKind.Other -> "Conectado"
                        SystemBits.NetKind.None -> "Sin conexión"
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(start = 6.dp, bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        HogarIcon(HogarIcons.Wifi, Hogar.Color.TextSecondary, size = 22.dp)
                        Column {
                            HogarText("Red", Hogar.Type.Caption)
                            HogarText(wifiText, Hogar.Type.Callout, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (net.needsLocation) {
                        GlassButton(
                            "Mostrar nombre de la red",
                            { permission.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
                            Modifier.fillMaxWidth(), HogarIcons.Search, shape,
                        )
                    }
                    val full = Modifier.fillMaxWidth()
                    GlassButton("Audio y AirPlay", { close(onAudio) }, full.focusRequester(first), HogarIcons.AirPlay, shape)
                    GlassButton("Reproduciendo ahora", { close { NowPlayingLink.open(ctx) } }, full, HogarIcons.Music, shape)
                    NightLightRow(serviceOn, onOpenPanel = { close { state.nightOpen = true } }, modifier = full)
                    GlassButton("Este TV", { close(onSpecs) }, full, HogarIcons.Tv, shape)
                    GlassButton(
                        "Mostrar ocultas", onToggleHidden, full,
                        if (showHidden) HogarIcons.Eye else HogarIcons.EyeSlash, shape,
                        trailing = if (showHidden) HogarIcons.Check else null,
                    )
                }
            }
        }
    }
}

