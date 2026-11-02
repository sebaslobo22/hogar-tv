package live.hogar.airplay

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.nio.ByteBuffer
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Tiempo NTP (segundos desde 1900 + fracción de 32 bits). */
internal data class NtpTime(val seconds: Long, val fraction: Long) {
    companion object {
        private const val EPOCH_OFFSET = 0x83AA7E80L // 1900 → 1970

        fun now(): NtpTime {
            val i = Instant.now()
            return NtpTime(i.epochSecond + EPOCH_OFFSET, (i.nano.toLong() shl 32) / 1_000_000_000L)
        }
    }
}

/** Construcción y lectura de paquetes RAOP/AirPlay (formatos de pyatv/protocols/raop/packets.py). */
internal object RtpPackets {
    const val TYPE_TIMING_REQUEST = 0x52
    const val TYPE_TIMING_RESPONSE = 0x53
    const val TYPE_SYNC = 0x54
    const val TYPE_RETRANSMIT_REQUEST = 0x55
    const val TYPE_RETRANSMIT_RESPONSE = 0x56

    /** Cabecera de audio (12 bytes): 0x80, 0x60 (0xE0 con marca en el primero), seq, timestamp, ssrc. */
    fun audioHeader(seq: Int, timestamp: Long, ssrc: Long, first: Boolean): ByteArray =
        ByteBuffer.allocate(12)
            .put(0x80.toByte())
            .put((if (first) 0xE0 else 0x60).toByte())
            .putShort(seq.toShort())
            .putInt(timestamp.toInt())
            .putInt(ssrc.toInt())
            .array()

    /** Paquete de sincronía (20 bytes) para el puerto de control. */
    fun sync(first: Boolean, nowWithoutLatency: Long, ntp: NtpTime, nowRtp: Long): ByteArray =
        ByteBuffer.allocate(20)
            .put((if (first) 0x90 else 0x80).toByte())
            .put(0xD4.toByte())
            .putShort(7)
            .putInt(nowWithoutLatency.toInt())
            .putInt(ntp.seconds.toInt())
            .putInt(ntp.fraction.toInt())
            .putInt(nowRtp.toInt())
            .array()

    /** Respuesta a una petición de tiempo (32 bytes): eco del envío del cliente + hora local. */
    fun timingResponse(request: ByteArray, now: NtpTime): ByteArray {
        val rq = ByteBuffer.wrap(request)
        val out = ByteBuffer.allocate(32)
        out.put(request[0])
        out.put((0x80 or TYPE_TIMING_RESPONSE).toByte())
        out.putShort(7)
        out.putInt(0)
        out.putInt(rq.getInt(24)).putInt(rq.getInt(28)) // reftime = envío del cliente
        out.putInt(now.seconds.toInt()).putInt(now.fraction.toInt()) // recepción
        out.putInt(now.seconds.toInt()).putInt(now.fraction.toInt()) // envío
        return out.array()
    }

    /** (primer seq perdido, cantidad) o null si no es una petición de retransmisión. */
    fun parseRetransmitRequest(data: ByteArray, len: Int): Pair<Int, Int>? {
        if (len < 8 || (data[1].toInt() and 0x7F) != TYPE_RETRANSMIT_REQUEST) return null
        val bb = ByteBuffer.wrap(data, 0, len)
        return (bb.getShort(4).toInt() and 0xFFFF) to (bb.getShort(6).toInt() and 0xFFFF)
    }

    /** Envuelve un paquete guardado como respuesta de retransmisión: 0x80 0xD6 + seq original + paquete. */
    fun retransmitResponse(original: ByteArray): ByteArray {
        val out = ByteArray(original.size + 4)
        out[0] = 0x80.toByte(); out[1] = 0xD6.toByte()
        out[2] = original[2]; out[3] = original[3]
        System.arraycopy(original, 0, out, 4, original.size)
        return out
    }
}

/**
 * Salida RTP hacia un receptor. Si Apple cambia el transporte de audio (otro cifrado,
 * otros puertos), sólo hay que reemplazar esta interfaz.
 */
internal interface RtpSender : AutoCloseable {
    /** Puerto local de control (recibe peticiones de retransmisión; envía sincronía). */
    val controlPort: Int

    /** Puerto local de tiempo (el receptor nos consulta la hora NTP). */
    val timingPort: Int
    val stats: RtpStats

    /** Apunta a los puertos que el receptor indicó en el SETUP y empieza a enviar. */
    fun connect(remote: InetAddress, dataPort: Int, remoteControlPort: Int)
    fun sendAudio(seq: Int, timestamp: Long, payload: ByteArray, first: Boolean)
    fun sendSync(nowWithoutLatency: Long, ntp: NtpTime, nowRtp: Long)
}

internal class RtpStats {
    val packetsSent = AtomicInteger()
    val syncSent = AtomicInteger()
    val timingRequests = AtomicInteger()
    val retransmitRequests = AtomicInteger()
    val retransmitted = AtomicInteger()
    override fun toString() =
        "paquetes=${packetsSent.get()} sync=${syncSent.get()} tiempo=${timingRequests.get()} " +
            "retransmisiones=${retransmitted.get()}/${retransmitRequests.get()}"
}

/**
 * Implementación UDP de [RtpSender]: audio cifrado con ChaCha20-Poly1305 (clave `shk` del SETUP),
 * sincronía cada segundo, servidor de tiempo NTP y reenvío de paquetes perdidos.
 *
 * Paquete de audio AirPlay 2 = cabecera(12) + cifrado(carga útil)+etiqueta(16) + nonce(8, little-endian);
 * el AAD son los bytes 4..11 de la cabecera (marca de tiempo + ssrc). Ver
 * `AirPlayV2.send_audio_packet` en pyatv.
 */
internal class UdpRtpSender(
    private val sharedKey: ByteArray?,
    private val ssrc: Long,
) : RtpSender {
    private val control = DatagramSocket(0)
    private val timing = DatagramSocket(0)
    private val audio = DatagramSocket()
    private var remote: InetAddress? = null
    private var remoteControl: InetSocketAddress? = null
    private var dataAddr: InetSocketAddress? = null
    private var nonceCounter = 0L
    private val backlog = arrayOfNulls<ByteArray>(BACKLOG)
    private val backlogSeq = IntArray(BACKLOG) { -1 }
    override val stats = RtpStats()

    override val controlPort: Int get() = control.localPort
    override val timingPort: Int get() = timing.localPort

    init {
        thread(name = "airplay-timing", isDaemon = true) {
            val buf = ByteArray(128)
            while (!timing.isClosed) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    timing.receive(p)
                    if (p.length >= 32 && (buf[1].toInt() and 0x7F) == RtpPackets.TYPE_TIMING_REQUEST) {
                        stats.timingRequests.incrementAndGet()
                        val resp = RtpPackets.timingResponse(buf.copyOf(p.length), NtpTime.now())
                        timing.send(DatagramPacket(resp, resp.size, p.socketAddress))
                    }
                } catch (_: SocketException) {
                    return@thread
                } catch (e: Exception) {
                    APLog.d("timing: ${e.message}")
                }
            }
        }
        thread(name = "airplay-control", isDaemon = true) {
            val buf = ByteArray(128)
            while (!control.isClosed) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    control.receive(p)
                    val req = RtpPackets.parseRetransmitRequest(buf, p.length) ?: continue
                    stats.retransmitRequests.incrementAndGet()
                    for (i in 0 until req.second) {
                        val seq = (req.first + i) and 0xFFFF
                        val pkt = synchronized(backlog) {
                            if (backlogSeq[seq % BACKLOG] == seq) backlog[seq % BACKLOG] else null
                        } ?: continue
                        val resp = RtpPackets.retransmitResponse(pkt)
                        control.send(DatagramPacket(resp, resp.size, p.socketAddress))
                        stats.retransmitted.incrementAndGet()
                    }
                } catch (_: SocketException) {
                    return@thread
                } catch (e: Exception) {
                    APLog.d("control: ${e.message}")
                }
            }
        }
    }

    override fun connect(remote: InetAddress, dataPort: Int, remoteControlPort: Int) {
        this.remote = remote
        dataAddr = InetSocketAddress(remote, dataPort)
        remoteControl = InetSocketAddress(remote, remoteControlPort)
    }

    override fun sendAudio(seq: Int, timestamp: Long, payload: ByteArray, first: Boolean) {
        val header = RtpPackets.audioHeader(seq, timestamp, ssrc, first)
        val packet: ByteArray
        if (sharedKey != null) {
            val nonce8 = ByteArray(8) { (nonceCounter ushr (8 * it)).toByte() }
            val nonce12 = ByteArray(12).also { System.arraycopy(nonce8, 0, it, 4, 8) }
            nonceCounter++
            val sealed = ChaCha20Poly1305.seal(sharedKey, nonce12, payload, header.copyOfRange(4, 12))
            packet = header + sealed + nonce8
        } else {
            packet = header + payload
        }
        synchronized(backlog) {
            backlog[seq % BACKLOG] = packet
            backlogSeq[seq % BACKLOG] = seq
        }
        val to = dataAddr ?: throw IllegalStateException("RTP sin destino")
        audio.send(DatagramPacket(packet, packet.size, to))
        stats.packetsSent.incrementAndGet()
    }

    override fun sendSync(nowWithoutLatency: Long, ntp: NtpTime, nowRtp: Long) {
        val to = remoteControl ?: return
        val first = stats.syncSent.getAndIncrement() == 0
        val pkt = RtpPackets.sync(first, nowWithoutLatency, ntp, nowRtp)
        control.send(DatagramPacket(pkt, pkt.size, to))
    }

    override fun close() {
        control.close(); timing.close(); audio.close()
    }

    private companion object {
        const val BACKLOG = 1024
    }
}
