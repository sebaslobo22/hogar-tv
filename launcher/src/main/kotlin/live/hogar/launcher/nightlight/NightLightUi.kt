package live.hogar.launcher.nightlight

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import live.hogar.design.FocusStyles
import live.hogar.design.FocusSurface
import live.hogar.design.GlassButton
import live.hogar.design.GlassIconButton
import live.hogar.design.GlassSurface
import live.hogar.design.Hogar
import live.hogar.design.HogarIcons
import live.hogar.design.HogarText
import java.time.LocalDate
import java.time.ZoneId

/** Hace que la UI se recomponga cuando cambian los ajustes (el servicio escribe en el mismo archivo). */
@Composable
private fun rememberNightConfig(sp: SharedPreferences): NightConfig {
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(sp) {
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> tick++ }
        sp.registerOnSharedPreferenceChangeListener(l)
        onDispose { sp.unregisterOnSharedPreferenceChangeListener(l) }
    }
    @Suppress("UNUSED_EXPRESSION") tick
    return NightLightStore.read(sp)
}

/**
 * Fila "Luz nocturna" del Centro de control: pulsar la fila abre los ajustes; el botón de
 * encendido la activa/apaga. Sin el servicio de accesibilidad explica qué falta y lleva al ajuste.
 */
@Composable
internal fun NightLightRow(
    serviceEnabled: Boolean,
    onOpenPanel: () -> Unit,
    modifier: Modifier = Modifier,
    rowModifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val sp = remember { NightLightStore.prefs(ctx) }
    val cfg = rememberNightConfig(sp)
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    val status = if (!serviceEnabled) "Activa \"Hogar: botón Inicio\" en Accesibilidad"
    else NightSchedule.statusText(cfg, now, ZoneId.systemDefault())
    val on = serviceEnabled && cfg.mode != NightMode.OFF
    val shape = RoundedCornerShape(Hogar.Radius.Row)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Hogar.Space.Xs), verticalAlignment = Alignment.CenterVertically) {
        FocusSurface(
            rowModifier.weight(1f), shape = shape, style = FocusStyles.Pill,
            onClick = { if (serviceEnabled) onOpenPanel() else openAccessibility(ctx) },
        ) { c ->
            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                HogarText("Luz nocturna", Hogar.Type.Label.copy(fontSize = 16.sp), color = { c.primary() }, maxLines = 1)
                HogarText(status, Hogar.Type.Caption, color = { c.secondary() }, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        GlassIconButton(
            HogarIcons.Power,
            { if (serviceEnabled) NightLightStore.toggle(sp) else openAccessibility(ctx) },
            Modifier, size = 48.dp, iconSize = 22.dp, selected = on,
        )
    }
}

private fun openAccessibility(ctx: Context) {
    runCatching {
        ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Fila enfocable que se ajusta con las flechas izquierda/derecha (y OK avanza). */
@Composable
private fun AdjustRow(
    label: String,
    value: String,
    onStep: (Int) -> Unit,
    modifier: Modifier = Modifier,
    fraction: (() -> Float)? = null,
) {
    FocusSurface(
        modifier.fillMaxWidth().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.key) {
                Key.DirectionLeft -> { onStep(-1); true }
                Key.DirectionRight -> { onStep(1); true }
                else -> false
            }
        },
        shape = RoundedCornerShape(Hogar.Radius.Row), style = FocusStyles.Row,
        onClick = { onStep(1) },
    ) { c ->
        Column(Modifier.padding(horizontal = 16.dp, vertical = 9.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                HogarText(label, Hogar.Type.Callout, color = { c.primary() }, maxLines = 1)
                HogarText("‹  $value  ›", Hogar.Type.Callout, color = { c.secondary() }, maxLines = 1)
            }
            if (fraction != null) {
                Box(
                    Modifier.padding(top = 7.dp).fillMaxWidth().height(6.dp).drawBehind {
                        val r = CornerRadius(size.height / 2)
                        drawRoundRect(c.tertiary().copy(alpha = 0.35f), size = size, cornerRadius = r)
                        drawRoundRect(c.primary(), size = Size(size.width * fraction().coerceIn(0f, 1f), size.height), cornerRadius = r)
                    },
                )
            }
        }
    }
}

/** Panel de ajustes (vidrio, centrado). Se abre desde el Centro de control con [open]. */
@Composable
internal fun NightLightPanel(open: Boolean, onClose: () -> Unit) {
    if (!open) return
    val ctx = LocalContext.current
    val sp = remember { NightLightStore.prefs(ctx) }
    val cfg = rememberNightConfig(sp)
    val first = remember { FocusRequester() }
    val zone = remember { ZoneId.systemDefault() }

    fun save(c: NightConfig) {
        NightLightStore.write(sp, c)
        NightLightStore.touchPreview(sp, true) // vista previa en vivo mientras se ajusta
    }

    LaunchedEffect(Unit) {
        NightLightStore.touchPreview(sp, true)
        delay(80); runCatching { first.requestFocus() }
    }
    DisposableEffect(Unit) { onDispose { NightLightStore.touchPreview(sp, false) } }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().drawBehind { drawRect(Color.Black.copy(alpha = 0.40f)) }, contentAlignment = Alignment.Center) {
            GlassSurface(Modifier.width(720.dp), shape = RoundedCornerShape(Hogar.Radius.Panel)) {
                Column(Modifier.padding(Hogar.Space.M), verticalArrangement = Arrangement.spacedBy(Hogar.Space.S)) {
                    HogarText("Luz nocturna", Hogar.Type.Title)
                    Row(horizontalArrangement = Arrangement.spacedBy(Hogar.Space.M)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            AdjustRow(
                                "Intensidad", "${cfg.intensity} %",
                                { d -> save(cfg.copy(intensity = stepClamp(cfg.intensity, d, 5, 0, 100))) },
                                Modifier.focusRequester(first), fraction = { cfg.intensity / 100f },
                            )
                            AdjustRow(
                                "Atenuar", "${cfg.dim} %",
                                { d -> save(cfg.copy(dim = stepClamp(cfg.dim, d, 5, 0, NightColor.MAX_DIM_PERCENT))) },
                                fraction = { cfg.dim / NightColor.MAX_DIM_PERCENT.toFloat() },
                            )
                            HogarText("Modo", Hogar.Type.Caption, Modifier.padding(start = 6.dp, top = 4.dp))
                            val shape = RoundedCornerShape(Hogar.Radius.Row)
                            NightMode.entries.forEach { m ->
                                GlassButton(
                                    m.label, { save(cfg.copy(mode = m)) }, Modifier.fillMaxWidth(),
                                    shape = shape, selected = cfg.mode == m,
                                    trailing = if (cfg.mode == m) HogarIcons.Check else null,
                                )
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            when (cfg.mode) {
                                NightMode.FIXED -> {
                                    AdjustRow("Empieza", NightSchedule.clock12(cfg.startMin), { d -> save(cfg.copy(startMin = cfg.startMin + 15 * d)) })
                                    AdjustRow("Termina", NightSchedule.clock12(cfg.endMin), { d -> save(cfg.copy(endMin = cfg.endMin + 15 * d)) })
                                }
                                NightMode.SUN -> {
                                    AdjustRow(
                                        "Ubicación", cfg.city.label,
                                        { d ->
                                            val all = NightCity.ALL
                                            val i = Math.floorMod(all.indexOfFirst { it.id == cfg.cityId } + d, all.size)
                                            save(cfg.copy(cityId = all[i].id))
                                        },
                                    )
                                    AdjustRow(
                                        "Ajuste", offsetText(cfg.offsetMin),
                                        { d -> save(cfg.copy(offsetMin = stepClamp(cfg.offsetMin, d, 5, -30, 30))) },
                                    )
                                    SunPreview(cfg, zone)
                                }
                                NightMode.ALWAYS -> HogarText("Filtro activo todo el tiempo.", Hogar.Type.Caption, Modifier.padding(start = 6.dp))
                                NightMode.OFF -> HogarText("La luz nocturna está apagada.", Hogar.Type.Caption, Modifier.padding(start = 6.dp))
                            }
                            HogarText(
                                "Flechas izquierda/derecha para ajustar.",
                                Hogar.Type.Caption.copy(color = Hogar.Color.TextTertiary), Modifier.padding(start = 6.dp, top = 6.dp),
                            )
                            GlassButton("Listo", onClose, Modifier.fillMaxWidth(), shape = RoundedCornerShape(Hogar.Radius.Row))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SunPreview(cfg: NightConfig, zone: ZoneId) {
    val today = remember { LocalDate.now(ZoneId.of(cfg.city.zoneId)) }
    val t = remember(cfg.cityId) { Solar.cityTimes(cfg.city, today) }
    fun fmt(m: Double?): String = if (m == null) "—" else NightSchedule.clock12(m.toInt())
    Column(Modifier.padding(start = 6.dp, top = 4.dp)) {
        HogarText("Hoy en ${cfg.city.label}", Hogar.Type.Caption)
        HogarText("Atardecer  ${fmt(t.sunsetMin)}", Hogar.Type.Callout)
        HogarText("Amanecer  ${fmt(t.sunriseMin)}", Hogar.Type.Callout)
    }
}

private fun offsetText(m: Int): String = when {
    m == 0 -> "Exacto"
    m > 0 -> "+$m min"
    else -> "$m min"
}

/** Sube/baja [v] en pasos de [step] dentro de [lo]..[hi] sin pasarse. */
internal fun stepClamp(v: Int, dir: Int, step: Int, lo: Int, hi: Int): Int = (v + dir * step).coerceIn(lo, hi)
