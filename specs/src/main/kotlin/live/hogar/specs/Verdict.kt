package live.hogar.specs

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToInt

enum class Status(val label: String) {
    Supported("Soportado"), Unsupported("No soportado"), Unknown("Desconocido")
}

enum class Section(val title: String) { Imagen("Imagen"), Sonido("Sonido"), Equipo("Equipo"), Red("Red") }

data class VerdictLine(
    val key: String,
    val section: Section,
    val title: String,
    val status: Status,
    val explanation: String,
    /** Valores crudos que sustentan el veredicto (para "Ver datos técnicos"). */
    val technical: String,
)

data class Verdict(
    val deviceTitle: String,
    val deviceSubtitle: String,
    val lines: List<VerdictLine>,
    /** Las 3 funciones más importantes que sí tiene. */
    val highlights: List<String>,
    /** Limitaciones importantes (máx. 3). */
    val limitations: List<String>,
) {
    fun of(section: Section) = lines.filter { it.section == section }
}

private const val NOT_REPORTED = "No disponible (Android no lo informa)"

object VerdictEngine {

    private val highlightOrder = listOf("dv", "hdr10plus", "hdr10", "res4k", "atmos", "earc", "hz120", "av1", "hires", "hevc")
    private val limitationKeys = listOf("dv", "hdr10", "res4k", "atmos", "widevine", "wifi_band", "hevc", "h264", "ram")

    fun evaluate(r: DeviceReport, today: LocalDate = LocalDate.now()): Verdict {
        val lines = buildList {
            addAll(imagen(r))
            addAll(sonido(r))
            addAll(equipo(r, today))
            addAll(red(r))
        }
        val byKey = lines.associateBy { it.key }
        val highlights = highlightOrder.mapNotNull { byKey[it] }
            .filter { it.status == Status.Supported }.take(3).map { it.title }
        val limitations = limitationKeys.mapNotNull { byKey[it] }
            .filter { it.status == Status.Unsupported }.take(3).map { "${it.title}: ${it.status.label.lowercase()}" }
        val d = r.device
        val title = listOf(d.manufacturer.replaceFirstChar { it.uppercase() }, d.model).filter { it.isNotBlank() }.joinToString(" ")
            .ifBlank { "Este TV" }
        return Verdict(title, "Android ${d.androidRelease} (API ${d.sdk})", lines, highlights, limitations)
    }

    // ----------------------------------------------------------------- Imagen

    private fun imagen(r: DeviceReport): List<VerdictLine> {
        val dsp = r.display
        val out = mutableListOf<VerdictLine>()
        fun add(key: String, title: String, status: Status, text: String, tech: String) {
            out += VerdictLine(key, Section.Imagen, title, status, text, tech)
        }

        // Resolución
        val best = dsp.modes.maxByOrNull { it.width * it.height } ?: dsp.currentMode
        val cur = dsp.currentMode
        val panel = dsp.panelSize
        val uiBelowPanel = panel != null && best != null && panel.width >= 3840 && best.width < panel.width
        if (uiBelowPanel) {
            add(
                "res4k", "Resolución 4K",
                Status.Supported,
                "Panel 4K (${panel!!.width}×${panel.height}). El video de las apps llega en 4K; la interfaz se " +
                    "dibuja en ${resName(best!!.width)} (${best.width}×${best.height}) porque el chip del TV no " +
                    "ofrece más a las apps, y el TV la escala.",
                "panel=${panel.width}x${panel.height} ui=${best.width}x${best.height}@${hz(best.refreshHz)}",
            )
        } else if (best == null) {
            add("res4k", "Resolución 4K", Status.Unknown, NOT_REPORTED, "modes=[]")
        } else {
            val is4k = best.width >= 3840 && best.height >= 2160 - 8
            val curTxt = cur?.let { " Ahora está en ${resName(it.width)} (${it.width}×${it.height})." }.orEmpty()
            add(
                "res4k", "Resolución 4K",
                if (is4k) Status.Supported else Status.Unsupported,
                if (is4k) "La pantalla acepta hasta ${resName(best.width)} (${best.width}×${best.height}).$curTxt" +
                    (if (cur != null && cur.width < 3840) " Se puede subir en los ajustes de pantalla del TV." else "")
                else "La pantalla informa como máximo ${resName(best.width)} (${best.width}×${best.height}).$curTxt",
                "current=${cur?.let { "${it.width}x${it.height}@${hz(it.refreshHz)}" }} modes=${dsp.modes.joinToString { "${it.width}x${it.height}@${hz(it.refreshHz)}" }}",
            )
        }

        // Frecuencia
        val maxHz = dsp.modes.maxOfOrNull { it.refreshHz }
        if (maxHz == null) {
            add("hz120", "Imagen a 120 Hz", Status.Unknown, NOT_REPORTED, "refresh=[]")
            add("hz24", "Cine a 24 cuadros (24p)", Status.Unknown, NOT_REPORTED, "refresh=[]")
        } else {
            val rates = dsp.modes.map { hz(it.refreshHz) }.distinct().sorted()
            val has120 = maxHz >= 119f
            add(
                "hz120", "Imagen a 120 Hz", if (has120) Status.Supported else Status.Unsupported,
                if (has120) "La pantalla ofrece hasta ${hz(maxHz)} Hz: movimientos muy fluidos, ideal para deportes y juegos."
                else "La pantalla informa hasta ${hz(maxHz)} Hz. Es suficiente para series, cine y deportes normales.",
                "refreshRates=${rates.joinToString()}",
            )
            val has24 = dsp.modes.any { m ->
                val k = m.refreshHz / 24f
                val k2 = m.refreshHz / 23.976f
                (abs(k - k.roundToInt()) < 0.01f * k && k.roundToInt() >= 1) ||
                    (abs(k2 - k2.roundToInt()) < 0.01f * k2 && k2.roundToInt() >= 1)
            }
            add(
                "hz24", "Cine a 24 cuadros (24p)", if (has24) Status.Supported else Status.Unsupported,
                if (has24) "La pantalla tiene un modo múltiplo de 24 Hz: el cine se ve sin tirones."
                else "No hay un modo de 24 Hz (ni múltiplo): las películas pueden verse con pequeños saltos.",
                "refreshRates=${rates.joinToString()}",
            )
        }

        // HDR
        val hdrKnown = dsp.hdrReported
        val sdr = if (hdrKnown) dsp.hdrTypes else emptySet()
        fun dispHas(t: HdrType): Boolean? = if (hdrKnown) t in sdr else null
        val vOk = r.video.scanned
        val hevc = r.video.of(VideoCodec.Hevc).flatMap { it.profiles }.toSet()
        val vp9 = r.video.of(VideoCodec.Vp9).flatMap { it.profiles }.toSet()
        val av1 = r.video.of(VideoCodec.Av1).flatMap { it.profiles }.toSet()
        val dvProfiles = r.video.of(VideoCodec.DolbyVision).flatMap { it.profiles }.toSet()
        val decoder10bit: Boolean? = if (!vOk) null else
            VideoProfile.Hevc10 in hevc || VideoProfile.Hevc10Hdr10 in hevc || VideoProfile.Vp9P2 in vp9 ||
                VideoProfile.Vp9P2Hdr in vp9 || VideoProfile.Av1Main10 in av1 || VideoProfile.Av1Main10Hdr10 in av1
        val hdrTech = "hdrTypes=${sdr.joinToString { it.label }} isHdr=${dsp.isHdr} screenHdr=${dsp.screenHdrConfig} hevc=$hevc vp9=$vp9 av1=$av1"

        fun hdrLine(key: String, title: String, type: HdrType, decoder: Boolean?, okText: String, noDisplay: String, noDecoder: String) {
            val d = dispHas(type)
            val status = when {
                d == true && decoder == true -> Status.Supported
                d == false || decoder == false -> Status.Unsupported
                else -> Status.Unknown
            }
            val text = when {
                d == null && decoder == null -> NOT_REPORTED
                status == Status.Supported -> okText
                d == false -> noDisplay
                decoder == false -> noDecoder
                else -> NOT_REPORTED
            }
            add(key, title, status, text, hdrTech)
        }

        hdrLine(
            "hdr10", "HDR10", HdrType.Hdr10, decoder10bit,
            "Soportado por la pantalla y por el decodificador. Netflix, Prime Video, Disney+ y YouTube podrán mostrar HDR.",
            "La pantalla no informa HDR10: las películas HDR se verán en imagen normal.",
            "La pantalla acepta HDR10 pero el decodificador no anuncia video de 10 bits: el HDR podría no funcionar.",
        )
        hdrLine(
            "hlg", "HLG", HdrType.Hlg, decoder10bit,
            "Soportado por la pantalla y el decodificador. Sirve para emisiones HDR de TV y YouTube.",
            "La pantalla no informa HLG.",
            "El decodificador no anuncia video de 10 bits.",
        )
        // HDR10+: Android no siempre anuncia el perfil aunque el TV lo maneje.
        val hdr10pDecoder: Boolean? = if (!vOk) null else
            VideoProfile.Hevc10Hdr10Plus in hevc || VideoProfile.Vp9P2Hdr10Plus in vp9 || VideoProfile.Av1Main10Hdr10Plus in av1
        val d10p = dispHas(HdrType.Hdr10Plus)
        val s10p = when {
            d10p == true && hdr10pDecoder == true -> Status.Supported
            d10p == false -> Status.Unsupported
            else -> Status.Unknown
        }
        add(
            "hdr10plus", "HDR10+", s10p,
            when {
                s10p == Status.Supported -> "Soportado por la pantalla y por el decodificador. Prime Video y otros servicios podrán usarlo."
                d10p == false -> "La pantalla no informa HDR10+. Verás HDR10 normal en esos contenidos."
                d10p == true -> "La pantalla lo informa, pero el decodificador no lo anuncia. Muchos TV lo manejan igual: no podemos confirmarlo."
                else -> NOT_REPORTED
            },
            hdrTech,
        )
        // Dolby Vision
        val dvDecoder: Boolean? = if (!vOk) null else r.video.of(VideoCodec.DolbyVision).isNotEmpty()
        val dDv = dispHas(HdrType.DolbyVision)
        val sDv = when {
            dDv == true && dvDecoder == true -> Status.Supported
            dDv == false || dvDecoder == false -> Status.Unsupported
            else -> Status.Unknown
        }
        val dvList = dvProfiles.map { it.name.removePrefix("Dv") }.sorted().joinToString(", ")
        add(
            "dv", "Dolby Vision", sDv,
            when {
                sDv == Status.Supported -> "Soportado por la pantalla y por el decodificador. Netflix y Disney+ podrán usarlo." +
                    if (dvList.isNotEmpty()) " Perfiles: $dvList." else ""
                dDv == false && dvDecoder == true -> "El decodificador sabe Dolby Vision, pero la pantalla no lo informa: se verá en HDR10 o normal."
                dDv == true && dvDecoder == false -> "La pantalla lo informa, pero falta el decodificador Dolby Vision: no se usará."
                dDv == false -> "Ni la pantalla ni el decodificador lo informan: no hay Dolby Vision."
                else -> NOT_REPORTED
            },
            "hdrTypes=${sdr.joinToString { it.label }} dvDecoders=${r.video.of(VideoCodec.DolbyVision).joinToString { it.name }} dvProfiles=$dvProfiles",
        )

        // Brillo
        val nits = dsp.maxLuminanceNits
        add(
            "nits", "Brillo máximo informado",
            when { nits == null -> Status.Unknown; nits >= 400f -> Status.Supported; else -> Status.Unsupported },
            when {
                nits == null -> NOT_REPORTED
                nits >= 400f -> "El TV declara hasta ${nits.roundToInt()} nits: bueno para HDR. Es el dato del fabricante, no una medición."
                else -> "El TV declara solo ${nits.roundToInt()} nits: el HDR será modesto. Es el dato del fabricante, no una medición."
            },
            "maxNits=${dsp.maxLuminanceNits} avgNits=${dsp.maxAvgLuminanceNits} minNits=${dsp.minLuminanceNits} hdrSdrRatio=${dsp.hdrSdrRatio}",
        )
        // Color
        val wcg = dsp.isWideColorGamut
        add(
            "wcg", "Color amplio (gama extendida)",
            when (wcg) { true -> Status.Supported; false -> Status.Unsupported; null -> Status.Unknown },
            when (wcg) {
                true -> "La pantalla muestra colores más vivos que una pantalla estándar."
                false -> "La pantalla informa gama de color estándar."
                null -> NOT_REPORTED
            },
            "isWideColorGamut=$wcg",
        )

        // Panel (honestidad)
        add(
            "panel", "Tipo de panel (OLED, LED, QLED)", Status.Unknown,
            "Android no informa el tipo de panel. Ese dato solo está en la ficha del fabricante; aquí no lo inventamos.",
            "sin API pública",
        )

        // Decodificadores
        val codecInfo = listOf(
            Triple("h264", VideoCodec.H264, "El formato más común; lo usan casi todas las apps."),
            Triple("hevc", VideoCodec.Hevc, "Lo usan Netflix, Disney+ y Prime Video para 4K y HDR."),
            Triple("vp9", VideoCodec.Vp9, "Lo usa YouTube para 4K y HDR."),
            Triple("av1", VideoCodec.Av1, "Formato nuevo que ahorra datos; YouTube y Netflix lo usan cuando el TV lo tiene."),
        )
        for ((key, codec, why) in codecInfo) {
            val dec = r.video.of(codec)
            val hw = dec.filter { it.hardware }
            val tech = dec.joinToString(" | ") {
                "${it.name} hw=${it.hardware} max=${it.maxWidth}x${it.maxHeight} 4k=${it.supports4k} 4k60=${it.supports4k60} 8k=${it.supports8k} secure=${it.securePlayback}"
            }.ifEmpty { "sin decodificadores" }
            val title = "Video ${codec.label}"
            if (!vOk) {
                add(key, title, Status.Unknown, NOT_REPORTED, "scanned=false")
            } else if (hw.isNotEmpty()) {
                val cap = when {
                    hw.any { it.supports4k60 } -> "Llega a 4K a 60 cuadros."
                    hw.any { it.supports4k } -> "Llega a 4K, pero no a 60 cuadros."
                    else -> "Solo hasta Full HD o menos."
                }
                add(key, title, Status.Supported, "Decodificador por hardware. $cap $why", tech)
            } else if (dec.isNotEmpty()) {
                add(key, title, Status.Unsupported, "Solo hay decodificador por software (lento): puede entrecortarse. $why", tech)
            } else {
                add(key, title, Status.Unsupported, "Este TV no trae decodificador. Las apps usarán otro formato si pueden. $why", tech)
            }
        }
        val any8k = r.video.decoders.values.flatten().any { it.hardware && it.supports8k }
        add(
            "8k", "Video 8K",
            if (!vOk) Status.Unknown else if (any8k) Status.Supported else Status.Unsupported,
            when { !vOk -> NOT_REPORTED; any8k -> "Hay decodificador por hardware con 8K (7680×4320)."; else -> "Ningún decodificador por hardware llega a 8K." },
            "8k=${r.video.decoders.mapValues { (_, v) -> v.any { it.supports8k } }}",
        )

        // Widevine
        val wv = r.video.widevineLevel
        val secure = r.video.decoders.values.flatten().any { it.securePlayback }
        add(
            "widevine", "Protección de contenido (Widevine)",
            when { wv == null -> Status.Unknown; wv.equals("L1", true) -> Status.Supported; else -> Status.Unsupported },
            when {
                wv == null -> NOT_REPORTED
                wv.equals("L1", true) -> "Nivel L1: Netflix, Disney+ y Prime Video pueden dar HD y 4K."
                else -> "Nivel $wv: las apps de pago suelen limitar la calidad a SD o HD básico."
            },
            "widevine=$wv secureDecoder=$secure",
        )
        return out
    }

    // ----------------------------------------------------------------- Sonido

    private fun sonido(r: DeviceReport): List<VerdictLine> {
        val a = r.audio
        val out = mutableListOf<VerdictLine>()
        fun add(key: String, title: String, status: Status, text: String, tech: String) {
            out += VerdictLine(key, Section.Sonido, title, status, text, tech)
        }
        val kinds = a.outputs.map { it.kind }.toSet()
        val external = kinds.any { it == OutputKind.Hdmi || it == OutputKind.HdmiArc || it == OutputKind.HdmiEarc }
        val outTech = a.outputs.joinToString(" | ") {
            "${it.kind.label}(type=${it.typeCode}) enc=${it.encodings.joinToString { c -> c.label }} rates=${it.sampleRates} ch=${it.channelCounts}"
        }.ifEmpty { "sin salidas" }

        add(
            "out", "Salidas de audio", if (a.scanned && a.outputs.isNotEmpty()) Status.Supported else Status.Unknown,
            if (a.outputs.isEmpty()) NOT_REPORTED else "Detectadas: ${kinds.joinToString { it.label }}.",
            outTech,
        )

        val earc = OutputKind.HdmiEarc in kinds
        val arc = OutputKind.HdmiArc in kinds
        add(
            "earc", "HDMI eARC (audio sin pérdida a la barra)",
            when { earc -> Status.Supported; arc -> Status.Unsupported; else -> Status.Unknown },
            when {
                earc -> "Hay una salida eARC activa: puede enviar Dolby TrueHD, Atmos sin pérdida y DTS-HD a una barra compatible."
                arc -> "Hay ARC normal (sin eARC): lleva Dolby Digital Plus y Atmos comprimido, pero no audio sin pérdida."
                else -> "No se detecta barra ni receptor por HDMI. Conéctalo y vuelve a abrir esta pantalla."
            },
            outTech,
        )

        fun passLine(key: String, title: String, codec: AudioCodec, yes: String, no: String, maxText: Boolean = false) {
            val onOutput = a.outputs.any { codec in it.encodings }
            val direct = a.passthrough[codec] == true
            val ok = onOutput || direct
            val status = when {
                !a.scanned -> Status.Unknown
                ok -> Status.Supported
                !external -> Status.Unknown
                else -> Status.Unsupported
            }
            val ch = a.directMaxChannels[codec]
            val text = when (status) {
                Status.Supported -> yes + (if (maxText && ch != null && ch > 0) " Hasta $ch canales." else "")
                Status.Unsupported -> no
                Status.Unknown -> if (!a.scanned) NOT_REPORTED else
                    "No hay barra ni receptor detectado por HDMI/ARC, así que no se puede saber. Conéctalo y vuelve a abrir esta pantalla."
            }
            add(key, title, status, text, "passthrough=${a.passthrough[codec]} enEncodingsDeSalida=$onOutput maxCanales=$ch")
        }
        passLine("atmos", "Dolby Atmos por HDMI", AudioCodec.Eac3Joc,
            "El TV puede enviarlo a una barra de sonido o receptor compatible.",
            "El equipo conectado por HDMI no acepta Dolby Atmos.")
        passLine("eac3", "Dolby Digital Plus por HDMI", AudioCodec.Eac3,
            "El TV puede enviarlo a una barra o receptor: es el sonido envolvente de Netflix y Disney+.",
            "El equipo conectado no acepta Dolby Digital Plus; las apps bajarán a estéreo o Dolby Digital.", true)
        passLine("ac3", "Dolby Digital por HDMI", AudioCodec.Ac3,
            "El TV puede enviar sonido envolvente 5.1 clásico a una barra o receptor.",
            "El equipo conectado no acepta Dolby Digital.")
        passLine("truehd", "Dolby TrueHD por HDMI", AudioCodec.TrueHd,
            "El TV puede enviar audio sin pérdida de discos Blu-ray a un receptor compatible.",
            "El equipo conectado no acepta Dolby TrueHD (suele requerir eARC).")
        passLine("mat", "Dolby Atmos sin pérdida (MAT)", AudioCodec.Mat,
            "El TV puede enviar Atmos sin pérdida a un receptor compatible.",
            "El equipo conectado no acepta Atmos sin pérdida (suele requerir eARC).")
        passLine("ac4", "Dolby AC-4 por HDMI", AudioCodec.Ac4,
            "El TV puede enviar AC-4 (usado en emisiones nuevas) a un receptor compatible.",
            "El equipo conectado no acepta AC-4.")
        passLine("dts", "DTS por HDMI", AudioCodec.Dts,
            "El TV puede enviar DTS a una barra o receptor compatible.",
            "El equipo conectado no acepta DTS.")
        passLine("dtshd", "DTS-HD por HDMI", AudioCodec.DtsHd,
            "El TV puede enviar DTS-HD a un receptor compatible.",
            "El equipo conectado no acepta DTS-HD.")

        val joc = a.decoders[Mime.Eac3Joc].orEmpty()
        add(
            "atmos_local", "Decodificar Atmos en el propio TV",
            if (!a.scanned) Status.Unknown else if (joc.isNotEmpty()) Status.Supported else Status.Unsupported,
            when {
                !a.scanned -> NOT_REPORTED
                joc.isNotEmpty() -> "El TV trae decodificador de Atmos (DD+ JOC): puede leerlo aunque no haya barra."
                else -> "No trae decodificador de Atmos: sin barra compatible no se aprovecha."
            },
            "decoders=$joc",
        )

        val sp = a.spatializer
        add(
            "spatial", "Audio espacial del sistema",
            when { sp == null -> Status.Unknown; !sp.available -> Status.Unsupported; sp.enabled -> Status.Supported; else -> Status.Unknown },
            when {
                sp == null -> NOT_REPORTED
                !sp.available -> "El TV no ofrece audio espacial de Android."
                sp.enabled -> "Audio espacial disponible y activo."
                else -> "Audio espacial disponible, pero apagado en los ajustes del TV."
            },
            "spatializer=$sp",
        )

        // Sin pérdida
        val flac = a.decoders[Mime.Flac].orEmpty()
        val alac = a.decoders[Mime.Alac].orEmpty()
        val mixer = a.mixerSampleRateHz
        add(
            "flac", "Música sin pérdida FLAC",
            if (!a.scanned) Status.Unknown else if (flac.isNotEmpty()) Status.Supported else Status.Unsupported,
            when {
                !a.scanned -> NOT_REPORTED
                flac.isNotEmpty() -> "El TV decodifica FLAC: reproductores como Plex, Kodi o VLC pueden usarlo."
                else -> "Sin decodificador FLAC del sistema; solo lo leerán apps que traigan el suyo."
            },
            "decoders=$flac",
        )
        add(
            "alac", "Música sin pérdida Apple (ALAC)",
            if (!a.scanned) Status.Unknown else if (alac.isNotEmpty()) Status.Supported else Status.Unsupported,
            when {
                !a.scanned -> NOT_REPORTED
                alac.isNotEmpty() -> "El TV decodifica ALAC (Apple Lossless)."
                else -> "Android no trae ALAC en este TV; solo lo leerán apps que incluyan su propio decodificador."
            },
            "decoders=$alac",
        )
        val directRates = a.outputs.flatMap { it.sampleRates }
        val hiRes = (mixer ?: 0) > 48000 || directRates.any { it > 48000 }
        add(
            "hires", "Audio de alta resolución (más de 48 kHz)",
            when { mixer == null && directRates.isEmpty() -> Status.Unknown; hiRes -> Status.Supported; else -> Status.Unsupported },
            when {
                mixer == null && directRates.isEmpty() -> NOT_REPORTED
                hiRes -> "El sistema o la salida acepta más de 48 kHz (hasta ${maxOf(mixer ?: 0, directRates.maxOrNull() ?: 0) / 1000} kHz)."
                else -> "El mezclador del sistema trabaja a ${(mixer ?: 48000) / 1000} kHz: todo el audio de Android se convierte (re-muestrea) a esa frecuencia. La música de 96 o 192 kHz se reduce antes de salir."
            },
            "mixerHz=$mixer framesPerBuffer=${a.framesPerBuffer} outputRates=${directRates.distinct().sorted()}",
        )
        return out
    }

    // ----------------------------------------------------------------- Equipo

    private fun equipo(r: DeviceReport, today: LocalDate): List<VerdictLine> {
        val d = r.device
        val out = mutableListOf<VerdictLine>()
        fun add(key: String, title: String, status: Status, text: String, tech: String) {
            out += VerdictLine(key, Section.Equipo, title, status, text, tech)
        }
        add(
            "tvmode", "Modo televisión",
            when (d.isTelevision) { true -> Status.Supported; false -> Status.Unsupported; null -> Status.Unknown },
            when (d.isTelevision) {
                true -> "Android identifica este equipo como televisión."
                false -> "Android no lo identifica como televisión: algunas funciones de TV pueden faltar."
                null -> NOT_REPORTED
            },
            "isTelevision=${d.isTelevision} brand=${d.brand} device=${d.device} hardware=${d.hardware}",
        )

        val ram = d.totalRamMb
        add(
            "ram", "Memoria (RAM)",
            when { ram == null -> Status.Unknown; ram >= 1400 -> Status.Supported; else -> Status.Unsupported },
            when {
                ram == null -> NOT_REPORTED
                ram >= 1400 -> "%.1f GB: suficiente para usar varias apps con soltura.".format(ram / 1024.0)
                else -> "%.1f GB: justa; las apps pesadas pueden cerrarse o ir lentas.".format(ram / 1024.0)
            },
            "totalRamMb=$ram",
        )
        val chip = listOfNotNull(d.socManufacturer, d.socModel).joinToString(" ")
        val is64 = d.abis.any { it.contains("64") }
        add(
            "cpu", "Procesador",
            if (d.cpuCores >= 4 && is64) Status.Supported else if (d.cpuCores >= 4 || is64) Status.Unknown else Status.Unsupported,
            "${d.cpuCores} núcleos, ${if (is64) "64 bits" else "32 bits"}. Chip: ${chip.ifBlank { "no disponible (Android no lo informa)" }}.",
            "cores=${d.cpuCores} abis=${d.abis} soc=$chip hardware=${d.hardware}",
        )
        val free = d.freeStorageMb
        add(
            "storage", "Almacenamiento libre",
            when { free == null -> Status.Unknown; free >= 1536 -> Status.Supported; free >= 500 -> Status.Unknown; else -> Status.Unsupported },
            when {
                free == null -> NOT_REPORTED
                free >= 1536 -> "%.1f GB libres de %.0f GB.".format(free / 1024.0, (d.totalStorageMb ?: 0) / 1024.0)
                free >= 500 -> "Quedan %.1f GB: justo para instalar apps nuevas.".format(free / 1024.0)
                else -> "Quedan solo %d MB: libera espacio para que las apps funcionen bien.".format(free)
            },
            "freeMb=$free totalMb=${d.totalStorageMb}",
        )
        add(
            "android", "Versión de Android",
            if (d.sdk >= 31) Status.Supported else Status.Unknown,
            if (d.sdk >= 31) "Android ${d.androidRelease}: versión reciente."
            else "Android ${d.androidRelease}: más antigua; funciona, pero algunas funciones nuevas (audio espacial, datos del chip) no se informan.",
            "release=${d.androidRelease} sdk=${d.sdk}",
        )
        val patchAge = d.securityPatch?.let { runCatching { YearMonth.from(LocalDate.parse(it)) }.getOrNull() }
            ?.let { ChronoUnit.MONTHS.between(it, YearMonth.from(today)) }
        add(
            "patch", "Parche de seguridad",
            when { patchAge == null -> Status.Unknown; patchAge <= 12 -> Status.Supported; patchAge > 24 -> Status.Unsupported; else -> Status.Unknown },
            when {
                patchAge == null -> NOT_REPORTED
                patchAge <= 12 -> "Del ${d.securityPatch}: al día."
                patchAge > 24 -> "Del ${d.securityPatch}: muy viejo (más de 2 años sin actualizar)."
                else -> "Del ${d.securityPatch}: con cierto atraso."
            },
            "securityPatch=${d.securityPatch}",
        )
        add(
            "size", "Tamaño de la pantalla en pulgadas", Status.Unknown,
            "Android no informa el tamaño físico real de un TV. Mira la etiqueta o la ficha del fabricante.",
            "densityDpi=${r.display.densityDpi}",
        )
        return out
    }

    // -------------------------------------------------------------------- Red

    private fun red(r: DeviceReport): List<VerdictLine> {
        val n = r.network
        val out = mutableListOf<VerdictLine>()
        fun add(key: String, title: String, status: Status, text: String, tech: String) {
            out += VerdictLine(key, Section.Red, title, status, text, tech)
        }
        val tech = "transport=${n.transport} link=${n.linkSpeedMbps}Mbps freq=${n.frequencyMhz}MHz rssi=${n.rssiDbm} std=${n.wifiStandard} down=${n.downstreamKbps}kbps up=${n.upstreamKbps}kbps"
        when (n.transport) {
            null -> add("net", "Conexión a Internet", Status.Unknown, NOT_REPORTED, tech)
            Transport.None -> add("net", "Conexión a Internet", Status.Unsupported, "El TV no está conectado a ninguna red.", tech)
            Transport.Ethernet -> add("net", "Conexión a Internet", Status.Supported, "Por cable de red: la opción más estable para 4K.", tech)
            Transport.Wifi -> add("net", "Conexión a Internet", Status.Supported, "Por Wi-Fi.", tech)
            Transport.Other -> add("net", "Conexión a Internet", Status.Unknown, "Conectado por un medio que no es Wi-Fi ni cable.", tech)
        }
        if (n.transport == Transport.Wifi || n.transport == null) {
            val f = n.frequencyMhz
            val band = when {
                f == null -> null
                f >= 5925 -> "6 GHz"
                f >= 4900 -> "5 GHz"
                else -> "2,4 GHz"
            }
            add(
                "wifi_band", "Wi-Fi de 5 GHz",
                when (band) { null -> Status.Unknown; "2,4 GHz" -> Status.Unsupported; else -> Status.Supported },
                when (band) {
                    null -> NOT_REPORTED
                    "2,4 GHz" -> "Está en la banda de 2,4 GHz: es más lenta y sufre interferencias; el 4K puede cortarse. Si tu router tiene 5 GHz, conéctalo ahí."
                    else -> "Conectado en $band ($f MHz): rápida y estable para 4K."
                },
                tech,
            )
            val ls = n.linkSpeedMbps
            add(
                "wifi_speed", "Velocidad del enlace Wi-Fi",
                when { ls == null -> Status.Unknown; ls >= 100 -> Status.Supported; else -> Status.Unsupported },
                when {
                    ls == null -> NOT_REPORTED
                    ls >= 100 -> "$ls Mbps entre el TV y el router: de sobra para 4K. No es la velocidad de tu Internet."
                    else -> "$ls Mbps entre el TV y el router: justo para 4K. No es la velocidad de tu Internet."
                },
                tech,
            )
            val ws = n.wifiStandard
            val std = when (ws) {
                4 -> "Wi-Fi 4 (n)"; 5 -> "Wi-Fi 5 (ac)"; 6 -> "Wi-Fi 6 (ax)"; 8 -> "Wi-Fi 7 (be)"; 1 -> "Wi-Fi antiguo (a/b/g)"; else -> null
            }
            add(
                "wifi_std", "Estándar Wi-Fi",
                when { ws == null || std == null -> Status.Unknown; ws >= 5 -> Status.Supported; else -> Status.Unsupported },
                when {
                    ws == null || std == null -> NOT_REPORTED
                    ws >= 5 -> "$std: moderno."
                    else -> "$std: antiguo; limita la velocidad."
                },
                tech,
            )
            val rssi = n.rssiDbm
            add(
                "wifi_signal", "Señal Wi-Fi",
                when { rssi == null -> Status.Unknown; rssi >= -67 -> Status.Supported; rssi >= -75 -> Status.Unknown; else -> Status.Unsupported },
                when {
                    rssi == null -> NOT_REPORTED
                    rssi >= -67 -> "Señal fuerte ($rssi dBm)."
                    rssi >= -75 -> "Señal regular ($rssi dBm): puede haber cortes en 4K."
                    else -> "Señal débil ($rssi dBm): acerca el router o usa cable."
                },
                tech,
            )
        }
        return out
    }

    private fun hz(f: Float) = f.roundToInt()

    private fun resName(width: Int) = when {
        width >= 7680 -> "8K"
        width >= 3840 -> "4K"
        width >= 2560 -> "QHD"
        width >= 1920 -> "Full HD"
        width >= 1280 -> "HD"
        else -> "SD"
    }
}

/** Texto plano con todos los valores crudos (vista "Ver datos técnicos"). */
object TechnicalDump {
    fun of(r: DeviceReport): String = buildString {
        val d = r.device
        appendLine("EQUIPO")
        appendLine("  ${d.manufacturer} ${d.model} (brand=${d.brand}, device=${d.device}, hardware=${d.hardware})")
        appendLine("  soc=${d.socManufacturer ?: "-"} ${d.socModel ?: "-"} android=${d.androidRelease} sdk=${d.sdk} patch=${d.securityPatch ?: "-"}")
        appendLine("  ram=${d.totalRamMb ?: "-"}MB cores=${d.cpuCores} abis=${d.abis.joinToString()} free=${d.freeStorageMb ?: "-"}MB/${d.totalStorageMb ?: "-"}MB")
        val s = r.display
        appendLine("PANTALLA")
        appendLine("  actual=${s.currentMode?.let { "${it.width}x${it.height}@${it.refreshHz}" } ?: "-"}")
        appendLine("  modos=${s.modes.joinToString { "${it.width}x${it.height}@${it.refreshHz}" }}")
        appendLine("  hdr=${s.hdrTypes.joinToString { it.label }} nits max=${s.maxLuminanceNits} avg=${s.maxAvgLuminanceNits} min=${s.minLuminanceNits}")
        appendLine("  isHdr=${s.isHdr} wcg=${s.isWideColorGamut} screenHdr=${s.screenHdrConfig} hdrSdrRatio=${s.hdrSdrRatio} dpi=${s.densityDpi}")
        appendLine("VIDEO (widevine=${r.video.widevineLevel ?: "-"})")
        r.video.decoders.forEach { (c, list) ->
            appendLine("  ${c.label}: " + list.joinToString(" | ") {
                "${it.name} hw=${it.hardware} ${it.maxWidth}x${it.maxHeight} 4k60=${it.supports4k60} 8k=${it.supports8k} secure=${it.securePlayback} ${it.profiles}"
            }.ifEmpty { "ninguno" })
        }
        val a = r.audio
        appendLine("AUDIO")
        a.outputs.forEach {
            appendLine("  ${it.kind.label} type=${it.typeCode} enc=${it.encodings.joinToString { c -> c.label }} rates=${it.sampleRates} ch=${it.channelCounts}")
        }
        appendLine("  passthrough=${a.passthrough.mapKeys { it.key.label }}")
        appendLine("  maxCanalesDirectos=${a.directMaxChannels.mapKeys { it.key.label }}")
        appendLine("  decodificadores=${a.decoders}")
        appendLine("  mezclador=${a.mixerSampleRateHz}Hz framesPorBuffer=${a.framesPerBuffer} spatializer=${a.spatializer}")
        val n = r.network
        appendLine("RED")
        appendLine("  ${n.transport} link=${n.linkSpeedMbps} freq=${n.frequencyMhz} rssi=${n.rssiDbm} std=${n.wifiStandard} down=${n.downstreamKbps} up=${n.upstreamKbps}")
        if (r.errors.isNotEmpty()) {
            appendLine("ERRORES DEL SONDEO")
            r.errors.forEach { appendLine("  $it") }
        }
    }.trimEnd()
}
