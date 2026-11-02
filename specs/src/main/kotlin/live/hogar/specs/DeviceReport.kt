package live.hogar.specs

/**
 * Datos crudos del equipo. Solo Kotlin puro (nada de Android) para poder probar el
 * veredicto con datos inventados. `null` = Android no lo informa.
 */
data class DeviceReport(
    val device: DeviceInfo,
    val display: DisplayInfo,
    val video: VideoInfo,
    val audio: AudioInfo,
    val network: NetworkInfo,
    /** Secciones del sondeo que fallaron (se muestran en datos técnicos). */
    val errors: List<String> = emptyList(),
)

data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val brand: String,
    val device: String,
    val hardware: String,
    val socManufacturer: String?,
    val socModel: String?,
    val androidRelease: String,
    val sdk: Int,
    val securityPatch: String?,
    val isTelevision: Boolean?,
    val totalRamMb: Long?,
    val cpuCores: Int,
    val abis: List<String>,
    val freeStorageMb: Long?,
    val totalStorageMb: Long?,
)

data class DisplayMode(val width: Int, val height: Int, val refreshHz: Float)

enum class HdrType(val label: String) {
    DolbyVision("Dolby Vision"), Hdr10("HDR10"), Hlg("HLG"), Hdr10Plus("HDR10+")
}

data class DisplayInfo(
    val currentMode: DisplayMode?,
    val modes: List<DisplayMode>,
    /** false si la consulta de HDR falló: entonces no se sabe nada. */
    val hdrReported: Boolean,
    val hdrTypes: Set<HdrType>,
    val maxLuminanceNits: Float?,
    val maxAvgLuminanceNits: Float?,
    val minLuminanceNits: Float?,
    val isHdr: Boolean?,
    val isWideColorGamut: Boolean?,
    val screenHdrConfig: Boolean?,
    val hdrSdrRatio: Float?,
    val densityDpi: Int?,
    /**
     * Tamaño real del panel según el fabricante (propiedades del sistema). En muchos TV la capa de
     * apps es 1080p aunque el panel y el video sean 4K; null si el fabricante no lo publica.
     */
    val panelSize: DisplayMode? = null,
)

enum class VideoCodec(val label: String) {
    H264("H.264"), Hevc("HEVC (H.265)"), Vp9("VP9"), Av1("AV1"), DolbyVision("Dolby Vision")
}

enum class VideoProfile {
    Hevc10, Hevc10Hdr10, Hevc10Hdr10Plus,
    Vp9P2, Vp9P2Hdr, Vp9P2Hdr10Plus,
    Av1Main10, Av1Main10Hdr10, Av1Main10Hdr10Plus,
    Dv4, Dv5, Dv7, Dv8, Dv9, Dv10,
}

data class VideoDecoder(
    val name: String,
    val hardware: Boolean,
    val maxWidth: Int,
    val maxHeight: Int,
    val supports4k: Boolean,
    val supports4k60: Boolean,
    val supports8k: Boolean,
    val securePlayback: Boolean,
    val profiles: Set<VideoProfile>,
)

data class VideoInfo(
    /** false si MediaCodecList falló. */
    val scanned: Boolean,
    val decoders: Map<VideoCodec, List<VideoDecoder>>,
    /** "L1", "L3"... o null si no se pudo leer. */
    val widevineLevel: String?,
) {
    fun of(codec: VideoCodec): List<VideoDecoder> = decoders[codec].orEmpty()
}

enum class AudioCodec(val label: String) {
    Ac3("Dolby Digital"), Eac3("Dolby Digital Plus"), Eac3Joc("Dolby Atmos (sobre DD+)"),
    Ac4("Dolby AC-4"), TrueHd("Dolby TrueHD"), Mat("Dolby MAT (Atmos sin pérdida)"),
    Dts("DTS"), DtsHd("DTS-HD"),
}

enum class OutputKind(val label: String) {
    Hdmi("HDMI"), HdmiArc("HDMI ARC"), HdmiEarc("HDMI eARC"), Speaker("Altavoces del TV"),
    Bluetooth("Bluetooth"), Usb("USB"), Analog("Analógica"), Other("Otra salida"),
}

data class AudioOutput(
    val kind: OutputKind,
    val typeCode: Int,
    val encodings: Set<AudioCodec>,
    val sampleRates: List<Int>,
    val channelCounts: List<Int>,
)

data class SpatializerInfo(val available: Boolean, val enabled: Boolean, val immersiveLevel: Int)

object Mime {
    const val Eac3Joc = "audio/eac3-joc"
    const val Ac4 = "audio/ac4"
    const val Flac = "audio/flac"
    const val Alac = "audio/alac"
}

data class AudioInfo(
    /** false si AudioManager falló. */
    val scanned: Boolean,
    val outputs: List<AudioOutput>,
    /** Resultado de isDirectPlaybackSupported para cada formato. */
    val passthrough: Map<AudioCodec, Boolean>,
    /** Máximo de canales en passthrough (API 33+), por formato. */
    val directMaxChannels: Map<AudioCodec, Int>,
    /** Decodificadores de audio presentes, por mime. */
    val decoders: Map<String, List<String>>,
    val mixerSampleRateHz: Int?,
    val framesPerBuffer: Int?,
    val spatializer: SpatializerInfo?,
)

enum class Transport { Wifi, Ethernet, Other, None }

data class NetworkInfo(
    val transport: Transport?,
    val linkSpeedMbps: Int?,
    val frequencyMhz: Int?,
    val rssiDbm: Int?,
    /** Constante WifiInfo.WIFI_STANDARD_* (4 = n, 5 = ac, 6 = ax, 8 = be). */
    val wifiStandard: Int?,
    val downstreamKbps: Int?,
    val upstreamKbps: Int?,
)
