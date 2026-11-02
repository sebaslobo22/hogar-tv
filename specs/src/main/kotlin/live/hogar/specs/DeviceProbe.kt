@file:Suppress("DEPRECATION")

package live.hogar.specs

import android.app.ActivityManager
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaDrm
import android.media.MediaFormat
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.view.Display
import java.util.UUID

/**
 * Sondeo de hardware y capacidades. Bloqueante: llamar fuera del hilo principal.
 * Cada sección va protegida: si una consulta falla queda como "no informado" y el
 * motivo se anota en [DeviceReport.errors]; nunca se inventa un valor.
 */
object DeviceProbe {

    fun probe(context: Context): DeviceReport {
        val app = context.applicationContext
        val errors = mutableListOf<String>()
        fun <T> safe(name: String, fallback: T, block: () -> T): T =
            try { block() } catch (t: Throwable) { errors += "$name: ${t.javaClass.simpleName} ${t.message.orEmpty()}".trim(); fallback }

        val device = safe("equipo", emptyDevice()) { probeDevice(app) }
        val display = safe("pantalla", emptyDisplay()) { probeDisplay(app) }
        val video = safe("video", VideoInfo(false, emptyMap(), null)) { probeVideo() }
        val widevine = safe("widevine", null) { widevineLevel() }
        val audio = safe("audio", emptyAudio()) { probeAudio(app) }
        val network = safe("red", NetworkInfo(null, null, null, null, null, null, null)) { probeNetwork(app) }
        return DeviceReport(device, display, video.copy(widevineLevel = widevine), audio, network, errors)
    }

    // ---------------------------------------------------------------- Equipo

    private fun probeDevice(app: Context): DeviceInfo {
        val am = app.getSystemService(ActivityManager::class.java)
        val totalRam = runCatching {
            ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem / (1024 * 1024)
        }.getOrNull()
        val stat = runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull()
        val ui = app.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        fun clean(s: String?) = s?.takeIf { it.isNotBlank() && !it.equals(Build.UNKNOWN, true) }
        return DeviceInfo(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            brand = Build.BRAND.orEmpty(),
            device = Build.DEVICE.orEmpty(),
            hardware = Build.HARDWARE.orEmpty(),
            socManufacturer = if (Build.VERSION.SDK_INT >= 31) clean(Build.SOC_MANUFACTURER) else null,
            socModel = if (Build.VERSION.SDK_INT >= 31) clean(Build.SOC_MODEL) else null,
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            sdk = Build.VERSION.SDK_INT,
            securityPatch = clean(Build.VERSION.SECURITY_PATCH),
            isTelevision = ui?.let { it.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION },
            totalRamMb = totalRam,
            cpuCores = Runtime.getRuntime().availableProcessors(),
            abis = Build.SUPPORTED_ABIS.toList(),
            freeStorageMb = stat?.let { it.availableBytes / (1024 * 1024) },
            totalStorageMb = stat?.let { it.totalBytes / (1024 * 1024) },
        )
    }

    private fun emptyDevice() = DeviceInfo(
        Build.MANUFACTURER.orEmpty(), Build.MODEL.orEmpty(), Build.BRAND.orEmpty(), Build.DEVICE.orEmpty(),
        Build.HARDWARE.orEmpty(), null, null, Build.VERSION.RELEASE.orEmpty(), Build.VERSION.SDK_INT,
        null, null, null, Runtime.getRuntime().availableProcessors(), emptyList(), null, null,
    )

    // --------------------------------------------------------------- Pantalla

    private fun probeDisplay(app: Context): DisplayInfo {
        val dm = app.getSystemService(DisplayManager::class.java)
        val d = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return emptyDisplay()

        fun Display.Mode.toMode() = DisplayMode(physicalWidth, physicalHeight, refreshRate)
        val current = runCatching { d.mode.toMode() }.getOrNull()
        val modes = runCatching { d.supportedModes.map { it.toMode() } }.getOrDefault(emptyList())
            .distinct().sortedWith(compareByDescending<DisplayMode> { it.width * it.height }.thenByDescending { it.refreshHz })

        // Tipos HDR: capacidades del display + (API 34+) los de cada modo.
        val types = mutableSetOf<Int>()
        var hdrOk = false
        var maxL: Float? = null
        var avgL: Float? = null
        var minL: Float? = null
        runCatching {
            val caps = d.hdrCapabilities
            hdrOk = true
            if (caps != null) {
                caps.supportedHdrTypes.forEach { types += it }
                maxL = caps.desiredMaxLuminance.takeIf { it > 0f }
                avgL = caps.desiredMaxAverageLuminance.takeIf { it > 0f }
                minL = caps.desiredMinLuminance.takeIf { it > 0f }
            }
        }
        if (Build.VERSION.SDK_INT >= 34) {
            runCatching {
                d.supportedModes.forEach { m -> m.supportedHdrTypes.forEach { types += it } }
                hdrOk = true
            }
        }
        val hdr = types.mapNotNull {
            when (it) {
                Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> HdrType.DolbyVision
                Display.HdrCapabilities.HDR_TYPE_HDR10 -> HdrType.Hdr10
                Display.HdrCapabilities.HDR_TYPE_HLG -> HdrType.Hlg
                Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> HdrType.Hdr10Plus
                else -> null
            }
        }.toSet()

        val isHdr = if (Build.VERSION.SDK_INT >= 34) runCatching { d.isHdr }.getOrNull() else null
        val wcg = runCatching { d.isWideColorGamut }.getOrNull()
        val screenHdr = runCatching { app.resources.configuration.isScreenHdr }.getOrNull()
        val ratio = if (Build.VERSION.SDK_INT >= 34) {
            runCatching { if (d.isHdrSdrRatioAvailable) d.hdrSdrRatio else null }.getOrNull()
        } else null

        return DisplayInfo(
            currentMode = current, modes = modes, hdrReported = hdrOk, hdrTypes = hdr,
            maxLuminanceNits = maxL, maxAvgLuminanceNits = avgL, minLuminanceNits = minL,
            isHdr = isHdr, isWideColorGamut = wcg, screenHdrConfig = screenHdr, hdrSdrRatio = ratio,
            densityDpi = runCatching { app.resources.displayMetrics.densityDpi }.getOrNull(),
            panelSize = panelSize(),
        )
    }

    private fun emptyDisplay() = DisplayInfo(null, emptyList(), false, emptySet(), null, null, null, null, null, null, null, null)

    // ------------------------------------------------------------------ Video

    private val videoMimes = mapOf(
        VideoCodec.H264 to MediaFormat.MIMETYPE_VIDEO_AVC,
        VideoCodec.Hevc to MediaFormat.MIMETYPE_VIDEO_HEVC,
        VideoCodec.Vp9 to MediaFormat.MIMETYPE_VIDEO_VP9,
        VideoCodec.Av1 to "video/av01",
        VideoCodec.DolbyVision to MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION,
    )

    private fun probeVideo(): VideoInfo {
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
        val out = mutableMapOf<VideoCodec, List<VideoDecoder>>()
        for ((codec, mime) in videoMimes) {
            out[codec] = infos.mapNotNull { info ->
                val type = info.supportedTypes.firstOrNull { it.equals(mime, true) } ?: return@mapNotNull null
                runCatching { decoderOf(info, type, codec) }.getOrNull()
            }
        }
        return VideoInfo(true, out, null)
    }

    private fun decoderOf(info: MediaCodecInfo, type: String, codec: VideoCodec): VideoDecoder {
        val caps = info.getCapabilitiesForType(type)
        val vc = caps.videoCapabilities
        val profiles = mutableSetOf<VideoProfile>()
        for (pl in caps.profileLevels) {
            val p = pl.profile
            when (codec) {
                VideoCodec.Hevc -> when (p) {
                    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 -> profiles += VideoProfile.Hevc10
                    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 -> profiles += VideoProfile.Hevc10Hdr10
                    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus -> profiles += VideoProfile.Hevc10Hdr10Plus
                }
                VideoCodec.Vp9 -> when (p) {
                    MediaCodecInfo.CodecProfileLevel.VP9Profile2 -> profiles += VideoProfile.Vp9P2
                    MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR -> profiles += VideoProfile.Vp9P2Hdr
                    MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR10Plus -> profiles += VideoProfile.Vp9P2Hdr10Plus
                }
                VideoCodec.Av1 -> when (p) {
                    MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10 -> profiles += VideoProfile.Av1Main10
                    MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10 -> profiles += VideoProfile.Av1Main10Hdr10
                    MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10Plus -> profiles += VideoProfile.Av1Main10Hdr10Plus
                }
                VideoCodec.DolbyVision -> when (p) {
                    MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDtr -> profiles += VideoProfile.Dv4
                    MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheStn -> profiles += VideoProfile.Dv5
                    MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDtb -> profiles += VideoProfile.Dv7
                    MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheSt -> profiles += VideoProfile.Dv8
                    MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvavSe -> profiles += VideoProfile.Dv9
                    MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvav110 -> profiles += VideoProfile.Dv10
                }
                VideoCodec.H264 -> Unit
            }
        }
        return VideoDecoder(
            name = info.name,
            hardware = info.isHardwareAccelerated && !info.isSoftwareOnly,
            maxWidth = vc?.supportedWidths?.upper ?: 0,
            maxHeight = vc?.supportedHeights?.upper ?: 0,
            supports4k = runCatching { vc.isSizeSupported(3840, 2160) }.getOrDefault(false),
            supports4k60 = runCatching { vc.areSizeAndRateSupported(3840, 2160, 60.0) }.getOrDefault(false),
            supports8k = runCatching { vc.isSizeSupported(7680, 4320) }.getOrDefault(false),
            securePlayback = runCatching { caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback) }.getOrDefault(false),
            profiles = profiles,
        )
    }

    private fun widevineLevel(): String? {
        val drm = MediaDrm(UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"))
        return try {
            drm.getPropertyString("securityLevel").takeIf { it.isNotBlank() }
        } finally {
            if (Build.VERSION.SDK_INT >= 28) drm.close() else drm.release()
        }
    }

    // ------------------------------------------------------------------ Audio

    private fun codecOf(encoding: Int): AudioCodec? = when (encoding) {
        AudioFormat.ENCODING_AC3 -> AudioCodec.Ac3
        AudioFormat.ENCODING_E_AC3 -> AudioCodec.Eac3
        AudioFormat.ENCODING_E_AC3_JOC -> AudioCodec.Eac3Joc
        AudioFormat.ENCODING_AC4 -> AudioCodec.Ac4
        AudioFormat.ENCODING_DOLBY_TRUEHD -> AudioCodec.TrueHd
        AudioFormat.ENCODING_DOLBY_MAT -> AudioCodec.Mat
        AudioFormat.ENCODING_DTS -> AudioCodec.Dts
        AudioFormat.ENCODING_DTS_HD -> AudioCodec.DtsHd
        else -> null
    }

    private fun kindOf(type: Int): OutputKind = when (type) {
        AudioDeviceInfo.TYPE_HDMI -> OutputKind.Hdmi
        AudioDeviceInfo.TYPE_HDMI_ARC -> OutputKind.HdmiArc
        29 /* TYPE_HDMI_EARC, API 31 */ -> OutputKind.HdmiEarc
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> OutputKind.Speaker
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> OutputKind.Bluetooth
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> OutputKind.Usb
        AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_AUX_LINE -> OutputKind.Analog
        else -> OutputKind.Other
    }

    private fun emptyAudio() = AudioInfo(false, emptyList(), emptyMap(), emptyMap(), emptyMap(), null, null, null)

    private fun probeAudio(app: Context): AudioInfo {
        val am = app.getSystemService(AudioManager::class.java)
        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.isSink }.map { d ->
            AudioOutput(
                kind = kindOf(d.type),
                typeCode = d.type,
                encodings = d.encodings.toList().mapNotNull { codecOf(it) }.toSet(),
                sampleRates = d.sampleRates.sorted(),
                channelCounts = d.channelCounts.sorted(),
            )
        }

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()
        val probes = listOf(
            Triple(AudioCodec.Ac3, AudioFormat.ENCODING_AC3, 48000),
            Triple(AudioCodec.Eac3, AudioFormat.ENCODING_E_AC3, 48000),
            Triple(AudioCodec.Eac3Joc, AudioFormat.ENCODING_E_AC3_JOC, 48000),
            Triple(AudioCodec.Ac4, AudioFormat.ENCODING_AC4, 48000),
            Triple(AudioCodec.TrueHd, AudioFormat.ENCODING_DOLBY_TRUEHD, 192000),
            Triple(AudioCodec.Mat, AudioFormat.ENCODING_DOLBY_MAT, 192000),
            Triple(AudioCodec.Dts, AudioFormat.ENCODING_DTS, 48000),
            Triple(AudioCodec.DtsHd, AudioFormat.ENCODING_DTS_HD, 192000),
        )
        val passthrough = mutableMapOf<AudioCodec, Boolean>()
        for ((codec, enc, rate) in probes) {
            val mask = if (codec == AudioCodec.TrueHd || codec == AudioCodec.Mat || codec == AudioCodec.DtsHd)
                AudioFormat.CHANNEL_OUT_7POINT1_SURROUND else AudioFormat.CHANNEL_OUT_5POINT1
            passthrough[codec] = runCatching {
                val f = AudioFormat.Builder().setEncoding(enc).setSampleRate(rate).setChannelMask(mask).build()
                AudioTrack.isDirectPlaybackSupported(f, attrs)
            }.getOrDefault(false)
        }

        val maxCh = mutableMapOf<AudioCodec, Int>()
        if (Build.VERSION.SDK_INT >= 33) {
            runCatching {
                for (p in am.getDirectProfilesForAttributes(attrs)) {
                    val codec = codecOf(p.format) ?: continue
                    passthrough[codec] = true
                    val ch = (p.channelMasks.map { Integer.bitCount(it) } + p.channelIndexMasks.map { Integer.bitCount(it) }).maxOrNull() ?: 0
                    maxCh[codec] = maxOf(maxCh[codec] ?: 0, ch)
                }
            }
        }

        val infos = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder } }.getOrDefault(emptyList())
        val decoders = listOf(Mime.Eac3Joc, Mime.Ac4, Mime.Flac, Mime.Alac).associateWith { mime ->
            infos.filter { i -> i.supportedTypes.any { it.equals(mime, true) } }.map { it.name }
        }

        val spatial = if (Build.VERSION.SDK_INT >= 32) {
            runCatching { am.spatializer.let { SpatializerInfo(it.isAvailable, it.isEnabled, it.immersiveAudioLevel) } }.getOrNull()
        } else null

        return AudioInfo(
            scanned = true, outputs = outputs, passthrough = passthrough, directMaxChannels = maxCh, decoders = decoders,
            mixerSampleRateHz = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull(),
            framesPerBuffer = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull(),
            spatializer = spatial,
        )
    }

    // -------------------------------------------------------------------- Red

    private fun probeNetwork(app: Context): NetworkInfo {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            ?: return NetworkInfo(Transport.None, null, null, null, null, null, null)
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.Wifi
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Transport.Ethernet
            else -> Transport.Other
        }
        var link: Int? = null
        var freq: Int? = null
        var rssi: Int? = null
        var std: Int? = null
        if (transport == Transport.Wifi) {
            // Velocidad y banda no requieren permiso de ubicación (solo SSID/BSSID lo piden).
            runCatching {
                val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val info = wm.connectionInfo
                link = listOf(info.linkSpeed, info.rxLinkSpeedMbps, info.txLinkSpeedMbps).filter { it > 0 }.maxOrNull()
                freq = info.frequency.takeIf { it > 0 }
                rssi = info.rssi.takeIf { it > -127 && it < 0 }
                std = if (Build.VERSION.SDK_INT >= 30) info.wifiStandard.takeIf { it > 0 } else null
            }
        }
        return NetworkInfo(
            transport, link, freq, rssi, std,
            caps.linkDownstreamBandwidthKbps.takeIf { it > 0 },
            caps.linkUpstreamBandwidthKbps.takeIf { it > 0 },
        )
    }

    /** Lee "3840x2160" de las propiedades que publican los fabricantes (MediaTek/MStar, Amlogic). */
    private fun panelSize(): DisplayMode? = runCatching {
        val get = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
        listOf("vendor.display-size", "sys.display-size", "ro.vendor.display.size")
            .firstNotNullOfOrNull { key ->
                Regex("""(\d{3,5})x(\d{3,5})""").find(get.invoke(null, key) as String)?.destructured
                    ?.let { (w, h) -> DisplayMode(w.toInt(), h.toInt(), 0f) }
            }
    }.getOrNull()
}
