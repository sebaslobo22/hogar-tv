package live.hogar.airplay

import android.content.Context
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import live.hogar.core.AudioOutput
import live.hogar.core.AudioOutputFactory
import live.hogar.core.OutputState
import live.hogar.core.PcmFormat
import live.hogar.core.Speaker
import kotlin.math.PI
import kotlin.math.sin

/*
 * Módulo AirPlay (emisor) de Hogar. Mapa de archivos: si Apple cambia el protocolo, normalmente sólo
 * hay que tocar UNO de estos:
 *
 *   Discovery.kt  AirPlayDiscovery: mDNS (_airplay/_raop), fusión de registros, pares estéreo
 *   Pairing.kt    interfaz Pairing + emparejamiento HAP transitorio (PIN 3939)
 *   Srp.kt        SRP-6a (3072 bits, SHA-512) · Tlv8.kt TLV de HAP · Crypto.kt HKDF + ChaCha20-Poly1305
 *   Rtsp.kt       interfaz RtspClient: control RTSP/HTTP, tramas HAP cifradas
 *   Session.kt    flujo completo con un receptor (info, pairing, SETUP, eventos, RECORD, FLUSH, volumen)
 *   Rtp.kt        interfaz RtpSender: audio UDP cifrado, sincronía, tiempo NTP, retransmisión
 *   Alac.kt       interfaz AudioEncoder: PCM L16 y ALAC "verbatim"
 *   Plist.kt      plist binario
 *   Output.kt     AudioOutput: ritmo por reloj RTP, volumen, flush
 *
 * Fuentes de referencia: pyatv (carpetas protocols/raop, protocols/airplay y auth), OwnTone y la
 * documentación de AirPlay 2 de Emanuele Cozzi / openairplay.
 */

/**
 * Crea salidas AirPlay.
 *
 * @param codec PCM (por defecto, el camino que usa pyatv con HomePod) o ALAC verbatim.
 * @param latencyMs retraso de reproducción pedido al receptor (250..2000). Más bajo = menos desfase
 *   entre imagen y sonido, pero menos margen para la red.
 */
class AirPlayOutputFactory(
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val codec: AirPlayCodec = AirPlayCodec.PCM,
    private val latencyMs: Int = 1500,
) : AudioOutputFactory {

    private val config get() = AirPlayConfig(codec = codec, latencyMs = latencyMs)

    /**
     * Uno o varios altavoces. Un par estéreo (o sus dos miembros pasados por separado) cuenta como
     * **un** destino: se transmite al líder y el par reparte los canales; si el líder rechaza la
     * sesión se prueba el otro miembro.
     */
    override fun create(speakers: List<Speaker>): AudioOutput = AirPlayOutput(TargetResolver.resolve(speakers), config)

    /**
     * Regresión tras una actualización de Apple o del altavoz: conecta, transmite 10 s (2 s de silencio,
     * 6 s de tono de 440 Hz a volumen bajo y 2 s de silencio) y desconecta. Devuelve un resumen o el
     * error en español. El tono hay que oírlo para confirmar el audio; `success` garantiza que el
     * protocolo completo (emparejar, cifrar, SETUP, RTP, tiempo) funcionó.
     */
    suspend fun selfTest(speaker: Speaker, volume: Float = 0.25f): Result<String> {
        val output = AirPlayOutput(TargetResolver.resolve(listOf(speaker)), config)
        return try {
            withTimeout(60_000) {
                output.connect(PcmFormat())
                output.setVolume(volume)
                val chunk = ByteArray(AirPlayOutput.PACKET_BYTES * 4)
                val total = 10 * 44_100
                var frame = 0
                while (frame < total) {
                    val n = minOf(chunk.size / 4, total - frame)
                    for (i in 0 until n) {
                        val sec = (frame + i) / 44_100.0
                        val v = if (sec in 2.0..8.0) (sin(2 * PI * 440.0 * sec) * 3_000).toInt() else 0
                        val lo = v.toByte()
                        val hi = (v shr 8).toByte()
                        chunk[i * 4] = lo; chunk[i * 4 + 1] = hi; chunk[i * 4 + 2] = lo; chunk[i * 4 + 3] = hi
                    }
                    output.write(chunk, 0, n * 4)
                    frame += n
                }
                val state = output.state.value
                if (state is OutputState.Error) return@withTimeout Result.failure<String>(AirPlayException(state.message))
                val all = output.stats()
                val stats = all.joinToString("; ") { (name, st) -> "$name: ${st ?: "sin datos"}" }
                val heard = all.any { (it.second?.timingRequests?.get() ?: 0) > 0 }
                Result.success(
                    "Autodiagnóstico AirPlay correcto (${codec.name}, ${output.latencyMs} ms de latencia). $stats." +
                        if (heard) "" else " Aviso: el altavoz no pidió la hora, puede que no esté reproduciendo.",
                )
            }
        } catch (e: TimeoutCancellationException) {
            Result.failure(AirPlayException("El autodiagnóstico tardó demasiado: el altavoz no respondió a tiempo"))
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            runCatching { output.disconnect() }
        }
    }
}

/** Agrupa los altavoces pedidos en destinos: un par estéreo (aunque lleguen sus dos miembros sueltos) es un solo destino. */
internal object TargetResolver {
    fun resolve(speakers: List<Speaker>): List<Target> {
        val seenGroups = HashSet<String>()
        val targets = ArrayList<Target>()
        for (sp in speakers) {
            val members = SpeakerMerger.membersOf(sp)
            val gid = sp.txt["gid"]?.takeIf { it.isNotEmpty() }
            if (gid != null && members.size == 1) {
                val sibling = speakers.firstOrNull { it !== sp && it.txt["gid"] == gid }
                if (sibling != null) {
                    if (!seenGroups.add(gid)) continue
                    targets += Target(SpeakerMerger.leaderFirst(listOf(sp, sibling)))
                    continue
                }
            }
            targets += Target(members)
        }
        return targets
    }
}
