package live.hogar.nowplaying

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import live.hogar.design.GlassButton
import live.hogar.design.GlassIconButton
import live.hogar.design.GlassSurface
import live.hogar.design.Hogar
import live.hogar.design.HogarGlow
import live.hogar.design.HogarIcon
import live.hogar.design.HogarIcons
import live.hogar.design.HogarRoot
import live.hogar.design.HogarText
import live.hogar.nowplaying.light.LightEngine
import kotlin.math.max
import kotlin.math.roundToInt

private const val LyricLeadMs = 250L
private const val TickMs = 250L
private const val ChromeHideMs = 5_000L
private const val LyricsTag = "HogarLyrics"

@Composable
internal fun NowPlayingScreen(
    tracker: MediaTracker,
    lyrics: LyricsRepository,
    engine: MeshEngine,
    light: LightEngine,
    granted: Boolean,
    autoMode: Int,
    autoLaunched: Boolean,
    onCycleAuto: () -> Unit,
    onOpenAccessSettings: () -> Unit,
    onClose: () -> Unit,
) {
    val st by tracker.state.collectAsState()
    val track = st.track
    val targetPx = with(LocalDensity.current) { 440.dp.roundToPx() }
    val ctx = LocalContext.current

    // Todo lo pesado de la carátula (recorte, esquinas, sombra, colores) se calcula una vez por imagen.
    val art = st.art
    var prepared by remember { mutableStateOf<PreparedArt?>(null) }
    LaunchedEffect(art, targetPx) {
        prepared = withContext(Dispatchers.Default) { ArtworkRender.prepare(art, targetPx) }
    }
    LaunchedEffect(prepared) { prepared?.let { engine.setColors(it.mesh) } }

    // Posición para barra y reloj: se extrapola 4 veces por segundo, solo mientras suena.
    val positionMs = remember { mutableLongStateOf(0L) }
    LaunchedEffect(st.playback, track?.durationMs) {
        val p = st.playback
        val dur = track?.durationMs ?: 0L
        while (isActive) {
            positionMs.longValue = PlaybackMath.position(p.positionMs, p.updateElapsedMs, SystemClock.elapsedRealtime(), p.speed, p.playing, dur)
            if (!p.playing) break
            delay(TickMs)
        }
    }
    // Posición exacta bajo demanda (la fase de dibujo la pide por cuadro para el barrido de palabras).
    val playbackRef = rememberUpdatedState(st.playback)
    val durationRef = rememberUpdatedState(track?.durationMs ?: 0L)
    val nowMs: () -> Long = remember {
        {
            val p = playbackRef.value
            PlaybackMath.position(p.positionMs, p.updateElapsedMs, SystemClock.elapsedRealtime(), p.speed, p.playing, durationRef.value)
        }
    }

    var lyricsState by remember { mutableStateOf<LyricsState>(LyricsState.Loading) }
    LaunchedEffect(track?.key) {
        lyricsState = LyricsState.Loading
        lyricsState = if (track == null) LyricsState.None else lyrics.load(track)
    }
    val ready = (lyricsState as? LyricsState.Ready)?.lyrics
    // Una sola decisión de qué se dibuja (sincronizada / texto / nada); el modo letra sale de ella.
    val view = remember(ready) { LyricsView.resolve(ready) }
    val currentIndex by remember(view) {
        derivedStateOf { (view as? LyricsView.Synced)?.let { LyricTimeline.indexAt(it.items, positionMs.longValue + LyricLeadMs) } ?: -1 }
    }
    val loadingLyrics = lyricsState is LyricsState.Loading
    LaunchedEffect(track?.key, view, loadingLyrics) {
        val name = track?.let { "${it.title} - ${it.artist}" } ?: "-"
        snapshotFlow { currentIndex }.collect { Log.d(LyricsTag, LyricsView.describe(name, view, it, loadingLyrics)) }
    }
    val hasLyrics = view !is LyricsView.None

    var showLyrics by remember { mutableStateOf(true) }
    var queueOpen by remember { mutableStateOf(false) }
    var shuffleOn by remember { mutableStateOf(false) }
    var repeatMode by remember { mutableIntStateOf(0) }
    val lyricsOn = showLyrics && hasLyrics && !queueOpen

    // Controles: se desvanecen a los 5 s sin mando y reaparecen con cualquier tecla.
    var chromeShown by remember { mutableStateOf(true) }
    var chromeKick by remember { mutableIntStateOf(0) }
    LaunchedEffect(chromeKick) {
        delay(ChromeHideMs)
        chromeShown = false
    }
    val chromeAlpha by animateFloatAsState(if (chromeShown) 1f else 0f, tween(if (chromeShown) 220 else 600), label = "chrome")

    val root = remember { FocusRequester() }
    val plan = remember { NpFocus() }
    // Los controles solo reciben foco si se ven y la cola no está abierta (ahí manda la lista).
    val canFocusChrome: () -> Boolean = { chromeShown && !queueOpen }
    // Foco: al abrir, en pausa/play. Al ocultarse los controles, en la raíz (nunca en algo invisible);
    // al reaparecer, de vuelta al último control (la cola abierta conserva el foco en su lista).
    LaunchedEffect(chromeShown, track != null, granted) {
        if (!chromeShown) {
            if (!queueOpen) runCatching { root.requestFocus() }
        } else if (!queueOpen) {
            if (track != null && granted) {
                if (plan.focusedId < 0) {
                    delay(60)
                    if (!plan.req[plan.last].tryFocus()) plan.req[NpFocusMap.PLAY].tryFocus()
                }
            } else runCatching { root.requestFocus() }
        }
    }
    // Al cerrarse la cola, el foco vuelve al botón Cola.
    val queueWasOpen = remember { booleanArrayOf(false) }
    LaunchedEffect(queueOpen) {
        if (queueOpen) { queueWasOpen[0] = true; return@LaunchedEffect }
        if (!queueWasOpen[0]) return@LaunchedEffect
        queueWasOpen[0] = false
        chromeShown = true
        // Reintenta hasta que el botón Cola de verdad tenga el foco (puede tardar en volver a ser enfocable).
        for (attempt in 0 until 6) {
            delay(if (attempt == 0) 40L else 80L)
            plan.req[NpFocusMap.QUEUE].tryFocus()
            delay(16)
            if (plan.focusedId == NpFocusMap.QUEUE || queueOpen) break
        }
        Log.d(FocusTag, "cola cerrada, foco=${plan.focusedId.let { if (it < 0) "ninguno" else NpFocusMap.NAMES[it] }}")
        if (plan.focusedId < 0 && !queueOpen) plan.req[NpFocusMap.PLAY].tryFocus()
    }
    // Atrás: cierra la cola → oculta los controles → sale. Cada handler registrado después gana.
    BackHandler(onBack = onClose)
    BackHandler(enabled = chromeShown && !queueOpen) { chromeShown = false }
    BackHandler(enabled = queueOpen) { queueOpen = false; chromeShown = true; chromeKick++ }

    HogarRoot {
        HogarGlow(prepared?.let { Color(it.accent) } ?: Hogar.Color.GlowDefault)
        MeshBackground(engine)
        Box(
            Modifier.fillMaxSize()
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val code = e.nativeKeyEvent.keyCode
                    Log.d(FocusTag, "raiz preview tecla=${e.key} foco=${plan.focusedId.let { if (it < 0) "ninguno" else NpFocusMap.NAMES[it] }} controles=$chromeShown cola=$queueOpen")
                    if (autoLaunched && !isMediaKey(code)) { onClose(); return@onPreviewKeyEvent true }
                    // Atrás nunca despierta los controles: lo resuelven los BackHandler.
                    if (e.key == Key.Back) return@onPreviewKeyEvent false
                    val wasHidden = !chromeShown
                    chromeShown = true
                    chromeKick++
                    when {
                        // La primera tecla solo despierta los controles (como Apple); no actúa. Con la cola abierta sí.
                        wasHidden && !queueOpen && !isMediaKey(code) -> true
                        // Atajo de letra solo si ningún botón tiene el foco (si lo tiene, las flechas navegan).
                        e.key == Key.DirectionUp && !queueOpen && plan.focusedId < 0 -> { showLyrics = true; true }
                        e.key == Key.DirectionDown && !queueOpen && plan.focusedId < 0 -> { showLyrics = false; true }
                        else -> false
                    }
                }
                .onKeyEvent { e ->
                    // Solo llegan aquí si ningún botón los consumió (el foco está en la raíz).
                    if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                    Log.d(FocusTag, "raiz sin consumir tecla=${e.key} foco=${plan.focusedId}")
                    when (e.key) {
                        Key.Enter, Key.DirectionCenter, Key.NumPadEnter -> { tracker.playPause(); true }
                        // Con un botón enfocado las flechas son del sistema de foco: la raíz no las toca.
                        Key.DirectionLeft, Key.DirectionRight -> when {
                            plan.focusedId >= 0 -> false
                            queueOpen -> { if (e.key == Key.DirectionLeft) queueOpen = false; true }
                            else -> plan.req[plan.last].tryFocus() || plan.req[NpFocusMap.PLAY].tryFocus()
                        }
                        else -> false
                    }
                }
                .focusRequester(root)
                .focusable(),
        ) {
            when {
                !granted || st.accessDenied -> AccessPrompt(onOpenAccessSettings)
                track == null -> Empty()
                else -> Content(
                    track = track, st = st, prepared = prepared, view = view,
                    currentIndex = currentIndex, positionMs = positionMs, nowMs = nowMs, engine = engine,
                    lyricsOn = lyricsOn, queueOpen = queueOpen, showLyrics = showLyrics, hasLyrics = hasLyrics,
                    autoMode = autoMode, plan = plan, canFocusChrome = canFocusChrome, chromeAlpha = { chromeAlpha },
                    shuffleOn = shuffleOn, repeatMode = repeatMode,
                    onToggleLyrics = { showLyrics = !showLyrics },
                    onToggleQueue = { queueOpen = !queueOpen },
                    onCloseQueue = { queueOpen = false },
                    onAirPlay = { openAudio(ctx) },
                    onShuffle = { shuffleOn = !shuffleOn; tracker.setShuffle(shuffleOn) },
                    onRepeat = { repeatMode = SessionCaps.nextRepeat(repeatMode); tracker.setRepeat(repeatMode) },
                    onCycleAuto = onCycleAuto, tracker = tracker, light = light,
                )
            }
        }
    }
}

/** AirPlay: abre la pantalla Audio de Hogar (la app principal lee el extra "open_audio"). */
private fun openAudio(ctx: Context) {
    runCatching { ctx.startActivity(Intent().setClassName(ctx.packageName, "live.hogar.app.MainActivity").putExtra("open_audio", true)) }
}

// ------------------------------------------------------------------ fondo

/** Mesh animado: un solo bitmap pequeño estirado con filtro bilineal. Solo se invalida el dibujo. */
@Composable
private fun MeshBackground(engine: MeshEngine) {
    Box(
        Modifier.fillMaxSize().drawBehind {
            engine.frame.intValue // lectura que invalida el dibujo en cada cuadro nuevo
            drawImage(engine.image, dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.Low)
        },
    )
}

// ------------------------------------------------------------------ contenido

/** Carátula: 420 dp, centrada sin letra y a la izquierda con letra o cola (el título cabe debajo). */
private val ArtMax = 420.dp
private val ColumnGap = 44.dp
private val CenterChromeWidth = 640.dp
/** Alto reservado para título + artista, y para el aviso de luz (la geometría los trata como cajas fijas). */
private val TitleH = 68.dp
private val ToastH = 64.dp

/**
 * Geometría de los controles entre dos posiciones (0 = centrados bajo la carátula, 1 = bajo la
 * columna derecha). Pura: se evalúa en la fase de medida, sin recomponer.
 */
internal class ChromeGeom(val centerW: Float, val centerX: Float, val sideW: Float, val sideX: Float) {
    fun width(p: Float) = centerW + (sideW - centerW) * p.coerceIn(0f, 1f)
    fun x(p: Float) = centerX + (sideX - centerX) * p.coerceIn(0f, 1f)

    companion object {
        fun of(fullPx: Float, centerWPx: Float, sideXPx: Float, safeHPx: Float) =
            ChromeGeom(centerWPx, (fullPx - centerWPx) / 2f, fullPx - sideXPx - safeHPx, sideXPx)

        /** Desplazamiento horizontal que centra una columna de ancho [artPx] que empieza en [startPx]. */
        fun centerShift(fullPx: Float, startPx: Float, artPx: Float) = fullPx / 2f - (startPx + artPx / 2f)
    }
}

@Composable
private fun Content(
    track: TrackInfo,
    st: NowPlayingState,
    prepared: PreparedArt?,
    view: LyricsView,
    currentIndex: Int,
    positionMs: MutableLongState,
    nowMs: () -> Long,
    engine: MeshEngine,
    lyricsOn: Boolean,
    queueOpen: Boolean,
    showLyrics: Boolean,
    hasLyrics: Boolean,
    autoMode: Int,
    plan: NpFocus,
    canFocusChrome: () -> Boolean,
    light: LightEngine,
    chromeAlpha: () -> Float,
    shuffleOn: Boolean,
    repeatMode: Int,
    onToggleLyrics: () -> Unit,
    onToggleQueue: () -> Unit,
    onCloseQueue: () -> Unit,
    onAirPlay: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onCycleAuto: () -> Unit,
    tracker: MediaTracker,
) {
    val side = lyricsOn || queueOpen

    // Un resorte `.spring` de Apple mueve todo el diseño; se lee solo en las fases de capa y de medida.
    val mode = remember { Animatable(if (side) 1f else 0f) }
    val lyricsP = remember { Animatable(if (lyricsOn) 1f else 0f) }
    val queueP = remember { Animatable(if (queueOpen) 1f else 0f) }
    var lyricsComposed by remember { mutableStateOf(lyricsOn) }
    var queueComposed by remember { mutableStateOf(queueOpen) }
    LaunchedEffect(side) { mode.animateTo(if (side) 1f else 0f, Hogar.Motion.spring()) }
    LaunchedEffect(lyricsOn) {
        if (lyricsOn) lyricsComposed = true
        lyricsP.animateTo(if (lyricsOn) 1f else 0f, Hogar.Motion.spring())
        if (!lyricsOn) lyricsComposed = false
    }
    LaunchedEffect(queueOpen) {
        if (queueOpen) queueComposed = true
        queueP.animateTo(if (queueOpen) 1f else 0f, Hogar.Motion.spring())
        if (!queueOpen) queueComposed = false
    }

    // Aviso de la luz: ocupa sitio propio sobre los controles (la pila inferior), nunca se superpone.
    val toastP = remember { Animatable(0f) }
    var toastActive by remember { mutableStateOf(false) }
    var toastText by remember { mutableStateOf("") }
    LaunchedEffect(light.toastId) {
        if (light.toastId == 0) return@LaunchedEffect
        toastText = light.toast ?: return@LaunchedEffect
        toastActive = true
        toastP.animateTo(1f, tween(220))
        delay(3_300)
        toastP.animateTo(0f, tween(260))
        toastActive = false
    }

    val d = LocalDensity.current
    val lin = remember(d.density) {
        with(d) {
            NpLayoutIn(
                width = 0f, height = 0f, safeH = Hogar.Space.SafeH.toPx(), safeV = Hogar.Space.SafeV.toPx(),
                titleH = TitleH.toPx(), ctrlH = 0f, toastH = ToastH.toPx(), artMax = ArtMax.toPx(),
                centerCtrlW = CenterChromeWidth.toPx(), gap = 8.dp.toPx(), colGap = ColumnGap.toPx(),
                startPad = (Hogar.Space.SafeH + 16.dp).toPx(),
            )
        }
    }
    val slidePx = with(d) { 56.dp.toPx() }
    val veilUp = with(d) { 48.dp.toPx() }
    val veilSide = with(d) { 70.dp.toPx() }
    // Alineación del título: centrado en el modo centrado, a la izquierda al pasar a columna.
    val centerFraction: () -> Float = { (1f - mode.value).coerceIn(0f, 1f) }

    Layout(
        modifier = Modifier.fillMaxSize(),
        content = {
            Box(Modifier.layoutId("art")) { ArtView(prepared) }
            Box(Modifier.layoutId("title").clipToBounds()) { TitleBlock(track, centerFraction) }
            if (lyricsComposed) {
                Box(Modifier.layoutId("lyrics").clipToBounds().drawWithCache {
                    // Plumilla de 40 dp al borde inferior: una línea que asoma se desvanece, no se corta.
                    val feather = 40.dp.toPx()
                    val fade = Brush.verticalGradient(
                        0f to Color.Transparent, 1f to Color.Black,
                        startY = (size.height - feather).coerceAtLeast(0f), endY = size.height,
                    )
                    onDrawWithContent { drawContent(); drawRect(fade, blendMode = BlendMode.DstOut) }
                }) {
                    when (view) {
                        is LyricsView.Synced -> SyncedLyrics(view.items, currentIndex, nowMs, engine, Modifier.fillMaxSize())
                        is LyricsView.Plain -> PlainLyrics(view.lines, Modifier.fillMaxSize())
                        LyricsView.None -> Unit
                    }
                }
            }
            if (queueComposed) {
                QueuePanel(
                    queue = st.queue, active = queueOpen,
                    canShuffle = SessionCaps.canShuffle(st.playback.actions),
                    canRepeat = SessionCaps.canRepeat(st.playback.actions),
                    shuffleOn = shuffleOn, repeatMode = repeatMode,
                    onSelect = tracker::skipToQueueItem, onShuffle = onShuffle, onRepeat = onRepeat, onClose = onCloseQueue,
                    modifier = Modifier.layoutId("queue"),
                )
            }
            // Velo oscuro detrás de los controles (brocha en caché; el alfa se aplica en la capa).
            Box(
                Modifier.layoutId("veil").drawWithCache {
                    val v = Brush.verticalGradient(0f to Color.Transparent, 0.5f to Color.Black.copy(alpha = 0.42f), 1f to Color.Black.copy(alpha = 0.72f))
                    val hz = Brush.horizontalGradient(0f to Color.Transparent, 0.18f to Color.Black, 0.82f to Color.Black, 1f to Color.Transparent)
                    onDrawBehind { drawRect(v); drawRect(hz, blendMode = BlendMode.DstIn) }
                },
            )
            Box(Modifier.layoutId("chrome")) {
                Chrome(
                    positionMs = positionMs, durationMs = track.durationMs,
                    playing = st.playback.playing, showLyrics = showLyrics && hasLyrics, queueOpen = queueOpen, badges = st.quality,
                    autoMode = autoMode, plan = plan, canFocus = canFocusChrome, tracker = tracker, light = light,
                    onToggleLyrics = onToggleLyrics, onToggleQueue = onToggleQueue, onAirPlay = onAirPlay, onCycleAuto = onCycleAuto,
                )
            }
            if (toastActive) {
                Box(Modifier.layoutId("toast").clipToBounds(), contentAlignment = Alignment.TopCenter) {
                    GlassSurface(Modifier.fillMaxWidth().requiredHeight(ToastH), shape = RoundedCornerShape(Hogar.Radius.Panel)) {
                        Box(Modifier.fillMaxSize().padding(horizontal = 22.dp), contentAlignment = Alignment.Center) {
                            HogarText(toastText, Hogar.Type.Caption, maxLines = 2)
                        }
                    }
                }
            }
        },
    ) { ms, c ->
        fun find(id: String) = ms.firstOrNull { it.layoutId == id }
        val w = c.maxWidth
        val h = c.maxHeight
        val p = mode.value.coerceIn(0f, 1f)
        val base = NpLayoutIn(
            w.toFloat(), h.toFloat(), lin.safeH, lin.safeV, lin.titleH, 0f, lin.toastH, lin.artMax,
            lin.centerCtrlW, lin.gap, lin.colGap, lin.startPad,
        )
        // Los controles se miden primero (su alto manda en el reparto del resto).
        val ctrlW = NpLayout.controlsWidth(base, p).roundToInt().coerceAtLeast(0)
        val chrome = find("chrome")!!.measure(Constraints(minWidth = ctrlW, maxWidth = ctrlW))
        val f = NpLayout.frame(
            NpLayoutIn(base.width, base.height, base.safeH, base.safeV, base.titleH, chrome.height.toFloat(), base.toastH,
                base.artMax, base.centerCtrlW, base.gap, base.colGap, base.startPad),
            p, chromeAlpha(), toastP.value,
        )
        fun fixed(r: NpRect) = Constraints.fixed(r.w.roundToInt().coerceAtLeast(0), r.h.roundToInt().coerceAtLeast(0))
        val chromeA = chromeAlpha().coerceIn(0f, 1f)
        val clear = f.sideClear
        val art = find("art")!!.measure(fixed(f.art))
        val title = find("title")!!.measure(fixed(f.title))
        val lyr = find("lyrics")?.measure(fixed(f.side))
        val que = find("queue")?.measure(fixed(f.side))
        val vTop = (f.ctrl.t - veilUp).coerceAtLeast(0f)
        val veil = find("veil")!!.measure(
            Constraints.fixed((f.ctrl.w + 2 * veilSide).roundToInt().coerceAtLeast(0), (h - vTop).roundToInt().coerceAtLeast(0)),
        )
        val toast = find("toast")?.measure(fixed(f.toast))
        layout(w, h) {
            art.place(f.art.l.roundToInt(), f.art.t.roundToInt())
            title.place(f.title.l.roundToInt(), f.title.t.roundToInt())
            lyr?.placeWithLayer(f.side.l.roundToInt(), f.side.t.roundToInt()) {
                alpha = lyricsP.value.coerceIn(0f, 1f) * clear
                translationX = (1f - lyricsP.value) * slidePx
                compositingStrategy = CompositingStrategy.Offscreen
            }
            que?.placeWithLayer(f.side.l.roundToInt(), f.side.t.roundToInt()) {
                alpha = queueP.value.coerceIn(0f, 1f) * clear
                translationX = (1f - queueP.value) * slidePx * 2f
            }
            veil.placeWithLayer((f.ctrl.l - veilSide).roundToInt(), vTop.roundToInt()) {
                alpha = chromeA; compositingStrategy = CompositingStrategy.Offscreen
            }
            chrome.placeWithLayer(f.ctrl.l.roundToInt(), f.ctrl.t.roundToInt()) { alpha = chromeA }
            toast?.placeWithLayer(f.toast.l.roundToInt(), f.toast.t.roundToInt()) { alpha = toastP.value.coerceIn(0f, 1f) }
        }
    }
}

/** Título y artista: una línea cada uno; si no caben, se desplazan despacio (pausa, avance, pausa, reinicio). */
@Composable
private fun TitleBlock(track: TrackInfo, centerFraction: () -> Float) {
    Column(Modifier.fillMaxSize()) {
        MarqueeText(track.title, Hogar.Type.LargeTitle.copy(fontSize = 26.sp), centerFraction)
        if (track.artist.isNotBlank()) {
            MarqueeText(
                track.artist, Hogar.Type.Headline.copy(fontSize = 18.sp, fontWeight = FontWeight.Medium, color = Hogar.Color.TextSecondary),
                centerFraction, Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * Una línea de texto sin recortes con "…": medida una vez (en caché por texto y estilo); el desplazamiento
 * vive solo en la fase de dibujo (el reloj es un estado leído al dibujar, sin recomponer) y solo corre
 * mientras el texto desborda su caja.
 */
@Composable
private fun MarqueeText(text: String, style: TextStyle, centerFraction: () -> Float, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val layout = remember(text, style) { measurer.measure(text, style, softWrap = false, maxLines = 1, constraints = Constraints()) }
    val d = LocalDensity.current
    val clock = remember { mutableFloatStateOf(0f) }
    val boxW = remember { floatArrayOf(0f) }
    val textW = layout.size.width.toFloat()
    val speed = with(d) { MarqueeMath.SpeedDpPerS.dp.toPx() }
    LaunchedEffect(text, style, textW) {
        clock.floatValue = 0f
        var t0 = -1L
        while (isActive) {
            if (textW <= boxW[0] + 0.5f) { t0 = -1L; clock.floatValue = 0f; delay(300); continue }
            withFrameNanos { n ->
                if (t0 < 0) t0 = n
                clock.floatValue = (n - t0) / 1_000_000_000f
            }
        }
    }
    Box(
        modifier.fillMaxWidth().height(with(d) { layout.size.height.toDp() }).graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
                val feather = 14.dp.toPx()
                val over = textW - size.width
                val mask = Brush.horizontalGradient(
                    0f to Color.Transparent, (feather / size.width).coerceIn(0f, 0.5f) to Color.Black,
                    1f - (feather / size.width).coerceIn(0f, 0.5f) to Color.Black, 1f to Color.Transparent,
                )
                boxW[0] = size.width
                onDrawWithContent {
                    if (over > 0.5f) {
                        val off = MarqueeMath.offset(clock.floatValue, over + feather, speed)
                        drawText(layout, topLeft = Offset(-off, 0f))
                        drawRect(mask, blendMode = BlendMode.DstIn)
                    } else {
                        drawText(layout, topLeft = Offset((size.width - textW) * centerFraction(), 0f))
                    }
                }
            },
    )
}

/** Carátula cuadrada a lo que mida su caja (la sombra horneada se desborda alrededor sin ocupar sitio). */
@Composable
private fun ArtView(p: PreparedArt?) {
    Box(Modifier.fillMaxSize()) {
        Crossfade(targetState = p, animationSpec = tween(500), label = "art") { p ->
            if (p != null && p.side > 0) {
                Box(
                    Modifier.fillMaxSize().drawBehind {
                        val sidePx = size.width.toInt()
                        val scale = sidePx.toFloat() / p.side
                        val padPx = (p.pad * scale).toInt()
                        val total = sidePx + 2 * padPx
                        drawImage(p.shadow, dstOffset = IntOffset(-padPx, -padPx), dstSize = IntSize(total, total), filterQuality = FilterQuality.Low)
                        drawImage(p.art, dstOffset = IntOffset.Zero, dstSize = IntSize(sidePx, sidePx), filterQuality = FilterQuality.Medium)
                    },
                )
            }
        }
    }
}

// ------------------------------------------------------------------ controles

/**
 * Controles al estilo del reproductor de pantalla completa de Apple Music: barra fina con el
 * tiempo transcurrido a la izquierda, el restante a la derecha y la insignia de calidad en
 * medio; debajo, anterior / pausa / siguiente y, en una fila propia, TV, letra, cola, AirPlay e
 * iluminación. Misma estructura (y alto) en todos los modos: así el reparto del resto es exacto.
 */
@Composable
private fun Chrome(
    positionMs: MutableLongState,
    durationMs: Long,
    playing: Boolean,
    showLyrics: Boolean,
    queueOpen: Boolean,
    badges: AudioBadges,
    autoMode: Int,
    plan: NpFocus,
    canFocus: () -> Boolean,
    tracker: MediaTracker,
    light: LightEngine,
    onToggleLyrics: () -> Unit,
    onToggleQueue: () -> Unit,
    onAirPlay: () -> Unit,
    onCycleAuto: () -> Unit,
) {
    SideEffect { plan.wide = false }
    Column(Modifier.fillMaxWidth()) {
        ProgressBar(positionMs, durationMs)
        TimeRow(positionMs, durationMs, badges)
        Box(Modifier.fillMaxWidth().padding(top = Hogar.Space.S), contentAlignment = Alignment.Center) { Transport(playing, plan, canFocus, tracker) }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(
                HogarIcons.Tv, onCycleAuto, Modifier.npNode(plan, NpFocusMap.TV, canFocus),
                size = 40.dp, iconSize = 20.dp, selected = autoMode != AutoModePolicy.NEVER,
            )
            Spacer(Modifier.width(12.dp))
            SmallButtons(showLyrics, queueOpen, light, plan, canFocus, onToggleLyrics, onToggleQueue, onAirPlay)
        }
    }
}

@Composable
private fun Transport(playing: Boolean, plan: NpFocus, canFocus: () -> Boolean, tracker: MediaTracker) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        GlassIconButton(HogarIcons.Back, tracker::previous, Modifier.npNode(plan, NpFocusMap.PREV, canFocus), size = 54.dp, iconSize = 24.dp)
        GlassIconButton(
            if (playing) HogarIcons.Pause else HogarIcons.Play, tracker::playPause,
            Modifier.npNode(plan, NpFocusMap.PLAY, canFocus), size = 68.dp, iconSize = 32.dp,
        )
        GlassIconButton(HogarIcons.Forward, tracker::next, Modifier.npNode(plan, NpFocusMap.NEXT, canFocus), size = 54.dp, iconSize = 24.dp)
    }
}

@Composable
private fun SmallButtons(
    showLyrics: Boolean, queueOpen: Boolean, light: LightEngine, plan: NpFocus, canFocus: () -> Boolean,
    onLyrics: () -> Unit, onQueue: () -> Unit, onAirPlay: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        GlassIconButton(NpIcons.Lyrics, onLyrics, Modifier.npNode(plan, NpFocusMap.LYRICS, canFocus), size = 40.dp, iconSize = 20.dp, selected = showLyrics)
        GlassIconButton(NpIcons.Queue, onQueue, Modifier.npNode(plan, NpFocusMap.QUEUE, canFocus), size = 40.dp, iconSize = 20.dp, selected = queueOpen)
        GlassIconButton(NpIcons.AirPlayAudio, onAirPlay, Modifier.npNode(plan, NpFocusMap.AIRPLAY, canFocus), size = 40.dp, iconSize = 20.dp)
        LightButton(light, Modifier.npNode(plan, NpFocusMap.LIGHT, canFocus))
    }
}

/** Iluminación Govee. Pulsar: Apagado → Paleta → Paleta + ritmo. Mantener pulsado: "Prueba de tramos" (arcoíris 4 s; repetir cambia de método). */
@Composable
private fun LightButton(light: LightEngine, node: Modifier) {
    val suppressClick = remember { booleanArrayOf(false) }
    GlassIconButton(
        NpIcons.Lamp,
        onClick = { if (suppressClick[0]) suppressClick[0] = false else light.cycle() },
        modifier = node.onPreviewKeyEvent { e ->
            val k = e.key
            val center = k == Key.DirectionCenter || k == Key.Enter || k == Key.NumPadEnter
            if (center && e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount >= 1) {
                if (e.nativeKeyEvent.repeatCount == 1) { suppressClick[0] = true; light.testSegments() }
                true
            } else false
        },
        size = 40.dp, iconSize = 20.dp, selected = light.mode != 0,
    )
}

@Composable
private fun ProgressBar(positionMs: MutableLongState, durationMs: Long, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().height(4.dp).drawBehind {
            val r = CornerRadius(size.height / 2f)
            drawRoundRect(Color.White.copy(alpha = 0.25f), size = size, cornerRadius = r)
            val f = PlaybackMath.fraction(positionMs.longValue, durationMs)
            if (f > 0f) drawRoundRect(Color.White.copy(alpha = 0.92f), size = Size(max(size.height, size.width * f), size.height), cornerRadius = r)
        },
    )
}

/** Transcurrido a la izquierda, restante a la derecha y, entre ambos, la insignia de calidad (solo si hay evidencia). */
@Composable
private fun TimeRow(positionMs: MutableLongState, durationMs: Long, badges: AudioBadges) {
    val elapsed by remember { derivedStateOf { PlaybackMath.clock(positionMs.longValue) } }
    val remaining by remember(durationMs) {
        derivedStateOf { if (durationMs > 0) "-" + PlaybackMath.clock(durationMs - positionMs.longValue) else "" }
    }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        HogarText(elapsed, Hogar.Type.Caption)
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (badges.any) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (badges.lossless) QualityBadge("Lossless", NpIcons.Waveform)
                    if (badges.atmos) QualityBadge("Dolby Atmos", null)
                }
            }
        }
        HogarText(remaining, Hogar.Type.Caption)
    }
}

/** Insignia de calidad: rectángulo suave con glifo opcional y rótulo semibold en la fuente Text. */
@Composable
private fun QualityBadge(label: String, glyph: androidx.compose.ui.graphics.vector.ImageVector?) {
    Row(
        Modifier.drawBehind {
            drawRoundRect(Color.White.copy(alpha = 0.16f), size = size, cornerRadius = CornerRadius(5.dp.toPx()))
        }.padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (glyph != null) HogarIcon(glyph, Hogar.Color.TextSecondary, size = 14.dp)
        HogarText(label, Hogar.Type.Label.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Hogar.Color.TextSecondary, letterSpacing = 0.sp), maxLines = 1)
    }
}

// ------------------------------------------------------------------ letra

private val LyricStyle = TextStyle(fontSize = 44.sp, lineHeight = 52.sp, fontWeight = FontWeight.Bold, color = Color.White, letterSpacing = (-0.5).sp)
private val LinePad = 12.dp
private const val ANCHOR = 0.35f
private const val ALPHA_UPCOMING = 0.42f
private const val ALPHA_PAST = 0.28f
private const val FEATHER_PX = 36f

@Composable
private fun PlainLyrics(lines: List<String>, modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState())) {
        for (l in lines) {
            HogarText(l.ifBlank { " " }, Hogar.Type.Title.copy(fontSize = 28.sp, color = Hogar.Color.Text.copy(alpha = 0.85f)), Modifier.padding(vertical = 5.dp))
        }
    }
}

/**
 * Letra sincronizada. Las líneas se miden una vez; cambiar de línea es UNA animación con el resorte
 * `.spring` de Apple sobre la traslación de la capa (fase de dibujo: no se recompone ni se vuelve a
 * medir). Cada línea anima su propio brillo solo cuando cambia su estado (pasada / vigente / próxima).
 */
@Composable
private fun SyncedLyrics(items: List<LyricItem>, current: Int, nowMs: () -> Long, engine: MeshEngine, modifier: Modifier) {
    val scroll = remember(items) { Animatable(0f) }
    val tops = remember(items) { IntArray(items.size) }
    val heights = remember(items) { IntArray(items.size) }
    val first = remember(items) { booleanArrayOf(true) }
    var viewportH by remember { mutableIntStateOf(0) }

    LaunchedEffect(items, current, viewportH) {
        if (viewportH <= 0) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { } // ya se midió: tops/heights están al día
        val i = current.coerceIn(0, items.lastIndex)
        val target = (tops[i] + heights[i] / 2f - viewportH * ANCHOR).coerceAtLeast(0f)
        if (first[0]) { first[0] = false; scroll.snapTo(target) } else scroll.animateTo(target, Hogar.Motion.spring())
    }

    Box(modifier.clipToBounds().onSizeChanged { viewportH = it.height }) {
        Layout(
            content = {
                items.forEachIndexed { i, item ->
                    key(i) {
                        val rel = if (i == current) 0 else if (i < current) -1 else 1
                        if (item.gap) GapDots(item, rel, nowMs, engine) else LyricLine(item, rel, nowMs, engine.frame)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true)
                .graphicsLayer { translationY = -scroll.value },
        ) { measurables, c ->
            val cc = Constraints(maxWidth = c.maxWidth)
            var y = 0
            val placed = measurables.mapIndexed { i, m ->
                val p = m.measure(cc)
                tops[i] = y; heights[i] = p.height
                y += p.height
                p
            }
            // Cola vacía: la última línea puede llegar al ancla.
            val total = y + (viewportH * (1f - ANCHOR)).toInt()
            layout(c.maxWidth, total) {
                var yy = 0
                for (p in placed) { p.place(0, yy); yy += p.height }
            }
        }
    }
}

/** Línea de letra. Texto medido una vez; estado (brillo) y barrido de palabras en la fase de dibujo. */
@Composable
private fun LyricLine(item: LyricItem, rel: Int, nowMs: () -> Long, frame: State<Int>) {
    val measurer = rememberTextMeasurer()
    val holder = remember(item) { arrayOfNulls<TextLayoutResult>(1) }
    val starts = remember(item) {
        IntArray(item.words.size).also { var o = 0; item.words.forEachIndexed { i, w -> it[i] = o; o += w.text.length } }
    }
    val sweeping = item.words.isNotEmpty() && item.words.sumOf { it.text.length } == item.text.length
    val bright by animateFloatAsState(if (rel == 0) 1f else 0f, Hogar.Motion.smooth(), label = "bright")
    val base by animateFloatAsState(if (rel < 0) ALPHA_PAST else ALPHA_UPCOMING, Hogar.Motion.smooth(), label = "base")
    // Layout propio (no `Modifier.layout` sin medir un hijo): el nodo se mide a sí mismo, así la capa y el
    // dibujo que van encima reciben tamaño y se colocan. Un `.layout{}` que no mide al hijo deja
    // sin medir ni colocar todo lo que le sigue en la cadena (graphicsLayer, drawBehind): línea invisible.
    Layout(
        content = {},
        modifier = Modifier
            .graphicsLayer {
                val s = 0.95f + 0.05f * bright
                scaleX = s; scaleY = s
                transformOrigin = TransformOrigin(0f, 0.5f)
            }
            .drawBehind {
                val r = holder[0] ?: return@drawBehind
                val tl = Offset(0f, LinePad.toPx())
                val b = bright
                val a = base
                if (!sweeping) {
                    drawText(r, Color.White, tl, alpha = a + (1f - a) * b)
                    return@drawBehind
                }
                drawText(r, Color.White, tl, alpha = a)
                if (b <= 0.01f) return@drawBehind
                val pos = if (rel == 0) { frame.value; WordSweep.position(item.words, item.endMs, nowMs()) } else item.words.size.toFloat()
                drawSweep(r, item.words, starts, pos, tl, b)
            },
    ) { _, c ->
        val w = (c.maxWidth * 0.96f).toInt()
        val r = measurer.measure(item.text, LyricStyle, constraints = Constraints(maxWidth = w))
        holder[0] = r
        layout(c.maxWidth, r.size.height + 2 * LinePad.roundToPx()) {}
    }
}

/** Capa brillante del barrido: filas ya cantadas llenas, la fila actual hasta la palabra con borde suave. */
private fun DrawScope.drawSweep(r: TextLayoutResult, words: List<LyricWord>, starts: IntArray, pos: Float, tl: Offset, alpha: Float) {
    val n = words.size
    if (pos >= n) { drawText(r, Color.White, tl, alpha = alpha); return }
    val k = pos.toInt().coerceIn(0, n - 1)
    val f = pos - k
    val s = starts[k]
    val e = (s + words[k].text.trimEnd().length - 1).coerceAtLeast(s)
    val row = r.getLineForOffset(s)
    val x0 = r.getBoundingBox(s).left
    val x1 = if (r.getLineForOffset(e) == row) r.getBoundingBox(e).right else r.getLineRight(row)
    val x = x0 + (x1 - x0) * f
    val rowTop = r.getLineTop(row)
    val rowBottom = r.getLineBottom(row)
    if (rowTop > 0f) {
        clipRect(left = 0f, top = tl.y, right = size.width, bottom = tl.y + rowTop) { drawText(r, Color.White, tl, alpha = alpha) }
    }
    if (x > 0f) {
        val edge = x + FEATHER_PX
        val brush = Brush.horizontalGradient(
            colorStops = arrayOf(0f to Color.White, (x / edge).coerceIn(0f, 1f) to Color.White, 1f to Color.Transparent),
            startX = 0f, endX = edge,
        )
        clipRect(left = 0f, top = tl.y + rowTop, right = edge, bottom = tl.y + rowBottom) { drawText(r, brush, tl, alpha = alpha) }
    }
}

/** Hueco instrumental: "• • •" que se llenan hasta la siguiente línea y laten con la energía del audio. */
@Composable
private fun GapDots(item: LyricItem, rel: Int, nowMs: () -> Long, engine: MeshEngine) {
    val shown by animateFloatAsState(if (rel == 0) 1f else 0f, Hogar.Motion.smooth(), label = "dots")
    Box(
        Modifier.fillMaxWidth().height(76.dp).graphicsLayer { alpha = shown }.drawBehind {
            if (shown <= 0.01f) return@drawBehind
            val live = rel == 0
            if (live) engine.frame.intValue
            val span = (item.endMs - item.startMs).coerceAtLeast(1L)
            val prog = if (live) ((nowMs() - item.startMs).toFloat() / span).coerceIn(0f, 1f) else 1f
            val pulse = if (live) 1f + 0.35f * engine.energy + 3f * engine.swell else 1f
            val r0 = 8.dp.toPx()
            val step = 34.dp.toPx()
            for (i in 0 until 3) {
                val fill = (prog * 3f - i).coerceIn(0f, 1f)
                drawCircle(Color.White.copy(alpha = 0.30f + 0.70f * fill), r0 * pulse * (0.85f + 0.15f * fill), Offset(r0 + 6.dp.toPx() + i * step, size.height / 2f))
            }
        },
    )
}

// ------------------------------------------------------------------ estados vacíos

@Composable
private fun BoxScope.Empty() {
    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HogarText("Nada se está reproduciendo", Hogar.Type.Title)
        HogarText("Pon música en Apple Music, Spotify o YouTube Music y vuelve aquí.", Hogar.Type.Body.copy(color = Hogar.Color.TextSecondary))
    }
}

@Composable
private fun BoxScope.AccessPrompt(onOpen: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { focus.requestFocus() } }
    GlassSurface(Modifier.align(Alignment.Center).width(560.dp), shape = RoundedCornerShape(Hogar.Radius.Panel)) {
        Column(Modifier.padding(Hogar.Space.L), verticalArrangement = Arrangement.spacedBy(Hogar.Space.S)) {
            HogarText("Falta un permiso", Hogar.Type.Title)
            HogarText(
                "Para mostrar lo que suena, Hogar necesita acceso a las sesiones de medios. " +
                    "Activa \"Hogar: reproduciendo ahora\" en Acceso a notificaciones.",
                Hogar.Type.Body.copy(color = Hogar.Color.TextSecondary),
            )
            GlassButton("Abrir ajustes", onOpen, Modifier.focusRequester(focus), HogarIcons.Gear)
        }
    }
}

/** Teclas multimedia (play/pausa, siguiente...): no cierran el salvapantallas. */
private fun isMediaKey(code: Int) = code in 85..90 || code == 126 || code == 127 || code == 130
