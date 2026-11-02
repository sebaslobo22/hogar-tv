package live.hogar.launcher

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import live.hogar.design.GlassButton
import live.hogar.design.Hogar
import live.hogar.design.HogarText

/**
 * Android apaga los servicios de accesibilidad de una app cuando se "fuerza su detención". Sin el
 * servicio, el botón Inicio vuelve a Google TV: aquí se avisa y se lleva al ajuste para reactivarlo.
 */
@Composable
internal fun HomeButtonNotice(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        enabled = isHomeServiceEnabled(ctx)
        onPauseOrDispose { }
    }
    if (enabled) return
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(modifier.padding(bottom = 27.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        HogarText(
            "El botón Inicio está abriendo Google TV. Activa \"Hogar: botón Inicio\" en Accesibilidad.",
            Hogar.Type.Caption.copy(color = Hogar.Color.Text),
            Modifier.padding(bottom = 10.dp),
        )
        GlassButton(
            text = "Activar botón Inicio",
            onClick = {
                runCatching {
                    ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            },
            modifier = Modifier.focusRequester(focus),
        )
    }
}

internal fun isHomeServiceEnabled(ctx: Context): Boolean {
    val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
    val me = "${ctx.packageName}/${HomeButtonService::class.java.name}"
    return list.split(':').any { it.equals(me, ignoreCase = true) }
}
