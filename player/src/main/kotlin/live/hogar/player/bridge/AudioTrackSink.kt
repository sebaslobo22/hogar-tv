package live.hogar.player.bridge

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack

/** Altavoces del TV por AudioTrack (44,1 kHz, estéreo, 16 bits, flujo). */
internal class AudioTrackSink : LocalSink {
    private val track: AudioTrack
    private val ts = AudioTimestamp()
    @Volatile private var written = 0L        // tramas escritas desde el último vaciado
    @Volatile private var headBase = 0L

    init {
        val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(min * 2, RATE * BYTES_PER_FRAME / 4))   // ~250 ms
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    override val startLatencyNs: Long = 30_000_000L   // estimación inicial; la deriva fina la corrige

    override fun write(pcm: ByteArray, off: Int, len: Int) {
        var o = off
        var left = len
        while (left > 0) {
            val n = track.write(pcm, o, left)
            if (n <= 0) return   // vaciado o liberado desde otro hilo
            o += n; left -= n
            written += n / BYTES_PER_FRAME
        }
    }

    private fun head(nowNs: Long): Long {
        val pos: Long = if (track.getTimestamp(ts)) {
            ts.framePosition + (nowNs - ts.nanoTime) * RATE / 1_000_000_000L
        } else {
            track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        }
        return (pos - headBase).coerceIn(0L, written)
    }

    override fun bufferedFrames(nowNs: Long): Long = written - head(nowNs)

    override fun start() { runCatching { track.play() } }

    override fun pauseAndFlush() {
        runCatching {
            track.pause()
            track.flush()
            headBase = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            written = 0
        }
    }

    override fun setGain(g: Float) { runCatching { track.setVolume(g) } }

    override fun release() { runCatching { track.stop() }; runCatching { track.release() } }
}
