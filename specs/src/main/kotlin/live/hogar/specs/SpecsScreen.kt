package live.hogar.specs

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import live.hogar.design.FocusStyles
import live.hogar.design.FocusSurface
import live.hogar.design.GlassSurface
import live.hogar.design.Hogar
import live.hogar.design.HogarGlow
import live.hogar.design.HogarIcon
import live.hogar.design.HogarIcons
import live.hogar.design.HogarText
import live.hogar.design.ScreenTitle

private enum class Tab(val title: String, val section: Section?, val icon: () -> ImageVector) {
    Resumen("Resumen", null, { HogarIcons.Info }),
    Imagen("Imagen", Section.Imagen, { HogarIcons.Tv }),
    Sonido("Sonido", Section.Sonido, { HogarIcons.Audio }),
    Equipo("Equipo", Section.Equipo, { HogarIcons.Gear }),
    Red("Red", Section.Red, { HogarIcons.Wifi }),
}

/** Fila de la lista de la derecha. `status == null` = texto informativo sin punto. */
private data class UiRow(val title: String, val status: Status?, val body: String, val technical: String? = null, val mono: Boolean = false)

private sealed interface Load {
    data object Loading : Load
    data class Done(val report: DeviceReport, val verdict: Verdict) : Load
    data class Failed(val message: String) : Load
}

@Composable
fun SpecsScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    var load by remember { mutableStateOf<Load>(Load.Loading) }
    var tab by remember { mutableStateOf(Tab.Resumen) }
    var technical by remember { mutableStateOf(false) }
    val firstPill = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        load = try {
            withContext(Dispatchers.Default) {
                val report = DeviceProbe.probe(context)
                Load.Done(report, VerdictEngine.evaluate(report))
            }
        } catch (t: Throwable) {
            Load.Failed(t.message ?: t.javaClass.simpleName)
        }
    }
    LaunchedEffect(Unit) { runCatching { firstPill.requestFocus() } }

    HogarGlow(Color(0xFF2F6BFF))
    Row(
        Modifier
            .fillMaxSize()
            .padding(horizontal = Hogar.Space.SafeH, vertical = Hogar.Space.SafeV),
        horizontalArrangement = Arrangement.spacedBy(Hogar.Space.L),
    ) {
        Column(Modifier.width(280.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(Hogar.Space.Xs)) {
            ScreenTitle("Este TV", HogarIcons.Tv, Modifier.padding(bottom = Hogar.Space.S))
            Tab.entries.forEachIndexed { i, t ->
                NavPill(
                    text = t.title,
                    icon = t.icon(),
                    selected = tab == t,
                    modifier = if (i == 0) Modifier.focusRequester(firstPill) else Modifier,
                    onFocus = { tab = t },
                    onClick = { tab = t },
                )
            }
            Box(Modifier.size(Hogar.Space.Xs))
            NavPill(
                text = if (technical) "Ocultar datos técnicos" else "Ver datos técnicos",
                icon = if (technical) HogarIcons.EyeSlash else HogarIcons.Eye,
                selected = technical,
                onFocus = {},
                onClick = { technical = !technical },
            )
        }

        GlassSurface(Modifier.weight(1f).fillMaxHeight()) {
            when (val l = load) {
                Load.Loading -> Centered("Analizando este TV…")
                is Load.Failed -> Centered("No se pudo analizar este TV: ${l.message}")
                is Load.Done -> {
                    val rows = remember(l, tab) { rowsFor(tab, l) }
                    val header = if (tab == Tab.Resumen) l.verdict.deviceTitle else tab.title
                    val sub = if (tab == Tab.Resumen) l.verdict.deviceSubtitle else null
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(Hogar.Space.M),
                        verticalArrangement = Arrangement.spacedBy(Hogar.Space.Xxs),
                    ) {
                        item(key = "h-$tab") {
                            Column(Modifier.padding(start = 6.dp, bottom = Hogar.Space.S)) {
                                HogarText(header, Hogar.Type.Title)
                                if (sub != null) HogarText(sub, Hogar.Type.Caption)
                            }
                        }
                        items(rows, key = { "${tab.name}-${it.title}" }) { row -> SpecRow(row, technical) }
                        if (tab == Tab.Resumen && technical) {
                            item(key = "dump") {
                                SpecRow(UiRow("Datos crudos del equipo", null, TechnicalDump.of(l.report), mono = true), true)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun rowsFor(tab: Tab, l: Load.Done): List<UiRow> {
    val v = l.verdict
    val section = tab.section
    if (section != null) {
        return v.of(section).map { UiRow(it.title, it.status, it.explanation, it.technical) }
    }
    val rows = mutableListOf<UiRow>()
    if (v.highlights.isNotEmpty()) {
        v.highlights.forEach { rows += UiRow(it, Status.Supported, "Lo mejor de este TV.") }
    } else {
        rows += UiRow("Sin funciones destacadas confirmadas", Status.Unknown, "No se pudo confirmar ninguna función clave. Revisa las secciones.")
    }
    if (v.limitations.isNotEmpty()) {
        v.limitations.forEach { rows += UiRow(it, Status.Unsupported, "Limitación importante.") }
    } else {
        rows += UiRow("Sin limitaciones importantes", Status.Supported, "No se detectaron faltas en las funciones clave.")
    }
    val counts = Status.entries.associateWith { s -> v.lines.count { it.status == s } }
    rows += UiRow(
        "Resumen general", null,
        "${counts[Status.Supported]} soportadas, ${counts[Status.Unsupported]} no soportadas y ${counts[Status.Unknown]} sin confirmar. " +
            "Android no informa el tipo de panel (OLED, LED o QLED) ni el tamaño en pulgadas.",
    )
    return rows
}

private fun statusColor(s: Status) = when (s) {
    Status.Supported -> Hogar.Color.Positive
    Status.Unsupported -> Hogar.Color.Negative
    Status.Unknown -> Hogar.Color.Warning
}

@Composable
private fun Centered(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        HogarText(text, Hogar.Type.Headline)
    }
}

/** Píldora de navegación de la columna izquierda (vidrio; blanca al enfocar). */
@Composable
private fun NavPill(
    text: String,
    icon: ImageVector,
    selected: Boolean,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusSurface(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Hogar.Radius.Pill),
        style = FocusStyles.Pill,
        selected = selected,
        onFocusChanged = { if (it) onFocus() },
        onClick = onClick,
    ) { c ->
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HogarIcon(icon, { c.primary() }, size = 22.dp)
            HogarText(text, Hogar.Type.Callout, color = { c.primary() }, maxLines = 1)
        }
    }
}

@Composable
private fun SpecRow(row: UiRow, technical: Boolean) {
    FocusSurface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Hogar.Radius.Row),
        style = FocusStyles.Row,
        glass = false,
    ) { c ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Hogar.Space.S, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(Hogar.Space.S),
        ) {
            if (row.status != null) {
                Box(Modifier.padding(top = 7.dp).size(12.dp).background(statusColor(row.status), CircleShape))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Hogar.Space.S), verticalAlignment = Alignment.CenterVertically) {
                    HogarText(row.title, Hogar.Type.Headline, Modifier.weight(1f, fill = false), color = { c.primary() })
                    if (row.status != null) {
                        HogarText(row.status.label, Hogar.Type.Caption, color = { c.secondary() })
                    }
                }
                HogarText(
                    row.body,
                    if (row.mono) Hogar.Type.Mono else Hogar.Type.Body.copy(fontSize = 17.sp),
                    color = { c.secondary() },
                )
                if (technical && row.technical != null) {
                    HogarText(row.technical, Hogar.Type.Mono, color = { c.tertiary() })
                }
            }
        }
    }
}
