package live.hogar.nowplaying

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import live.hogar.design.FocusStyles
import live.hogar.design.FocusSurface
import live.hogar.design.GlassIconButton
import live.hogar.design.GlassSurface
import live.hogar.design.Hogar
import live.hogar.design.HogarIcon
import live.hogar.design.HogarIcons
import live.hogar.design.HogarText
import kotlin.math.min

private val RowHeight = 58.dp
private val ThumbSlot = 46.dp

@OptIn(ExperimentalComposeUiApi::class)
/** Panel "A continuación": la cola de la sesión en una bandeja de vidrio. */
@Composable
internal fun QueuePanel(
    queue: QueueState,
    canShuffle: Boolean,
    canRepeat: Boolean,
    shuffleOn: Boolean,
    repeatMode: Int,
    onSelect: (Long) -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onClose: () -> Unit,
    active: Boolean,
    modifier: Modifier,
) {
    GlassSurface(modifier, shape = RoundedCornerShape(Hogar.Radius.Panel)) {
        // Grupo de foco cerrado: ninguna flecha escapa del panel (izquierda desde la lista lo cierra).
        Column(
            Modifier.fillMaxSize().padding(top = 16.dp).focusGroup()
                .focusProperties { exit = { FocusRequester.Cancel }; canFocus = active }
                .onFocusChanged { if (it.hasFocus) android.util.Log.d(FocusTag, "foco -> lista de cola") },
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    HogarText("A continuación", Hogar.Type.Headline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val sub = queue.title?.takeIf { it.isNotBlank() && !it.equals("A continuación", ignoreCase = true) }
                    if (sub != null) HogarText(sub, Hogar.Type.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (canShuffle) GlassIconButton(NpIcons.Shuffle, onShuffle, size = 36.dp, iconSize = 18.dp, selected = shuffleOn)
                if (canRepeat) GlassIconButton(if (repeatMode == 1) NpIcons.RepeatOne else NpIcons.Repeat, onRepeat, size = 36.dp, iconSize = 18.dp, selected = repeatMode != 0)
            }
            if (!queue.available) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    HogarText(
                        "Esta app no comparte su cola",
                        Hogar.Type.Body.copy(color = Hogar.Color.TextSecondary, textAlign = TextAlign.Center),
                    )
                }
            } else {
                val cur = queue.currentIndex.coerceAtLeast(0)
                val focus = remember { FocusRequester() }
                val state = rememberLazyListState(initialFirstVisibleItemIndex = (cur - 1).coerceAtLeast(0))
                // Solo mientras la cola sigue abierta: si se cierra antes, no roba el foco al botón Cola.
                LaunchedEffect(active) { if (active) { delay(140); if (active) runCatching { focus.requestFocus() } } }
                LazyColumn(
                    Modifier.fillMaxSize().onPreviewKeyEvent { e ->
                        when {
                            e.type != KeyEventType.KeyDown -> false
                            e.key == Key.DirectionLeft -> { onClose(); true }
                            e.key == Key.DirectionRight -> true
                            else -> false
                        }
                    },
                    state = state,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    itemsIndexed(queue.rows, key = { i, _ -> i }) { i, r ->
                        QueueRowView(r, { onSelect(r.id) }, if (i == cur) Modifier.focusRequester(focus) else Modifier)
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueRowView(r: QueueRow, onClick: () -> Unit, modifier: Modifier) {
    val ctx = LocalContext.current
    val slotPx = with(LocalDensity.current) { ThumbSlot.roundToPx() }
    val thumb = remember(r.iconUri, r.icon) { mutableStateOf((r.icon as? Bitmap)?.asImageBitmap()) }
    LaunchedEffect(r.iconUri, r.icon) {
        if (thumb.value == null && r.iconUri != null) thumb.value = withContext(Dispatchers.IO) { QueueArt.decode(ctx, r.iconUri, slotPx) }?.asImageBitmap()
    }
    FocusSurface(
        modifier.fillMaxWidth().height(RowHeight),
        shape = RoundedCornerShape(Hogar.Radius.Row), style = FocusStyles.Row, glass = false, selected = r.current, onClick = onClick,
    ) { c ->
        Row(Modifier.fillMaxSize().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Thumb(thumb, slotPx)
            Column(Modifier.weight(1f)) {
                HogarText(r.title.ifBlank { r.subtitle }, Hogar.Type.Callout, color = { c.primary() }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (r.subtitle.isNotBlank() && r.title.isNotBlank()) {
                    HogarText(r.subtitle, Hogar.Type.Caption.copy(fontSize = 13.sp), color = { c.secondary() }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (r.current) HogarIcon(HogarIcons.Volume, { c.primary() }, size = 18.dp)
        }
    }
}

/** Miniatura: jamás más grande que su tamaño nativo (si es pequeña se muestra pequeña, centrada en el hueco). */
@Composable
private fun Thumb(img: androidx.compose.runtime.State<ImageBitmap?>, slotPx: Int) {
    Box(
        Modifier.size(ThumbSlot).drawWithCache {
            val corner = 6.dp.toPx()
            onDrawBehind {
                val bmp = img.value
                if (bmp == null) {
                    drawRoundRect(Color.White.copy(alpha = 0.10f), size = Size(size.width, size.height), cornerRadius = CornerRadius(corner))
                    return@onDrawBehind
                }
                val srcSide = min(bmp.width, bmp.height)
                val side = QueueMapper.thumbSidePx(srcSide, slotPx)
                val off = (size.width - side) / 2f
                val path = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(off, off, off + side, off + side, CornerRadius(corner))) }
                clipPath(path) {
                    drawImage(
                        bmp, srcOffset = IntOffset((bmp.width - srcSide) / 2, (bmp.height - srcSide) / 2), srcSize = IntSize(srcSide, srcSide),
                        dstOffset = IntOffset(off.toInt(), off.toInt()), dstSize = IntSize(side, side),
                        filterQuality = if (side < srcSide) FilterQuality.Medium else FilterQuality.None,
                    )
                }
            }
        },
    )
}

/** Carga de miniaturas por URI (disco o red), submuestreadas a lo justo para el hueco. */
internal object QueueArt {
    fun decode(ctx: Context, uriStr: String, slotPx: Int): Bitmap? = runCatching {
        val uri = Uri.parse(uriStr)
        fun open() = if (uri.scheme == "http" || uri.scheme == "https") {
            (java.net.URL(uriStr).openConnection() as java.net.HttpURLConnection).apply { connectTimeout = 5000; readTimeout = 6000 }.inputStream
        } else ctx.contentResolver.openInputStream(uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return@runCatching null
        val opts = BitmapFactory.Options().apply { inSampleSize = ArtworkMath.sampleSize(bounds.outWidth, bounds.outHeight, slotPx) }
        open()?.use { BitmapFactory.decodeStream(it, null, opts) }
    }.getOrNull()
}
