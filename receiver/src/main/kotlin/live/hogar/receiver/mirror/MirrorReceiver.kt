package live.hogar.receiver.mirror

import android.content.Context
import android.view.Surface
import live.hogar.receiver.Log
import java.io.EOFException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Flujo de espejo de pantalla (SETUP tipo 110): socket TCP de datos con cabeceras de 128 bytes.
 * tipo 0 = NAL H.264 cifradas con AES-128-CTR continuo, tipo 1 = avcC (SPS+PPS), 2/5 = latidos/informes.
 * Cifrado y formato segun mirror_buffer.c y raop_rtp_mirror.c de UxPlay.
 */
internal class MirrorReceiver(
    private val context: Context,
    aesKey: ByteArray,
    streamConnectionId: Long,
    private val onEnded: () -> Unit,
) : SurfaceConsumer {
    private val server = ServerSocket(0)
    val port: Int get() = server.localPort
    @Volatile private var running = true
    private var client: Socket? = null
    private val cipher: Cipher
    private val decoder = H264Decoder { w, h -> ScreenHub.setVideoSize(w, h) }
    private var ptsCounter = 0L

    init {
        val (key, iv) = StreamKeys.derive(aesKey, streamConnectionId)
        cipher = Cipher.getInstance("AES/CTR/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }
        decoder.start()
        ScreenHub.setConsumer(this)
        Thread(::run, "hogar-mirror").also { it.isDaemon = true }.start()
    }

    override fun onSurface(surface: Surface?) { decoder.setSurface(surface) }

    fun stop() {
        running = false
        try { server.close() } catch (_: Throwable) {}
        try { client?.close() } catch (_: Throwable) {}
        decoder.release()
        ScreenHub.setConsumer(null)
    }

    private fun readFully(input: InputStream, buf: ByteArray, len: Int) {
        var off = 0
        while (off < len) {
            val r = input.read(buf, off, len - off)
            if (r < 0) throw EOFException()
            off += r
        }
    }

    private fun run() {
        try {
            val s = server.accept()
            client = s
            s.tcpNoDelay = true
            s.keepAlive = true
            s.receiveBufferSize = 1 shl 20
            val input = java.io.BufferedInputStream(s.getInputStream(), 1 shl 16)
            val header = ByteArray(128)
            val hb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            Log.i("espejo: cliente conectado ${s.inetAddress.hostAddress}")
            while (running) {
                readFully(input, header, 128)
                val size = hb.getInt(0)
                val type = header[4].toInt() and 0xFF
                val option = header[6].toInt() and 0xFF
                if (size < 0 || size > 16 * 1024 * 1024) throw java.io.IOException("tamano de carga invalido: $size")
                val payload = ByteArray(size)
                if (size > 0) readFully(input, payload, size)
                when (type) {
                    0 -> onVideo(payload)
                    1 -> onConfig(payload, option, hb.getFloat(16), hb.getFloat(20))
                    else -> {} // 2 = latido, 5 = informe de streaming
                }
            }
        } catch (e: EOFException) {
            Log.i("espejo: el cliente cerro el flujo")
        } catch (t: Throwable) {
            if (running) Log.w("espejo: flujo termino", t)
        } finally {
            if (running) { running = false; onEnded() }
        }
    }

    private fun onConfig(p: ByteArray, option: Int, w: Float, h: Float) {
        if (option == 0x56) { Log.i("espejo: el cliente suspendio el video"); return }
        if (p.size < 8) return
        // avcC: [0]=1 version, [1..3] perfil/compat/nivel, [4] 0xFF, [5] 0xE1, [6..7] largo SPS, SPS, [n]=num PPS, largo PPS, PPS
        try {
            val spsLen = ((p[6].toInt() and 0xFF) shl 8) or (p[7].toInt() and 0xFF)
            val sps = p.copyOfRange(8, 8 + spsLen)
            var o = 8 + spsLen
            o++ // numero de PPS
            val ppsLen = ((p[o].toInt() and 0xFF) shl 8) or (p[o + 1].toInt() and 0xFF)
            val pps = p.copyOfRange(o + 2, o + 2 + ppsLen)
            Log.i("espejo: configuracion de video SPS=${sps.size} PPS=${pps.size} origen ${w.toInt()}x${h.toInt()}")
            if (w > 0 && h > 0) ScreenHub.setVideoSize(w.toInt(), h.toInt())
            ScreenHub.show(context)
            decoder.configure(sps, pps)
        } catch (t: Throwable) {
            Log.w("espejo: avcC invalido", t)
        }
    }

    private fun onVideo(enc: ByteArray) {
        // El cifrado es un flujo AES-CTR continuo a traves de todos los paquetes (restos de bloque incluidos).
        val data = cipher.update(enc) ?: return
        var i = 0
        while (i < data.size) {
            if (i + 4 > data.size) { Log.w("espejo: NAL truncada"); return }
            val len = ((data[i].toInt() and 0xFF) shl 24) or ((data[i + 1].toInt() and 0xFF) shl 16) or
                ((data[i + 2].toInt() and 0xFF) shl 8) or (data[i + 3].toInt() and 0xFF)
            if (len < 0 || i + 4 + len > data.size || len == 0) { Log.w("espejo: datos invalidos (descifrado incorrecto?)"); return }
            if (data[i + 4].toInt() and 0x80 != 0) { Log.w("espejo: bit prohibido en NAL (descifrado incorrecto?)"); return }
            data[i] = 0; data[i + 1] = 0; data[i + 2] = 0; data[i + 3] = 1
            i += 4 + len
        }
        decoder.queueFrame(data, ptsCounter)
        ptsCounter += 16_666
    }
}

/** Clave e IV de AES-128-CTR del video: SHA-512("AirPlayStreamKey<id>" + aesKey)[0..16] y lo mismo con "AirPlayStreamIV<id>". */
internal object StreamKeys {
    fun derive(aesKey: ByteArray, streamConnectionId: Long): Pair<ByteArray, ByteArray> {
        val idText = java.lang.Long.toUnsignedString(streamConnectionId)
        val sha = MessageDigest.getInstance("SHA-512")
        val key = sha.digest("AirPlayStreamKey$idText".toByteArray(Charsets.US_ASCII) + aesKey).copyOf(16)
        val iv = sha.digest("AirPlayStreamIV$idText".toByteArray(Charsets.US_ASCII) + aesKey).copyOf(16)
        return key to iv
    }
}
