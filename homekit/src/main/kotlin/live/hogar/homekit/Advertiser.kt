package live.hogar.homekit

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log

/**
 * Anuncio mDNS `_hap._tcp` con NsdManager. NsdManager no sabe actualizar el TXT: para cambiarlo
 * se da de baja y se vuelve a dar de alta, esperando a que termine la baja. Mientras anuncia
 * mantiene un MulticastLock y vuelve a anunciar cuando cambia la red.
 */
internal class Advertiser(private val ctx: Context) {
    private val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val thread = HandlerThread("hk-mdns").apply { start() }
    private val handler = Handler(thread.looper)

    private var lock: WifiManager.MulticastLock? = null
    private var listener: NsdManager.RegistrationListener? = null
    private var port = 0
    private var txt: Map<String, String> = emptyMap()
    private var wanted = false
    private var unregistering = false
    private var retry = 0
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile var registered = false
        private set
    @Volatile var onStateChanged: (() -> Unit)? = null

    fun start(port: Int, txt: Map<String, String>) = handler.post {
        this.port = port
        this.txt = txt
        wanted = true
        acquireLock()
        watchNetwork()
        restart()
    }

    fun update(txt: Map<String, String>) = handler.post {
        if (this.txt == txt) return@post
        this.txt = txt
        if (wanted) restart()
    }

    fun stop() = handler.post {
        wanted = false
        netCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        netCallback = null
        unregister()
        runCatching { lock?.release() }
        lock = null
    }

    private fun acquireLock() {
        if (lock != null) return
        runCatching {
            val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            lock = wifi.createMulticastLock("hogar-homekit").apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun watchNetwork() {
        if (netCallback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            private var first = true
            override fun onAvailable(network: Network) {
                if (first) { first = false; return }   // el aviso inicial no es un cambio
                handler.removeCallbacks(networkChanged)
                handler.postDelayed(networkChanged, 2_500)
            }
        }
        netCallback = cb
        runCatching { cm.registerDefaultNetworkCallback(cb, handler) }.onFailure { netCallback = null }
    }

    private val networkChanged: Runnable = Runnable { if (wanted) { retry = 0; restart() } }

    private fun restart() {
        handler.removeCallbacks(doRegister)
        if (listener != null) {
            unregister()          // doRegister se lanza desde onServiceUnregistered
        } else {
            handler.post(doRegister)
        }
    }

    private val doRegister: Runnable = Runnable {
        if (!wanted || listener != null || unregistering) return@Runnable
        val info = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = "_hap._tcp"
            port = this@Advertiser.port
            txt.forEach { (k, v) -> setAttribute(k, v) }
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {
                registered = true; retry = 0
                Log.i(TAG, "anunciado como ${i.serviceName} en el puerto $port")
                onStateChanged?.invoke()
            }
            override fun onRegistrationFailed(i: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "falló el anuncio mDNS: $errorCode")
                handler.post { listener = null; registered = false; onStateChanged?.invoke(); scheduleRetry() }
            }
            override fun onServiceUnregistered(i: NsdServiceInfo) {
                handler.post {
                    registered = false; listener = null; unregistering = false
                    onStateChanged?.invoke()
                    if (wanted) handler.post(doRegister)
                }
            }
            override fun onUnregistrationFailed(i: NsdServiceInfo, errorCode: Int) {
                handler.post { listener = null; unregistering = false; if (wanted) scheduleRetry() }
            }
        }
        listener = l
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }.onFailure {
            Log.w(TAG, "registerService: ${it.message}")
            listener = null
            scheduleRetry()
        }
    }

    private fun unregister() {
        val l = listener ?: return
        if (unregistering) return
        unregistering = true
        registered = false
        runCatching { nsd.unregisterService(l) }.onFailure { listener = null; unregistering = false }
    }

    private fun scheduleRetry() {
        if (!wanted) return
        val delay = minOf(60_000L, 3_000L shl minOf(retry++, 4))
        handler.removeCallbacks(doRegister)
        handler.postDelayed(doRegister, delay)
    }

    private companion object {
        const val TAG = "HogarHomeKit"
        const val SERVICE_NAME = "Hogar TV"
    }
}
