package live.hogar.specs

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerdictTest {
    private val today = LocalDate.of(2026, 11, 1)

    private fun dec(name: String, hw: Boolean = true, k60: Boolean = true, k8: Boolean = false, secure: Boolean = true, vararg p: VideoProfile) =
        VideoDecoder(name, hw, 3840, 2160, true, k60, k8, secure, p.toSet())

    private fun device(sdk: Int = 34, ramMb: Long? = 3000, patch: String? = "2026-06-01") = DeviceInfo(
        "sony", "BRAVIA 4K", "Sony", "bravia", "mt5", null, null, "14", sdk, patch, true, ramMb, 8,
        listOf("arm64-v8a", "armeabi-v7a"), 8000, 16000,
    )

    private fun fullDisplay() = DisplayInfo(
        DisplayMode(1920, 1080, 60f),
        listOf(DisplayMode(3840, 2160, 60f), DisplayMode(3840, 2160, 120f), DisplayMode(1920, 1080, 60f)),
        true, setOf(HdrType.Hdr10, HdrType.Hlg, HdrType.DolbyVision, HdrType.Hdr10Plus),
        1000f, 600f, 0.05f, true, true, true, null, 320,
    )

    private fun fullVideo() = VideoInfo(
        true,
        mapOf(
            VideoCodec.H264 to listOf(dec("h264")),
            VideoCodec.Hevc to listOf(dec("hevc", p = arrayOf(VideoProfile.Hevc10, VideoProfile.Hevc10Hdr10, VideoProfile.Hevc10Hdr10Plus))),
            VideoCodec.Vp9 to listOf(dec("vp9", p = arrayOf(VideoProfile.Vp9P2))),
            VideoCodec.Av1 to listOf(dec("av1", p = arrayOf(VideoProfile.Av1Main10))),
            VideoCodec.DolbyVision to listOf(dec("dv", p = arrayOf(VideoProfile.Dv5, VideoProfile.Dv8))),
        ),
        "L1",
    )

    private fun audio(
        pass: Map<AudioCodec, Boolean> = emptyMap(),
        outputs: List<AudioOutput> = listOf(AudioOutput(OutputKind.HdmiEarc, 29, setOf(AudioCodec.Eac3Joc, AudioCodec.TrueHd), listOf(48000, 96000), listOf(2, 8))),
        mixer: Int? = 48000,
    ) = AudioInfo(true, outputs, pass, emptyMap(), mapOf(Mime.Flac to listOf("flac"), Mime.Alac to emptyList(), Mime.Eac3Joc to listOf("joc"), Mime.Ac4 to emptyList()), mixer, 192, null)

    private fun net(freq: Int? = 5180) = NetworkInfo(Transport.Wifi, 866, freq, -50, 6, 400000, 100000)

    private fun report(
        device: DeviceInfo = device(), display: DisplayInfo = fullDisplay(), video: VideoInfo = fullVideo(),
        audio: AudioInfo = audio(), network: NetworkInfo = net(),
    ) = DeviceReport(device, display, video, audio, network)

    private fun Verdict.line(key: String) = lines.first { it.key == key }

    @Test fun panel4kWithUiAt1080pIsExplainedNotDenied() {
        val dsp = fullDisplay().copy(
            currentMode = DisplayMode(1920, 1080, 60f),
            modes = listOf(DisplayMode(1920, 1080, 60f)),
            panelSize = DisplayMode(3840, 2160, 0f),
        )
        val line = VerdictEngine.evaluate(report(display = dsp), today).line("res4k")
        assertEquals(Status.Supported, line.status)
        assertTrue(line.explanation.contains("Panel 4K"))
        assertTrue(line.explanation.contains("1920×1080"))
    }

    @Test fun dolbyVisionSupportedWhenDisplayAndDecoderAgree() {
        val v = VerdictEngine.evaluate(report(), today)
        val dv = v.line("dv")
        assertEquals(Status.Supported, dv.status)
        assertTrue(dv.explanation.contains("Netflix y Disney+"))
    }

    @Test fun dolbyVisionNotSupportedWithoutDecoder() {
        val video = fullVideo().copy(decoders = fullVideo().decoders - VideoCodec.DolbyVision)
        val dv = VerdictEngine.evaluate(report(video = video), today).line("dv")
        assertEquals(Status.Unsupported, dv.status)
    }

    @Test fun hdrUnknownWhenNothingReported() {
        val v = VerdictEngine.evaluate(
            report(display = fullDisplay().copy(hdrReported = false, hdrTypes = emptySet()), video = VideoInfo(false, emptyMap(), null)), today,
        )
        assertEquals(Status.Unknown, v.line("hdr10").status)
        assertEquals(Status.Unknown, v.line("dv").status)
        assertTrue(v.line("hdr10").explanation.contains("Android no lo informa"))
    }

    @Test fun hdr10PlusDisplayYesDecoderSilentIsUnknownNotNo() {
        val hevc = listOf(dec("hevc", p = arrayOf(VideoProfile.Hevc10)))
        val video = fullVideo().copy(decoders = fullVideo().decoders + (VideoCodec.Hevc to hevc))
        assertEquals(Status.Unknown, VerdictEngine.evaluate(report(video = video), today).line("hdr10plus").status)
    }

    @Test fun atmosPassthroughSupportedOverArc() {
        val v = VerdictEngine.evaluate(report(audio = audio(pass = mapOf(AudioCodec.Eac3Joc to true))), today)
        assertEquals(Status.Supported, v.line("atmos").status)
        assertTrue(v.line("atmos").explanation.contains("barra de sonido"))
        assertEquals(Status.Supported, v.line("earc").status)
    }

    @Test fun atmosUnknownWhenNoExternalSink() {
        val a = audio(outputs = listOf(AudioOutput(OutputKind.Speaker, 2, emptySet(), emptyList(), listOf(2))))
        val v = VerdictEngine.evaluate(report(audio = a), today)
        assertEquals(Status.Unknown, v.line("atmos").status)
        assertEquals(Status.Unknown, v.line("earc").status)
    }

    @Test fun atmosNotSupportedWhenSinkRejects() {
        val a = audio(outputs = listOf(AudioOutput(OutputKind.Hdmi, 9, setOf(AudioCodec.Ac3), listOf(48000), listOf(2, 6))))
        assertEquals(Status.Unsupported, VerdictEngine.evaluate(report(audio = a), today).line("atmos").status)
    }

    @Test fun hiResFollowsMixerRate() {
        val low = audio(outputs = listOf(AudioOutput(OutputKind.Speaker, 2, emptySet(), listOf(48000), listOf(2))), mixer = 48000)
        val v = VerdictEngine.evaluate(report(audio = low), today)
        assertEquals(Status.Unsupported, v.line("hires").status)
        assertTrue(v.line("hires").explanation.contains("re-muestrea"))
        val high = VerdictEngine.evaluate(report(audio = audio(mixer = 96000)), today)
        assertEquals(Status.Supported, high.line("hires").status)
    }

    @Test fun losslessDecoders() {
        val v = VerdictEngine.evaluate(report(), today)
        assertEquals(Status.Supported, v.line("flac").status)
        assertEquals(Status.Unsupported, v.line("alac").status)
    }

    @Test fun wifi24GhzIsLimitation() {
        val v = VerdictEngine.evaluate(report(network = net(2437)), today)
        assertEquals(Status.Unsupported, v.line("wifi_band").status)
        assertTrue(v.limitations.any { it.startsWith("Wi-Fi de 5 GHz") })
    }

    @Test fun networkUnavailableIsUnknown() {
        val n = NetworkInfo(Transport.Wifi, null, null, null, null, null, null)
        val v = VerdictEngine.evaluate(report(network = n), today)
        assertEquals(Status.Unknown, v.line("wifi_band").status)
        assertTrue(v.line("wifi_band").explanation.contains("No disponible"))
    }

    @Test fun panelTypeIsHonestlyUnknown() {
        val v = VerdictEngine.evaluate(report(), today)
        assertEquals(Status.Unknown, v.line("panel").status)
        assertTrue(v.line("panel").explanation.contains("no informa"))
    }

    @Test fun highlightsAreTopThreeAndLimitationsListed() {
        val v = VerdictEngine.evaluate(report(), today)
        assertEquals(listOf("Dolby Vision", "HDR10+", "HDR10"), v.highlights)
        assertTrue(v.limitations.isEmpty())
        val weak = VerdictEngine.evaluate(report(display = fullDisplay().copy(hdrTypes = emptySet())), today)
        assertTrue(weak.limitations.contains("Dolby Vision: no soportado"))
        assertTrue(weak.highlights.none { it == "Dolby Vision" })
    }

    @Test fun resolutionAndRefresh() {
        val v = VerdictEngine.evaluate(report(), today)
        assertEquals(Status.Supported, v.line("res4k").status)
        assertEquals(Status.Supported, v.line("hz120").status)
        assertEquals(Status.Supported, v.line("hz24").status)
        val fhd = fullDisplay().copy(modes = listOf(DisplayMode(1920, 1080, 60f)))
        val v2 = VerdictEngine.evaluate(report(display = fhd), today)
        assertEquals(Status.Unsupported, v2.line("res4k").status)
        assertEquals(Status.Unsupported, v2.line("hz120").status)
        assertEquals(Status.Unsupported, v2.line("hz24").status)
    }

    @Test fun softwareOnlyDecoderIsNotSupported() {
        val video = fullVideo().copy(decoders = fullVideo().decoders + (VideoCodec.Av1 to listOf(dec("av1.sw", hw = false, k60 = false))))
        assertEquals(Status.Unsupported, VerdictEngine.evaluate(report(video = video), today).line("av1").status)
    }

    @Test fun widevineLevels() {
        assertEquals(Status.Supported, VerdictEngine.evaluate(report(), today).line("widevine").status)
        assertEquals(Status.Unsupported, VerdictEngine.evaluate(report(video = fullVideo().copy(widevineLevel = "L3")), today).line("widevine").status)
        assertEquals(Status.Unknown, VerdictEngine.evaluate(report(video = fullVideo().copy(widevineLevel = null)), today).line("widevine").status)
    }

    @Test fun equipoThresholds() {
        val old = VerdictEngine.evaluate(report(device = device(sdk = 29, ramMb = 1000, patch = "2022-01-01")), today)
        assertEquals(Status.Unsupported, old.line("ram").status)
        assertEquals(Status.Unsupported, old.line("patch").status)
        assertEquals(Status.Unknown, old.line("android").status)
        val nul = VerdictEngine.evaluate(report(device = device(ramMb = null, patch = null)), today)
        assertEquals(Status.Unknown, nul.line("ram").status)
        assertEquals(Status.Unknown, nul.line("patch").status)
    }

    @Test fun everySectionHasLinesAndTitleIsBuilt() {
        val v = VerdictEngine.evaluate(report(), today)
        Section.entries.forEach { assertTrue(it.name, v.of(it).isNotEmpty()) }
        assertEquals("Sony BRAVIA 4K", v.deviceTitle)
    }
}
