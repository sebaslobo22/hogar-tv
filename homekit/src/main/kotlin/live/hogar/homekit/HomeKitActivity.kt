package live.hogar.homekit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import live.hogar.design.FocusStyles
import live.hogar.design.FocusSurface
import live.hogar.design.GlassButton
import live.hogar.design.GlassSurface
import live.hogar.design.Hogar
import live.hogar.design.HogarGlow
import live.hogar.design.HogarIcons
import live.hogar.design.HogarText
import live.hogar.design.ScreenTitle

/**
 * Pantalla de HomeKit: estado, código de configuración, QR para la app Casa, controladores
 * emparejados y "Restablecer emparejamiento". Todo se alcanza con las flechas del mando.
 */
class HomeKitActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HomeKit.start(application)
        setContent { HomeKitScreen(onBack = ::finish) }
    }
}

@Composable
private fun HomeKitScreen(onBack: () -> Unit) {
    val st by HomeKit.status.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    BackHandler(onBack = { if (confirm) confirm = false else onBack() })
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    HogarGlow(Hogar.Color.GlowDefault)
    Row(
        Modifier.fillMaxSize().padding(horizontal = Hogar.Space.SafeH, vertical = Hogar.Space.SafeV),
        horizontalArrangement = Arrangement.spacedBy(Hogar.Space.L),
    ) {
        GlassSurface(Modifier.weight(1f).fillMaxHeight()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Hogar.Space.M),
                verticalArrangement = Arrangement.spacedBy(Hogar.Space.S),
            ) {
                ScreenTitle("HomeKit", HogarIcons.Home, subtitle = "Casa de Apple, Siri y automatizaciones")
                StatusLine(st)
                if (st.running && !st.paired) {
                    Column {
                        HogarText("Código de configuración", Hogar.Type.Caption)
                        HogarText(st.setupCode, Hogar.Type.LargeTitle.copy(fontSize = 56.sp, fontFamily = FontFamily.Monospace))
                    }
                    HogarText(
                        "En el iPhone: Casa > Añadir accesorio > escanea el QR o elige \"Hogar TV\" e introduce el código.",
                        Hogar.Type.Body.copy(fontSize = 16.sp),
                    )
                } else if (st.paired) {
                    HogarText(
                        "Ya está emparejado. Pídele a Siri: \"Apaga la tele\", \"Pon Netflix\", \"Sube el volumen\".",
                        Hogar.Type.Body.copy(fontSize = 16.sp),
                    )
                }
                if (st.controllers.isNotEmpty()) {
                    HogarText("Controladores emparejados (${st.controllers.size})", Hogar.Type.Caption)
                    st.controllers.take(6).forEach { id ->
                        FocusSurface(
                            Modifier.fillMaxWidth(), shape = RoundedCornerShape(Hogar.Radius.Row),
                            style = FocusStyles.Pill, onClick = null,
                        ) { c ->
                            HogarText(
                                id.take(8) + "…" + id.takeLast(4), Hogar.Type.Mono.copy(fontSize = 15.sp),
                                Modifier.padding(horizontal = 18.dp, vertical = 9.dp), color = { c.secondary() },
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (st.controllers.size > 6) HogarText("y ${st.controllers.size - 6} más", Hogar.Type.Caption)
                }
                val shape = RoundedCornerShape(Hogar.Radius.Row)
                GlassButton("Volver", onBack, Modifier.fillMaxWidth().focusRequester(first), HogarIcons.ArrowLeft, shape)
                GlassButton(
                    "Restablecer emparejamiento", { confirm = true }, Modifier.fillMaxWidth(), HogarIcons.Power, shape,
                    enabled = st.running,
                )
            }
        }
        if (st.running && !st.paired && st.qrPayload.isNotEmpty()) {
            GlassSurface(Modifier.width(360.dp).fillMaxHeight()) {
                Column(
                    Modifier.fillMaxSize().padding(Hogar.Space.M),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    QrBox(st.qrPayload)
                    HogarText("Escanéalo con la app Casa", Hogar.Type.Caption, Modifier.padding(top = Hogar.Space.S))
                }
            }
        }
    }
    if (confirm) ConfirmReset(onCancel = { confirm = false }, onConfirm = { confirm = false; HomeKit.resetPairing() })
}

@Composable
private fun StatusLine(st: HomeKitStatus) {
    val (text, color) = when {
        st.error != null -> "No arrancó: ${st.error}" to Hogar.Color.Negative
        !st.running -> "Iniciando…" to Hogar.Color.Warning
        !st.advertising -> "Servidor activo; buscando la red…" to Hogar.Color.Warning
        st.paired -> "Emparejado y visible en la red" to Hogar.Color.Positive
        else -> "Listo para emparejar" to Hogar.Color.Positive
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        HogarText(text, Hogar.Type.Callout)
    }
}

/** QR en negro sobre blanco con zona de silencio; los módulos se dibujan como rectángulos. */
@Composable
private fun QrBox(payload: String) {
    val matrix = remember(payload) { runCatching { Qr.encode(payload) }.getOrNull() }
    Box(Modifier.size(280.dp).drawWhite(), contentAlignment = Alignment.Center) {
        if (matrix == null) {
            HogarText("QR no disponible", Hogar.Type.Callout, color = { Color.Black })
        } else {
            Canvas(Modifier.size(248.dp)) {
                val n = matrix.size
                val cell = size.width / (n + 0f)
                for (y in 0 until n) for (x in 0 until n) if (matrix[y][x]) {
                    drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.6f, cell + 0.6f))
                }
            }
        }
    }
}

private fun Modifier.drawWhite(): Modifier = drawBehind {
    drawRoundRect(Color.White, cornerRadius = androidx.compose.ui.geometry.CornerRadius(18.dp.toPx()))
}

@Composable
private fun ConfirmReset(onCancel: () -> Unit, onConfirm: () -> Unit) {
    val cancel = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { cancel.requestFocus() } }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().drawBlack(), contentAlignment = Alignment.Center) {
            GlassSurface(Modifier.width(460.dp)) {
                Column(Modifier.padding(Hogar.Space.M), verticalArrangement = Arrangement.spacedBy(Hogar.Space.S)) {
                    HogarText("¿Restablecer emparejamiento?", Hogar.Type.Headline)
                    HogarText(
                        "Se desvincula la tele de Casa y se genera un código nuevo. Tendrás que volver a añadirla.",
                        Hogar.Type.Body.copy(fontSize = 16.sp),
                    )
                    val shape = RoundedCornerShape(Hogar.Radius.Row)
                    GlassButton("Cancelar", onCancel, Modifier.fillMaxWidth().focusRequester(cancel), HogarIcons.ArrowLeft, shape)
                    GlassButton("Restablecer", onConfirm, Modifier.fillMaxWidth(), HogarIcons.Power, shape)
                }
            }
        }
    }
}

private fun Modifier.drawBlack(): Modifier = drawBehind { drawRect(Color.Black.copy(alpha = 0.55f)) }
