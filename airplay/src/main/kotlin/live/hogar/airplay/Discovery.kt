package live.hogar.airplay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import live.hogar.core.Speaker
import live.hogar.core.SpeakerDiscovery
import java.net.Inet4Address
import java.util.Locale

/** Un registro mDNS ya resuelto (`_airplay._tcp` o `_raop._tcp`). */
internal data class MdnsRecord(
    val type: String,
    val name: String,
    val host: String,
    val port: Int,
    val txt: Map<String, String>,
)

/**
 * Lógica pura (sin Android) que fusiona los registros `_airplay._tcp` y `_raop._tcp` de cada
 * dispositivo y agrupa los pares estéreo. Separada de [AirPlayDiscovery] para poder probarla.
 */
internal object SpeakerMerger {
    /** Claves que añadimos al `txt` de un altavoz que representa un par estéreo. */
    const val KEY_MEMBERS = "hogar.members"
    const val KEY_LEADER = "hogar.leader"

    fun normalizeId(raw: String): String {
        val hex = raw.replace(":", "").replace("-", "").uppercase(Locale.ROOT)
        return if (hex.length == 12 && hex.all { it in '0'..'9' || it in 'A'..'F' }) hex.chunked(2).joinToString(":") else raw
    }

    private fun deviceKey(r: MdnsRecord): String {
        val id = if (r.type == TYPE_AIRPLAY) r.txt["deviceid"] else r.name.substringBefore('@', "")
        return if (!id.isNullOrBlank()) normalizeId(id) else r.name
    }

    /** Fusiona los registros por dispositivo y agrupa pares estéreo. */
    fun merge(records: Collection<MdnsRecord>): List<Speaker> {
        val byDevice = records.groupBy(::deviceKey)
        val singles = byDevice.mapNotNull { (id, recs) -> mergeDevice(id, recs) }
        return groupPairs(singles).sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    private fun mergeDevice(id: String, recs: List<MdnsRecord>): Speaker? {
        val ap = recs.firstOrNull { it.type == TYPE_AIRPLAY }
        val raop = recs.firstOrNull { it.type == TYPE_RAOP }
        val primary = ap ?: raop ?: return null
        val txt = LinkedHashMap<String, String>()
        raop?.txt?.let(txt::putAll)
        ap?.txt?.let(txt::putAll)
        // Nombres homogéneos, vengan de donde vengan.
        fun alias(target: String, vararg from: String) {
            if (txt[target].isNullOrEmpty()) from.firstNotNullOfOrNull { txt[it]?.takeIf(String::isNotEmpty) }?.let { txt[target] = it }
        }
        alias("model", "am")
        alias("features", "ft")
        alias("flags", "sf")
        alias("srcvers", "vs")
        txt.putIfAbsent("deviceid", id)
        if (raop != null) txt["raop.port"] = raop.port.toString()
        if (ap != null) txt["airplay.port"] = ap.port.toString()
        // Un dispositivo sin audio (p. ej. sólo vídeo) no sirve como altavoz.
        if (raop == null && !supportsAudio(txt["features"])) return null
        val name = when {
            ap != null -> ap.name
            else -> raop!!.name.substringAfter('@')
        }
        return Speaker(id = id, name = name, host = primary.host, port = primary.port, model = txt["model"], txt = txt)
    }

    /** features = "0xLOW,0xHIGH" o "0xLOW"; el bit 9 es “soporta audio”. */
    fun supportsAudio(features: String?): Boolean {
        val low = features?.substringBefore(',')?.trim()?.removePrefix("0x")?.removePrefix("0X")?.toLongOrNull(16) ?: return false
        return (low shr 9) and 1L == 1L
    }

    private val SUFFIX = Regex("\\s*\\(\\d+\\)$")

    private fun baseName(n: String) = n.replace(SUFFIX, "").trim()

    /**
     * Un par estéreo = exactamente 2 dispositivos con el mismo `gid`, mismo modelo y nombres iguales
     * salvo el sufijo “ (2)”. Se expone como un único [Speaker] (id `par:<gid>`) con el anfitrión del
     * líder; `txt[KEY_MEMBERS]` lista “id|host|port|igl” de cada miembro, el líder primero.
     */
    private fun groupPairs(devices: List<Speaker>): List<Speaker> {
        val result = ArrayList<Speaker>()
        val byGid = devices.groupBy { it.txt["gid"].orEmpty() }
        for ((gid, members) in byGid) {
            val isPair = gid.isNotEmpty() && members.size == 2 &&
                members.map { it.model }.distinct().size == 1 &&
                members.map { baseName(it.name) }.distinct().size == 1
            if (!isPair) {
                result += members
                continue
            }
            val ordered = leaderFirst(members)
            val leader = ordered.first()
            val txt = LinkedHashMap(leader.txt)
            txt[KEY_LEADER] = leader.id
            txt[KEY_MEMBERS] = ordered.joinToString(";") { "${it.id}|${it.host}|${it.port}|${it.txt["igl"].orEmpty()}" }
            result += Speaker(
                id = "par:$gid",
                name = baseName(leader.name),
                host = leader.host,
                port = leader.port,
                model = leader.model,
                txt = txt,
            )
        }
        return result
    }

    /**
     * Orden de preferencia para el líder: `igl=1`; si ninguno lo anuncia (o los dos), el que no lleva
     * sufijo “ (2)”; por último, el id más bajo. El resto queda como alternativa si el primero falla.
     */
    fun leaderFirst(members: List<Speaker>): List<Speaker> =
        members.sortedWith(
            compareByDescending<Speaker> { it.txt["igl"] == "1" }
                .thenBy { SUFFIX.containsMatchIn(it.name) }
                .thenBy { it.id },
        )

    /** Miembros de un par, líder primero; para un altavoz suelto, sólo él. */
    fun membersOf(speaker: Speaker): List<Speaker> {
        val raw = speaker.txt[KEY_MEMBERS] ?: return listOf(speaker)
        return raw.split(';').mapNotNull { m ->
            val p = m.split('|')
            if (p.size < 3) return@mapNotNull null
            Speaker(p[0], speaker.name, p[1], p[2].toIntOrNull() ?: return@mapNotNull null, speaker.model, speaker.txt - KEY_MEMBERS - KEY_LEADER)
        }.ifEmpty { listOf(speaker) }
    }

    const val TYPE_AIRPLAY = "_airplay._tcp"
    const val TYPE_RAOP = "_raop._tcp"
}

/**
 * Descubrimiento mDNS de receptores AirPlay con [NsdManager].
 *
 * Escucha `_airplay._tcp` y `_raop._tcp`, resuelve cada servicio (uno a la vez: Android sólo admite
 * una resolución simultánea), lee los TXT (model, features, flags, gid, deviceid, pk, srcvers, igl…) y
 * fusiona ambos registros por dispositivo. Mantiene un `MulticastLock` mientras descubre.
 *
 * **Pares estéreo:** dos dispositivos con el mismo `gid`, mismo modelo y nombres iguales salvo
 * el sufijo “ (2)” se publican como **un solo** [Speaker] (id `par:<gid>`, nombre sin sufijo) cuyo
 * host/puerto son los del líder; `txt["hogar.members"]` lista los dos miembros y `txt["hogar.leader"]`
 * el id del líder. Así el usuario ve un único “Sala”, y [AirPlayOutputFactory] transmite
 * al líder (el par reparte los canales por sí solo) con el otro miembro como alternativa.
 */
class AirPlayDiscovery(context: Context) : SpeakerDiscovery {
    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val main = Handler(Looper.getMainLooper())

    private val _speakers = MutableStateFlow<List<Speaker>>(emptyList())
    override val speakers: StateFlow<List<Speaker>> = _speakers

    private val lock = Any()
    private val records = HashMap<String, MdnsRecord>()
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var running = false
    private var multicastLock: WifiManager.MulticastLock? = null
    private val listeners = HashMap<String, NsdManager.DiscoveryListener>()

    override fun start() {
        synchronized(lock) {
            if (running) return
            running = true
        }
        try {
            multicastLock = wifi.createMulticastLock("hogar-airplay").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            APLog.w("MulticastLock: ${e.message}")
        }
        for (type in listOf(SpeakerMerger.TYPE_AIRPLAY, SpeakerMerger.TYPE_RAOP)) {
            val l = listenerFor(type)
            listeners[type] = l
            try {
                nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l)
            } catch (e: Exception) {
                APLog.w("discoverServices($type): ${e.message}")
            }
        }
    }

    override fun stop() {
        synchronized(lock) {
            if (!running) return
            running = false
            queue.clear()
            records.clear()
        }
        listeners.values.forEach { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {} }
        listeners.clear()
        try { multicastLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        multicastLock = null
        _speakers.value = emptyList()
    }

    private fun listenerFor(type: String) = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {}
        override fun onDiscoveryStopped(serviceType: String) {}
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = APLog.w("mDNS $serviceType falló: $errorCode")
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

        override fun onServiceFound(info: NsdServiceInfo) {
            synchronized(lock) {
                if (!running) return
                queue.addLast(info)
            }
            pump()
        }

        override fun onServiceLost(info: NsdServiceInfo) {
            val removed = synchronized(lock) { records.remove("$type|${info.serviceName}") != null }
            if (removed) publish()
        }
    }

    private fun pump() {
        val next: NsdServiceInfo
        synchronized(lock) {
            if (resolving || queue.isEmpty() || !running) return
            resolving = true
            next = queue.removeFirst()
        }
        @Suppress("DEPRECATION")
        try {
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    APLog.d("resolve ${serviceInfo.serviceName} falló: $errorCode")
                    synchronized(lock) { resolving = false }
                    if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
                        synchronized(lock) { queue.addLast(serviceInfo) }
                        main.postDelayed(::pump, 500)
                    } else pump()
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    toRecord(serviceInfo)?.let { rec ->
                        synchronized(lock) { records["${rec.type}|${rec.name}"] = rec }
                        publish()
                    }
                    synchronized(lock) { resolving = false }
                    pump()
                }
            })
        } catch (e: Exception) {
            APLog.w("resolveService: ${e.message}")
            synchronized(lock) { resolving = false }
        }
    }

    private fun toRecord(info: NsdServiceInfo): MdnsRecord? {
        val type = info.serviceType.trim('.').lowercase(Locale.ROOT).let {
            when {
                it.startsWith("_airplay._tcp") -> SpeakerMerger.TYPE_AIRPLAY
                it.startsWith("_raop._tcp") -> SpeakerMerger.TYPE_RAOP
                else -> return null
            }
        }
        val host = hostOf(info) ?: return null
        val txt = LinkedHashMap<String, String>()
        info.attributes.forEach { (k, v) -> txt[k] = v?.toString(Charsets.UTF_8).orEmpty() }
        return MdnsRecord(type, info.serviceName, host, info.port, txt)
    }

    @Suppress("DEPRECATION")
    private fun hostOf(info: NsdServiceInfo): String? {
        if (Build.VERSION.SDK_INT >= 34) {
            val all = info.hostAddresses
            val v4 = all.firstOrNull { it is Inet4Address } ?: all.firstOrNull()
            if (v4 != null) return v4.hostAddress
        }
        return info.host?.hostAddress
    }

    private fun publish() {
        val snapshot = synchronized(lock) { records.values.toList() }
        // El receptor AirPlay del propio TV también se anuncia: elegirlo crearía un bucle.
        val own = localAddresses()
        _speakers.value = SpeakerMerger.merge(snapshot).filter { it.host !in own }
    }

    private fun localAddresses(): Set<String> = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .mapNotNull { it.hostAddress?.substringBefore('%') }
            .toSet()
    }.getOrDefault(emptySet())
}
