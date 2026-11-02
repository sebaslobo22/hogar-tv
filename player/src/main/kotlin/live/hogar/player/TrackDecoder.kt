package live.hogar.player

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Decodifica un archivo (FLAC, ALAC en m4a, WAV) a PCM con MediaExtractor + MediaCodec.
 * Los WAV ("audio/raw") no pasan por códec: sus bytes ya son PCM.
 */
class TrackDecoder(private val context: Context, private val uri: Uri) : AutoCloseable {
    class Chunk(val data: ByteArray, val size: Int)

    private val extractor = MediaExtractor()
    private var codec: MediaCodec? = null
    private var raw = false
    private var inputDone = false
    private var outputDone = false
    private val info = MediaCodec.BufferInfo()
    private val rawBuf = ByteBuffer.allocate(64 * 1024)

    var sampleRate = 44_100; private set
    var channels = 2; private set
    var encoding = PcmEncoding.PCM_16; private set
    var sourceBits = 16; private set

    fun open() {
        extractor.setDataSource(context, uri, null)
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                extractor.selectTrack(i)
                format = f
                break
            }
        }
        if (format == null) throw IOException("El archivo no tiene audio")
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        sourceBits = detectBitDepth(format, mime)

        if (mime == "audio/raw") {
            raw = true
            encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            else PcmEncoding.PCM_16
            if (!format.containsKey("bits-per-sample")) sourceBits = runCatching { PcmEncoding.bits(encoding).coerceAtMost(32) }.getOrDefault(16)
        } else {
            val c = try {
                MediaCodec.createDecoderByType(mime)
            } catch (e: Exception) {
                throw IOException("Este TV no puede decodificar $mime")
            }
            // Para 24 bits pedimos coma flotante y no perdemos bits antes de convertir nosotros.
            if (sourceBits > 16) format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
            c.configure(format, null, null, 0)
            c.start()
            codec = c
        }
    }

    /** Siguiente trozo de PCM, o null al terminar. Bloquea poco (decodificación). */
    fun read(): Chunk? {
        if (raw) return readRaw()
        val c = codec ?: return null
        while (!outputDone) {
            if (!inputDone) {
                val ii = c.dequeueInputBuffer(10_000)
                if (ii >= 0) {
                    val ib = c.getInputBuffer(ii)!!
                    val n = extractor.readSampleData(ib, 0)
                    if (n < 0) {
                        c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        c.queueInputBuffer(ii, 0, n, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val oi = c.dequeueOutputBuffer(info, 10_000)
            when {
                oi >= 0 -> {
                    var chunk: Chunk? = null
                    if (info.size > 0) {
                        val ob = c.getOutputBuffer(oi)!!
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size)
                        ob.get(bytes)
                        chunk = Chunk(bytes, bytes.size)
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    c.releaseOutputBuffer(oi, false)
                    if (chunk != null) return chunk
                }
                oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> applyOutputFormat(c.outputFormat)
            }
        }
        return null
    }

    private fun readRaw(): Chunk? {
        rawBuf.clear()
        val n = extractor.readSampleData(rawBuf, 0)
        if (n <= 0) return null
        val bytes = ByteArray(n)
        rawBuf.position(0)
        rawBuf.get(bytes, 0, n)
        extractor.advance()
        return Chunk(bytes, n)
    }

    fun seekTo(positionUs: Long) {
        extractor.seekTo(positionUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        codec?.flush()
        inputDone = false
        outputDone = false
    }

    private fun applyOutputFormat(f: MediaFormat) {
        if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        encoding = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING)
        else PcmEncoding.PCM_16
    }

    override fun close() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { extractor.release() }
        codec = null
    }

    private fun detectBitDepth(f: MediaFormat, mime: String): Int {
        if (f.containsKey("bits-per-sample")) return f.getInteger("bits-per-sample")
        if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            val e = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
            if (e == AudioFormat.ENCODING_PCM_16BIT) return 16
        }
        val csd = f.getByteBuffer("csd-0")?.let { b ->
            val d = b.duplicate()
            d.rewind()
            ByteArray(d.remaining()).also { d.get(it) }
        }
        if (csd != null) {
            val bits = when (mime) {
                "audio/flac" -> AudioFormats.parseFlacBitDepth(csd)
                "audio/alac" -> AudioFormats.parseAlacBitDepth(csd)
                else -> null
            }
            if (bits != null) return bits
        }
        if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            return runCatching { PcmEncoding.bits(f.getInteger(MediaFormat.KEY_PCM_ENCODING)) }.getOrDefault(16)
        }
        return 16
    }
}
