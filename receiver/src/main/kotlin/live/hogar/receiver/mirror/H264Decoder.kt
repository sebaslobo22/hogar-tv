package live.hogar.receiver.mirror

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import live.hogar.receiver.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Decodificador H.264 de baja latencia. Un solo hilo es dueno del MediaCodec: recibe configuracion,
 * superficie y cuadros por una cola, y pinta cada cuadro apenas sale (sin esperar marcas de tiempo).
 */
class H264Decoder(private val onSize: (width: Int, height: Int) -> Unit) {
    private sealed interface Cmd
    private class Config(val sps: ByteArray, val pps: ByteArray) : Cmd
    private class Surf(val surface: Surface?) : Cmd
    private class Frame(val data: ByteArray, val ptsUs: Long) : Cmd
    private object Stop : Cmd

    private val queue = LinkedBlockingQueue<Cmd>(90)
    private val thread = Thread(::run, "hogar-h264").also { it.isDaemon = true }
    @Volatile private var started = false

    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var needKey = true

    fun start() {
        if (!started) { started = true; thread.start() }
    }

    /** SPS/PPS sin codigo de inicio. */
    fun configure(sps: ByteArray, pps: ByteArray) { queue.put(Config(sps, pps)) }
    fun setSurface(s: Surface?) { queue.put(Surf(s)) }

    /** Unidad de acceso con codigos de inicio 00 00 00 01. Bloquea si el decodificador va atrasado (contrapresion TCP). */
    fun queueFrame(data: ByteArray, ptsUs: Long) {
        if (!queue.offer(Frame(data, ptsUs), 500, TimeUnit.MILLISECONDS)) Log.w("decodificador atrasado, se descarta un cuadro")
    }

    fun release() {
        queue.clear()
        queue.offer(Stop, 1, TimeUnit.SECONDS)
    }

    private fun run() {
        try {
            while (true) {
                val cmd = queue.poll(4, TimeUnit.MILLISECONDS)
                when (cmd) {
                    is Config -> { sps = cmd.sps; pps = cmd.pps; rebuild() }
                    is Surf -> { surface = cmd.surface; rebuild() }
                    is Frame -> feed(cmd)
                    Stop -> break
                    null -> {}
                }
                drain()
            }
        } catch (t: Throwable) {
            Log.e("decodificador H.264 fallo", t)
        } finally {
            closeCodec()
        }
    }

    private fun closeCodec() {
        val c = codec ?: return
        codec = null
        try { c.stop() } catch (_: Throwable) {}
        try { c.release() } catch (_: Throwable) {}
    }

    private fun rebuild() {
        closeCodec()
        val s = surface ?: return
        val sp = sps ?: return
        val pp = pps ?: return
        if (!s.isValid) return
        try {
            val (w, h) = SpsParser.size(sp) ?: (1920 to 1080)
            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
            fmt.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1) + sp))
            fmt.setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1) + pp))
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024)
            if (Build.VERSION.SDK_INT >= 30) fmt.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            fmt.setInteger(MediaFormat.KEY_PRIORITY, 0)
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.configure(fmt, s, null, 0)
            c.start()
            codec = c
            needKey = true
            onSize(w, h)
            Log.i("decodificador H.264 listo ${w}x$h (${c.name})")
        } catch (t: Throwable) {
            Log.e("no se pudo crear el decodificador H.264", t)
            closeCodec()
        }
    }

    private fun feed(f: Frame) {
        val c = codec ?: return
        // Hasta el primer IDR no se manda nada: el decodificador acaba de empezar.
        if (needKey) {
            if (!containsIdr(f.data)) return
            needKey = false
        }
        var tries = 0
        while (tries < 25) {
            val idx = try { c.dequeueInputBuffer(4_000) } catch (t: Throwable) { Log.w("dequeueInputBuffer", t); return }
            if (idx >= 0) {
                val buf = c.getInputBuffer(idx) ?: return
                buf.clear()
                if (f.data.size > buf.capacity()) { Log.w("cuadro mas grande que el buffer"); c.queueInputBuffer(idx, 0, 0, f.ptsUs, 0); return }
                buf.put(f.data)
                c.queueInputBuffer(idx, 0, f.data.size, f.ptsUs, 0)
                return
            }
            drain()
            tries++
        }
        Log.w("sin buffer de entrada, cuadro descartado")
    }

    private fun containsIdr(d: ByteArray): Boolean {
        var i = 0
        while (i + 4 < d.size) {
            if (d[i].toInt() == 0 && d[i + 1].toInt() == 0 && d[i + 2].toInt() == 0 && d[i + 3].toInt() == 1) {
                if ((d[i + 4].toInt() and 0x1F) == 5) return true
                i += 4
            } else i++
        }
        return false
    }

    private fun drain() {
        val c = codec ?: return
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                val idx = c.dequeueOutputBuffer(info, 0)
                when {
                    idx >= 0 -> c.releaseOutputBuffer(idx, true)
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = c.outputFormat
                        val w = if (f.containsKey("crop-right")) f.getInteger("crop-right") - f.getInteger("crop-left") + 1 else f.getInteger(MediaFormat.KEY_WIDTH)
                        val h = if (f.containsKey("crop-bottom")) f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1 else f.getInteger(MediaFormat.KEY_HEIGHT)
                        onSize(w, h)
                    }
                    else -> return
                }
            }
        } catch (t: Throwable) {
            Log.w("drain fallo", t)
            closeCodec()
        }
    }
}

/** Lee ancho y alto de un SPS H.264 (sin cabecera de NAL ni codigo de inicio). */
object SpsParser {
    fun size(sps: ByteArray): Pair<Int, Int>? = try {
        val rbsp = unescape(sps.copyOfRange(1, sps.size))
        val r = Bits(rbsp)
        val profile = r.bits(8); r.bits(8); r.bits(8)
        r.ue()
        var chromaFormat = 1
        if (profile in intArrayOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
            chromaFormat = r.ue()
            if (chromaFormat == 3) r.bits(1)
            r.ue(); r.ue(); r.bits(1)
            if (r.bits(1) == 1) {
                val n = if (chromaFormat != 3) 8 else 12
                for (i in 0 until n) if (r.bits(1) == 1) skipScaling(r, if (i < 6) 16 else 64)
            }
        }
        r.ue()
        val poc = r.ue()
        if (poc == 0) r.ue() else if (poc == 1) {
            r.bits(1); r.se(); r.se()
            val n = r.ue()
            repeat(n) { r.se() }
        }
        r.ue(); r.bits(1)
        val wMbs = r.ue() + 1
        val hMapUnits = r.ue() + 1
        val frameMbsOnly = r.bits(1)
        if (frameMbsOnly == 0) r.bits(1)
        r.bits(1)
        var cl = 0; var cr = 0; var ct = 0; var cb = 0
        if (r.bits(1) == 1) { cl = r.ue(); cr = r.ue(); ct = r.ue(); cb = r.ue() }
        val cropUnitX = if (chromaFormat == 0) 1 else if (chromaFormat == 3) 1 else 2
        val cropUnitY = (if (chromaFormat == 1) 2 else 1) * (2 - frameMbsOnly)
        val w = wMbs * 16 - (cl + cr) * cropUnitX
        val h = (2 - frameMbsOnly) * hMapUnits * 16 - (ct + cb) * cropUnitY
        if (w in 16..8192 && h in 16..8192) w to h else null
    } catch (_: Throwable) { null }

    private fun skipScaling(r: Bits, size: Int) {
        var last = 8; var next = 8
        for (j in 0 until size) {
            if (next != 0) { next = (last + r.se() + 256) % 256 }
            last = if (next == 0) last else next
        }
    }

    private fun unescape(b: ByteArray): ByteArray {
        val o = java.io.ByteArrayOutputStream(b.size)
        var zeros = 0
        for (x in b) {
            if (zeros >= 2 && x.toInt() == 3) { zeros = 0; continue }
            o.write(x.toInt())
            zeros = if (x.toInt() == 0) zeros + 1 else 0
        }
        return o.toByteArray()
    }

    private class Bits(val d: ByteArray) {
        var pos = 0
        fun bit(): Int { val v = (d[pos ushr 3].toInt() ushr (7 - (pos and 7))) and 1; pos++; return v }
        fun bits(n: Int): Int { var v = 0; repeat(n) { v = (v shl 1) or bit() }; return v }
        fun ue(): Int {
            var z = 0
            while (bit() == 0) { z++; if (z > 31) throw IllegalStateException() }
            return (1 shl z) - 1 + bits(z)
        }
        fun se(): Int { val k = ue(); return if (k and 1 == 1) (k + 1) / 2 else -(k / 2) }
    }
}
