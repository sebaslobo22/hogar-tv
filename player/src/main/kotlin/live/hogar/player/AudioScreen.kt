package live.hogar.player

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import live.hogar.core.AudioOutputFactory
import live.hogar.core.OutputState
import live.hogar.core.Speaker
import live.hogar.core.SpeakerDiscovery
import live.hogar.design.FocusStyles
import live.hogar.design.FocusSurface
import live.hogar.design.GlassButton
import live.hogar.design.GlassIconButton
import live.hogar.design.GlassSurface
import live.hogar.design.Hogar
import live.hogar.design.HogarGlow
import live.hogar.design.HogarIcon
import live.hogar.design.HogarIcons
import live.hogar.design.HogarText
import live.hogar.design.ScreenTitle
import live.hogar.design.hogarFocus
import live.hogar.design.rememberFocusDriver
import live.hogar.player.bridge.BridgeHub
import live.hogar.player.bridge.BridgeSettings
import live.hogar.player.bridge.BridgeStatus

/** Pantalla "Audio": altavoces a la izquierda, "Reproduciendo" a la derecha. */
@Composable
fun AudioScreen(discovery: SpeakerDiscovery, outputs: AudioOutputFactory, onBack: () -> Unit) {
    val context = LocalContext.current
    val engine = remember { PlayerHub.engine(context) }
    val ui by engine.state.collectAsState()
    val folders by engine.folders.collectAsState()
    val speakers by discovery.speakers.collectAsState()
    val bridgeSettings by BridgeHub.settings.collectAsState()
    val bridgeStatus by BridgeHub.status.collectAsState()

    BackHandler(onBack = onBack)   // la reproducción sigue en el servicio
    DisposableEffect(discovery) {
        discovery.start()
        onDispose { discovery.stop() }
    }
    LaunchedEffect(outputs) { engine.attach(outputs) }
    LaunchedEffect(outputs, discovery) { BridgeHub.init(context, outputs, discovery) }   // idempotente

    // Avisos del servicio (Android 13+). Si el TV no muestra el diálogo, el audio igual suena.
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) runCatching { notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            engine.playFolder(uri)
        }
    }
    val captureConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == Activity.RESULT_OK && data != null) {
            PlayerService.startCapture(context, r.resultCode, data)
        } else {
            engine.postMessage("No se concedió el permiso para capturar el sonido")
        }
    }
    fun askCaptureConsent() {
        try {
            val mpm = context.getSystemService(MediaProjectionManager::class.java)
            captureConsent.launch(mpm.createScreenCaptureIntent())
        } catch (e: ActivityNotFoundException) {
            engine.postMessage("Este TV no permite capturar el sonido")
        }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) askCaptureConsent() else engine.postMessage("Hace falta el permiso de audio para capturar el sonido del TV")
    }

    HogarGlow(Color(0xFFB04DFF))
    Row(
        Modifier.fillMaxSize().padding(horizontal = Hogar.Space.SafeH, vertical = Hogar.Space.SafeV),
        horizontalArrangement = Arrangement.spacedBy(Hogar.Space.M),
    ) {
        SpeakerPanel(
            speakers = speakers,
            selectedIds = ui.selectedIds,
            outputState = ui.outputState,
            onToggle = engine::toggleSpeaker,
            modifier = Modifier.width(340.dp).fillMaxHeight(),
        )
        NowPlayingPanel(
            ui = ui,
            folders = folders,
            onSource = engine::setSource,
            onPickFolder = {
                try {
                    folderPicker.launch(null)
                } catch (e: ActivityNotFoundException) {
                    engine.postMessage("Este TV no tiene selector de archivos. Instala uno (por ejemplo, un explorador de archivos) y vuelve a intentarlo")
                }
            },
            onOpenFolder = { engine.playFolder(android.net.Uri.parse(it.uri)) },
            onPlayPause = engine::playPause,
            onNext = engine::next,
            onPrevious = engine::previous,
            onVolume = engine::setVolume,
            onStartCapture = {
                when {
                    ui.selectedIds.isEmpty() -> engine.postMessage("Elige un altavoz para escuchar")
                    context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> askCaptureConsent()
                    else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            onStopCapture = engine::stopCapture,
            speakers = speakers,
            bridge = bridgeSettings,
            bridgeStatus = bridgeStatus,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
}

// ------------------------------------------------------------------ altavoces

@Composable
private fun SpeakerPanel(
    speakers: List<Speaker>,
    selectedIds: Set<String>,
    outputState: OutputState,
    onToggle: (Speaker) -> Unit,
    modifier: Modifier,
) {
    GlassSurface(modifier) {
        Column(Modifier.fillMaxSize().padding(Hogar.Space.M)) {
            ScreenTitle("Audio", HogarIcons.AirPlay, subtitle = "Altavoces AirPlay")
            Spacer(Modifier.height(Hogar.Space.S))
            if (speakers.isEmpty()) {
                HogarText("Buscando altavoces en tu red…", Hogar.Type.Caption)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(Hogar.Space.Xxs)) {
                items(speakers, key = { it.id }) { sp ->
                    SpeakerRow(sp, sp.id in selectedIds, outputState) { onToggle(sp) }
                }
            }
        }
    }
}

@Composable
private fun SpeakerRow(speaker: Speaker, selected: Boolean, outputState: OutputState, onClick: () -> Unit) {
    FocusSurface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Hogar.Radius.Row),
        style = FocusStyles.Row,
        selected = selected,
        onClick = onClick,
    ) { c ->
        val (status, statusColor) = when {
            !selected -> (speaker.model ?: "AirPlay") to null
            outputState is OutputState.Connecting -> "Conectando…" to null
            outputState is OutputState.Streaming -> "Sonando" to Hogar.Color.Positive
            outputState is OutputState.Error -> outputState.message to Hogar.Color.Negative
            else -> "Seleccionado" to null
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Hogar.Space.S, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HogarIcon(HogarIcons.Audio, { c.secondary() }, size = 22.dp)
            Column(Modifier.weight(1f)) {
                HogarText(speaker.name, Hogar.Type.Callout, color = { c.primary() }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                HogarText(
                    status, Hogar.Type.Caption, color = { statusColor ?: c.secondary() },
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            if (selected) HogarIcon(HogarIcons.Check, { c.primary() }, size = 22.dp)
        }
    }
}

// ------------------------------------------------------------------ reproduciendo

@Composable
private fun NowPlayingPanel(
    ui: PlayerUiState,
    folders: List<SavedFolder>,
    onSource: (AudioSource) -> Unit,
    onPickFolder: () -> Unit,
    onOpenFolder: (SavedFolder) -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onVolume: (Float) -> Unit,
    onStartCapture: () -> Unit,
    onStopCapture: () -> Unit,
    speakers: List<Speaker>,
    bridge: BridgeSettings,
    bridgeStatus: BridgeStatus,
    modifier: Modifier,
) {
    GlassSurface(modifier) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Hogar.Space.M),
            verticalArrangement = Arrangement.spacedBy(Hogar.Space.S),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(Hogar.Space.Xs)) {
                GlassButton("Mi música", { onSource(AudioSource.Library) }, icon = HogarIcons.Music, selected = ui.source == AudioSource.Library)
                GlassButton("Sonido del TV", { onSource(AudioSource.TvSound) }, icon = HogarIcons.Tv, selected = ui.source == AudioSource.TvSound)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(Hogar.Space.M), verticalAlignment = Alignment.CenterVertically) {
                Cover(ui.cover, 190.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    HogarText(
                        ui.title ?: if (ui.source == AudioSource.Library) "Nada sonando" else "Sonido del TV",
                        Hogar.Type.Title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    ui.artist?.let { HogarText(it, Hogar.Type.Headline.copy(color = Hogar.Color.TextSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    ui.album?.let { HogarText(it, Hogar.Type.Body.copy(color = Hogar.Color.TextTertiary), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    Spacer(Modifier.height(4.dp))
                    QualityBadge(ui.quality)
                    if (ui.latencyMs > 0 && ui.outputState is OutputState.Streaming) {
                        HogarText("Retraso ≈ ${QualityText.latencySeconds(ui.latencyMs)} s", Hogar.Type.Caption)
                    }
                    ui.message?.let { HogarText(it, Hogar.Type.Caption.copy(color = Hogar.Color.Negative)) }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(Hogar.Space.M), verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(HogarIcons.Back, onPrevious, size = 54.dp, iconSize = 24.dp)
                GlassIconButton(if (ui.playing) HogarIcons.Pause else HogarIcons.Play, onPlayPause, size = 68.dp, iconSize = 30.dp)
                GlassIconButton(HogarIcons.Forward, onNext, size = 54.dp, iconSize = 24.dp)
            }

            VolumeBar(ui.volume, onVolume)

            if (ui.source == AudioSource.Library) {
                LibraryControls(folders, onPickFolder, onOpenFolder)
            } else {
                TvSoundControls(ui.capturing, onStartCapture, onStopCapture)
            }

            BridgeSection(speakers, bridge, bridgeStatus)
        }
    }
}

// ------------------------------------------------------------------ Desde iPhone/iPad/Mac (TV como puente)

@Composable
private fun BridgeSection(speakers: List<Speaker>, bridge: BridgeSettings, status: BridgeStatus) {
    Column(verticalArrangement = Arrangement.spacedBy(Hogar.Space.Xs)) {
        Spacer(Modifier.height(Hogar.Space.Xs))
        HogarText("Desde iPhone/iPad/Mac", Hogar.Type.Headline)
        HogarText(
            "Envía el audio a «TV Daewoo» desde AirPlay: suena en el TV y se reenvía, sin tocar, a los altavoces que elijas.",
            Hogar.Type.Caption,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Hogar.Space.Xs)) {
            GlassButton(
                if (bridge.enabled) "Reenviar a altavoces AirPlay: sí" else "Reenviar a altavoces AirPlay: no",
                { BridgeHub.setEnabled(!bridge.enabled) },
                icon = HogarIcons.AirPlay,
                selected = bridge.enabled,
            )
        }
        if (bridge.enabled) {
            if (speakers.isEmpty()) HogarText("Buscando altavoces en tu red…", Hogar.Type.Caption)
            Column(verticalArrangement = Arrangement.spacedBy(Hogar.Space.Xxs)) {
                speakers.forEach { sp ->
                    SpeakerRow(sp, sp.id in bridge.speakerIds, OutputState.Idle) { BridgeHub.toggleSpeaker(sp) }
                }
            }
            GlassButton(
                if (bridge.localEnabled) "Sonar también en el TV: sí" else "Sonar también en el TV: no",
                { BridgeHub.setLocalEnabled(!bridge.localEnabled) },
                icon = HogarIcons.Tv,
                selected = bridge.localEnabled,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Hogar.Space.Xs), verticalAlignment = Alignment.CenterVertically) {
                GlassButton("−10 ms", { BridgeHub.nudgeTrim(-10) })
                GlassButton("+10 ms", { BridgeHub.nudgeTrim(10) })
                HogarText("Ajuste fino del TV: ${if (bridge.trimMs > 0) "+" else ""}${bridge.trimMs} ms", Hogar.Type.Caption)
            }
            val (text, color) = bridgeText(bridge, status)
            HogarText(text, Hogar.Type.Caption.copy(color = color))
        }
    }
}

private fun bridgeText(bridge: BridgeSettings, st: BridgeStatus): Pair<String, Color> = when {
    bridge.speakerIds.isEmpty() -> "Elige al menos un altavoz" to Hogar.Color.TextSecondary
    else -> when (st.phase) {
        BridgeStatus.Phase.Synced -> "Sincronizado · retraso ≈ ${st.delayMs} ms" to Hogar.Color.Positive
        BridgeStatus.Phase.Syncing -> "Sincronizando… retraso ≈ ${st.delayMs} ms" to Hogar.Color.Warning
        BridgeStatus.Phase.LocalOnly -> (st.message ?: "Sin conexión con los altavoces") to Hogar.Color.Negative
        BridgeStatus.Phase.Waiting, BridgeStatus.Phase.Off ->
            (st.message ?: "Esperando audio desde el iPhone, iPad o Mac") to Hogar.Color.TextSecondary
    }
}

@Composable
private fun LibraryControls(folders: List<SavedFolder>, onPick: () -> Unit, onOpen: (SavedFolder) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Hogar.Space.Xs)) {
        HogarText(
            "FLAC, ALAC (m4a) y WAV desde un USB o una carpeta de red. A 44,1 kHz/16 bits el sonido llega sin tocar; con más calidad se convierte.",
            Hogar.Type.Caption,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Hogar.Space.Xs)) {
            GlassButton("Elegir carpeta…", onPick, icon = HogarIcons.Folder, selected = true)
            folders.take(3).forEach { f -> GlassButton(f.name, { onOpen(f) }, icon = HogarIcons.Music) }
        }
    }
}

@Composable
private fun TvSoundControls(capturing: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Hogar.Space.Xs)) {
        HogarText(
            "El sonido del TV pasa por el mezclador de Android: no es bit a bit. Netflix, Disney+ y otras apps protegidas no permiten capturar su audio.",
            Hogar.Type.Caption,
        )
        HogarText("El TV seguirá sonando a la vez: baja su volumen o cambia su salida.", Hogar.Type.Caption.copy(color = Hogar.Color.TextTertiary))
        if (capturing) GlassButton("Dejar de capturar", onStop, icon = HogarIcons.Stop)
        else GlassButton("Empezar a capturar", onStart, icon = HogarIcons.Play, selected = true)
    }
}

@Composable
private fun QualityBadge(q: Quality) {
    val text = QualityText.badge(q) ?: return
    val dot = if (q is Quality.BitPerfect) Hogar.Color.Positive else Hogar.Color.Warning
    Row(
        Modifier
            .clip(RoundedCornerShape(Hogar.Radius.Pill))
            .background(Hogar.Color.Glass)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
        HogarText(text, Hogar.Type.Caption.copy(color = Hogar.Color.Text))
    }
}

@Composable
private fun Cover(bitmap: Bitmap?, size: Dp) {
    val shape = RoundedCornerShape(Hogar.Radius.Card)
    Box(
        Modifier
            .size(size)
            .shadow(32.dp, shape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(Brush.linearGradient(listOf(Hogar.Color.Accent.copy(alpha = 0.55f), Hogar.Color.Glass))),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            val img = remember(bitmap) { bitmap.asImageBitmap() }
            Image(img, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            HogarIcon(HogarIcons.Music, Hogar.Color.Text.copy(alpha = 0.85f), size = size * 0.4f)
        }
    }
}

/** Deslizador de volumen: izquierda/derecha del mando suben o bajan de 5 en 5 %. */
@Composable
private fun VolumeBar(volume: Float, onChange: (Float) -> Unit) {
    val driver = rememberFocusDriver(FocusStyles.Pill)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Hogar.Space.S)) {
        HogarIcon(HogarIcons.Volume, Hogar.Color.TextSecondary, size = 22.dp)
        Box(
            Modifier
                .width(380.dp)
                .height(30.dp)
                .hogarFocus(driver, RoundedCornerShape(Hogar.Radius.Pill))
                .background(Hogar.Color.Glass)
                .onKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) false
                    else when (e.key) {
                        Key.DirectionLeft -> { onChange(volume - 0.05f); true }
                        Key.DirectionRight -> { onChange(volume + 0.05f); true }
                        else -> false
                    }
                }
                .focusable(),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(volume.coerceIn(0.02f, 1f))
                    .drawBehind {
                        val a = driver.amount.value.coerceIn(0f, 1f)
                        drawRect(androidx.compose.ui.graphics.lerp(Hogar.Color.TextSecondary, Hogar.Color.GlassFocused, a))
                    },
            )
        }
        HogarText("${(volume * 100).toInt()} %", Hogar.Type.Caption)
    }
}
