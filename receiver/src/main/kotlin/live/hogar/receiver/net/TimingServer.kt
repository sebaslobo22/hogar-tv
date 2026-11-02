package live.hogar.receiver.net

import live.hogar.receiver.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Reloj NTP de AirPlay 1 (UDP). Hacemos dos cosas, como un receptor real: pedimos la hora al emisor
 * (puerto timingPort del cliente) cada 3 s y contestamos si el emisor nos pregunta a nosotros.
 * Hoy no se usa para sincronizar (se pinta en cuanto llega), pero el emisor espera ver el intercambio.
 */
internal class TimingServer(private val remote: InetAddress, private val remotePort: Int) {
    private val socket = DatagramSocket(0)
    val port: Int get() = socket.localPort
    @Volatile private var running = true
    @Volatile var offsetNanos: Long = 0
        private set

    init {
        Thread(::receiveLoop, "hogar-timing-rx").also { it.isDaemon = true }.start()
        if (remotePort > 0) Thread(::requestLoop, "hogar-timing-tx").also { it.isDaemon = true }.start()
    }

    fun stop() {
        running = false
        try { socket.close() } catch (_: Throwable) {}
    }

    private fun ntpNow(): Long {
        val ms = System.currentTimeMillis()
        val secs = ms / 1000 + 2208988800L
        val frac = ((ms % 1000) shl 32) / 1000
        return (secs shl 32) or frac
    }

    private fun put64(b: ByteArray, off: Int, v: Long) { for (i in 0 until 8) b[off + i] = (v ushr (56 - 8 * i)).toByte() }
    private fun get64(b: ByteArray, off: Int): Long { var v = 0L; for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xFF); return v }

    private fun requestLoop() {
        try {
            while (running) {
                val req = ByteArray(32)
                req[0] = 0x80.toByte(); req[1] = 0xd2.toByte(); req[2] = 0; req[3] = 7
                put64(req, 24, ntpNow())
                socket.send(DatagramPacket(req, 32, remote, remotePort))
                Thread.sleep(3000)
            }
        } catch (t: Throwable) {
            if (running) Log.w("timing: envio fallo", t)
        }
    }

    private fun receiveLoop() {
        val buf = ByteArray(128)
        try {
            while (running) {
                val p = DatagramPacket(buf, buf.size)
                socket.receive(p)
                if (p.length < 32) continue
                val type = buf[1].toInt() and 0x7F
                if (type == 0x52) { // peticion del emisor -> respuesta
                    val resp = ByteArray(32)
                    resp[0] = 0x80.toByte(); resp[1] = 0xd3.toByte(); resp[2] = 0; resp[3] = 7
                    System.arraycopy(buf, 24, resp, 8, 8) // referencia = su hora de envio
                    val now = ntpNow()
                    put64(resp, 16, now); put64(resp, 24, now)
                    socket.send(DatagramPacket(resp, 32, p.address, p.port))
                } else if (type == 0x53) { // respuesta a nuestra peticion: desfase = ((t2-t1)+(t3-t4))/2 en fracciones de segundo
                    val t1 = get64(buf, 8); val t2 = get64(buf, 16); val t3 = get64(buf, 24); val t4 = ntpNow()
                    offsetNanos = (((t2 - t1) + (t3 - t4)) / 2 * 1_000_000_000L) shr 32
                }
            }
        } catch (t: Throwable) {
            if (running) Log.w("timing: recepcion fallo", t)
        }
    }
}
