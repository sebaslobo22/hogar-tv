package live.hogar.receiver.mdns

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import live.hogar.receiver.Advertisement
import live.hogar.receiver.Features
import live.hogar.receiver.Identity
import live.hogar.receiver.Log

/**
 * Anuncia el receptor por mDNS/Bonjour con NsdManager: _airplay._tcp (nombre "TV Daewoo") y
 * _raop._tcp ("<DEVICEID>@TV Daewoo"). Mantiene un MulticastLock mientras esta anunciado.
 */
internal class Mdns(private val context: Context, private val identity: Identity, private val port: Int) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var lock: WifiManager.MulticastLock? = null
    private val listeners = ArrayList<NsdManager.RegistrationListener>()

    fun start() {
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            lock = wifi.createMulticastLock("hogar-airplay").apply { setReferenceCounted(false); acquire() }
        } catch (t: Throwable) {
            Log.w("no se pudo tomar el MulticastLock", t)
        }
        register("_airplay._tcp", Features.NAME, Advertisement.airplayTxt(identity))
        register("_raop._tcp", "${identity.deviceIdPlain}@${Features.NAME}", Advertisement.raopTxt(identity))
    }

    private fun register(type: String, name: String, txt: Map<String, String>) {
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = type
            port = this@Mdns.port
            txt.forEach { (k, v) -> setAttribute(k, v) }
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) { Log.i("mDNS registrado: ${i.serviceName} $type puerto $port") }
            override fun onRegistrationFailed(i: NsdServiceInfo, errorCode: Int) { Log.e("mDNS fallo $type (codigo $errorCode)") }
            override fun onServiceUnregistered(i: NsdServiceInfo) { Log.i("mDNS dado de baja: ${i.serviceName}") }
            override fun onUnregistrationFailed(i: NsdServiceInfo, errorCode: Int) { Log.w("mDNS baja fallo ($errorCode)") }
        }
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
            listeners.add(l)
        } catch (t: Throwable) {
            Log.e("registerService $type", t)
        }
    }

    fun stop() {
        listeners.forEach { try { nsd.unregisterService(it) } catch (_: Throwable) {} }
        listeners.clear()
        try { lock?.release() } catch (_: Throwable) {}
        lock = null
    }
}
