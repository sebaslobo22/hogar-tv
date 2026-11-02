package live.hogar.receiver.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import live.hogar.core.AudioBridgeHub
import live.hogar.receiver.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Flujo de audio RTP por UDP (SETUP tipo 96). ct: 1 PCM, 2 ALAC, 4 AAC-LC, 8 AAC-ELD (audio del espejo).
 * Descifra AES-128-CBC por paquete (IV fijo, solo los bloques completos; ver raop_buffer.c de UxPlay),
 * reordena por numero de secuencia, decodifica y reproduce por AudioTrack a 44,1 kHz estereo.
 */
internal class AudioReceiver(
    private val aesKey: ByteArray,
    private val aesIv: ByteArray,
    val ct: Int,
    private val spf: Int,
) {
    private val dataSocket = DatagramSocket(0)
    private val controlSocket = DatagramSocket(0)
    val dataPort: Int get() = dataSocket.localPort
    val controlPort: Int get() = controlSocket.localPort
    @Volatile private var running = true
    @Volatile private var flushRequested = false
    @Volatile private var gain = 1.0f
    @Volatile private var volumeDb = -15f
    private var streamLive = false
    // Marca RTP corrida para el puente (AAC: la salida del decodificador no coincide 1:1 con los paquetes).
    private var aacCursor = -1L
    private var lastPktRtp = -1L
    private var curRtp = 0L
    private var track: AudioTrack? = null
    private var codec: MediaCodec? = null
    private var alac: AlacDecoder? = null

    init {
        Thread(::controlLoop, "hogar-audio-ctl").also { it.isDaemon = true }.start()
        Thread(::dataLoop, "hogar-audio").also { it.isDaemon = true }.start()
    }

    fun stop() {
        running = false
        try { dataSocket.close() } catch (_: Throwable) {}
        try { controlSocket.close() } catch (_: Throwable) {}
    }

    fun flush() { flushRequested = true }

    /** El puente "TV como puente" recibe el PCM; si ya reproduce él, aquí no se usa el AudioTrack. */
    private fun emit(pcm: ByteArray, rtp: Long) {
        if (AudioBridgeHub.hasTap && AudioBridgeHub.pcm(this, pcm, pcm.size, rtp, System.nanoTime())) return
        try { track?.write(pcm, 0, pcm.size) } catch (_: Throwable) {}
    }

    private fun shortsToBytes(s: ShortArray): ByteArray {
        val b = ByteArray(s.size * 2)
        var j = 0
        for (v in s) { b[j++] = v.toByte(); b[j++] = (v.toInt() shr 8).toByte() }
        return b
    }

    /** Volumen de AirPlay en dB (-30..0, -144 = silencio) -> ganancia lineal. */
    fun setVolumeDb(db: Float) {
        volumeDb = db
        gain = if (db <= -100f) 0f else Math.pow(10.0, (db.coerceIn(-30f, 0f) / 20.0)).toFloat()
        try { track?.setVolume(gain) } catch (_: Throwable) {}
        if (streamLive) AudioBridgeHub.volume(this, db)
    }

    private fun controlLoop() {
        val buf = ByteArray(2048)
        try {
            while (running) {
                controlSocket.receive(DatagramPacket(buf, buf.size)) // sincronizacion y reenvios: no los usamos
            }
        } catch (t: Throwable) {
            if (running) Log.w("audio: control fallo", t)
        }
    }

    private fun setupDecoder() {
        when (ct) {
            2 -> alac = AlacDecoder(frameLength = if (spf > 0) spf else 352)
            4, 8 -> {
                val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 2)
                if (ct == 8) {
                    f.setInteger(MediaFormat.KEY_AAC_PROFILE, 39) // ELD
                    f.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(byteArrayOf(0xF8.toByte(), 0xE8.toByte(), 0x50, 0x00)))
                } else {
                    f.setInteger(MediaFormat.KEY_AAC_PROFILE, 2)
                    f.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(byteArrayOf(0x12, 0x10)))
                }
                f.setInteger(MediaFormat.KEY_IS_ADTS, 0)
                val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                c.configure(f, null, null, 0)
                c.start()
                codec = c
            }
            1 -> {}
            else -> Log.w("audio: tipo ct=$ct no soportado")
        }
        val minBuf = AudioTrack.getMinBufferSize(44100, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(44100).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minBuf * 2, 44100 * 4 / 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.setVolume(gain)
        t.play()
        track = t
        Log.i("audio: reproduciendo ct=$ct (44100 Hz estereo)")
        streamLive = true
        AudioBridgeHub.streamStarted(this, lossless = ct == 1 || ct == 2)
        AudioBridgeHub.volume(this, volumeDb)
    }

    private fun decrypt(pkt: ByteArray, len: Int): ByteArray {
        val payloadLen = len - 12
        val enc = payloadLen / 16 * 16
        val out = ByteArray(payloadLen)
        if (enc > 0) {
            val c = Cipher.getInstance("AES/CBC/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(aesIv))
            c.doFinal(pkt, 12, enc, out, 0)
        }
        System.arraycopy(pkt, 12 + enc, out, enc, payloadLen - enc)
        return out
    }

    private fun dataLoop() {
        try {
            setupDecoder()
            val buf = ByteArray(4096)
            // Reordenamiento pequeno: se espera hasta REORDER paquetes por un hueco antes de saltarlo.
            val pending = java.util.TreeMap<Int, ByteArray>()
            val rtps = HashMap<Int, Long>()
            var next = -1
            val REORDER = 8
            while (running) {
                val p = DatagramPacket(buf, buf.size)
                dataSocket.receive(p)
                if (p.length <= 12) continue // paquetes vacios previos a la sincronizacion
                if (flushRequested) {
                    flushRequested = false; pending.clear(); rtps.clear(); next = -1; aacCursor = -1; lastPktRtp = -1
                    try { track?.pause(); track?.flush(); track?.play() } catch (_: Throwable) {}
                    AudioBridgeHub.flush(this)
                }
                val seq = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
                val rtp = ((buf[4].toLong() and 0xFF) shl 24) or ((buf[5].toLong() and 0xFF) shl 16) or
                    ((buf[6].toLong() and 0xFF) shl 8) or (buf[7].toLong() and 0xFF)
                if (p.length == 16 && buf[12].toInt() == 0 && buf[13].toInt() == 0x68 && buf[14].toInt() == 0x34 && buf[15].toInt() == 0) continue
                if (ct == 2 && p.length == 44) continue // paquetes ALAC solo con informacion de formato
                val payload = try { decrypt(buf, p.length) } catch (t: Throwable) { Log.w("audio: descifrado fallo", t); continue }
                if (next < 0) next = seq
                val diff = (seq - next) and 0xFFFF
                if (diff >= 0x8000) continue // atrasado
                pending[seq] = payload
                rtps[seq] = rtp
                while (true) {
                    val d = pending[next]
                    if (d != null) { pending.remove(next); curRtp = rtps.remove(next) ?: curRtp; play(d); next = (next + 1) and 0xFFFF }
                    else if (pending.size > REORDER) { rtps.remove(next); next = (next + 1) and 0xFFFF } // se pierde ese paquete
                    else break
                }
            }
        } catch (t: Throwable) {
            if (running) Log.w("audio: flujo termino", t)
        } finally {
            if (streamLive) { streamLive = false; AudioBridgeHub.streamStopped(this) }
            try { track?.stop(); track?.release() } catch (_: Throwable) {}
            try { codec?.stop(); codec?.release() } catch (_: Throwable) {}
            track = null; codec = null
        }
    }

    private fun play(payload: ByteArray) {
        if (track == null) return
        try {
            when (ct) {
                1 -> emit(payload, curRtp)
                2 -> {
                    val s = alac!!.decode(payload)
                    if (AudioBridgeHub.hasTap) emit(shortsToBytes(s), curRtp)
                    else track?.write(s, 0, s.size)
                }
                4, 8 -> decodeAac(payload)
            }
        } catch (e: Throwable) {
            Log.w("audio: paquete descartado (${e.message})")
        }
    }

    private fun decodeAac(payload: ByteArray) {
        val c = codec ?: return
        // Cursor RTP propio: se reengancha al del paquete si hay salto (pérdida o tras un FLUSH).
        val step = if (spf > 0) spf else 480
        if (aacCursor < 0 || lastPktRtp < 0 || ((curRtp - lastPktRtp) and 0xFFFFFFFFL) != step.toLong()) aacCursor = curRtp
        lastPktRtp = curRtp
        val idx = c.dequeueInputBuffer(10_000)
        if (idx >= 0) {
            val b = c.getInputBuffer(idx)!!
            b.clear(); b.put(payload)
            c.queueInputBuffer(idx, 0, payload.size, 0, 0)
        }
        val info = MediaCodec.BufferInfo()
        while (true) {
            val o = c.dequeueOutputBuffer(info, 0)
            if (o >= 0) {
                val ob = c.getOutputBuffer(o)!!
                val pcm = ByteArray(info.size)
                ob.position(info.offset); ob.get(pcm, 0, info.size)
                c.releaseOutputBuffer(o, false)
                emit(pcm, aacCursor)
                aacCursor = (aacCursor + pcm.size / 4) and 0xFFFFFFFFL
            } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                continue
            } else break
        }
    }
}
