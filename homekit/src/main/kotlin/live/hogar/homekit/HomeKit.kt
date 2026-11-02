package live.hogar.homekit

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import live.hogar.core.TvPower

/** Lo que muestra la pantalla de HomeKit. */
data class HomeKitStatus(
    val running: Boolean = false,
    val advertising: Boolean = false,
    val paired: Boolean = false,
    val setupCode: String = "",
    val qrPayload: String = "",
    val controllers: List<String> = emptyList(),
    val port: Int = 0,
    val error: String? = null,
)

private class PrefsKv(private val p: SharedPreferences) : Kv {
    override fun get(key: String): String? = p.getString(key, null)
    override fun put(key: String, value: String) { p.edit().putString(key, value).apply() }
    override fun remove(key: String) { p.edit().remove(key).apply() }
    override fun keys(): Set<String> = p.all.keys.toSet()
}

/**
 * Accesorio HomeKit "Televisión" de Hogar (HAP sobre IP). `HomeKit.start(app)` arranca todo en
 * un hilo propio y nunca lanza: si algo falla queda registrado y el estado lo muestra.
 */
object HomeKit {
    private const val TAG = "HogarHomeKit"

    private val _status = MutableStateFlow(HomeKitStatus())
    val status: StateFlow<HomeKitStatus> get() = _status

    private class Runtime(
        val app: Application,
        val store: HomeKitStore,
        val backend: AndroidTvBackend,
        val pairing: PairingManager,
        val server: HapServer,
        val advertiser: Advertiser,
        val tv: AtomicReference<TvController>,
        val exec: ScheduledExecutorService,
    )

    @Volatile private var rt: Runtime? = null
    @Volatile private var starting = false

    @Synchronized
    fun start(app: Application) {
        if (rt != null || starting) return
        starting = true
        Thread(null, { init(app) }, "hk-start", 256 * 1024).apply { isDaemon = true }.start()
    }

    private fun init(app: Application) {
        try {
            val store = HomeKitStore(PrefsKv(app.getSharedPreferences("homekit", Context.MODE_PRIVATE)))
            val backend = AndroidTvBackend(app)
            val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(null, r, "hk-events", 256 * 1024).apply { isDaemon = true } }
            val tvRef = AtomicReference<TvController>()
            tvRef.set(buildController(app, store, backend))
            lateinit var runtime: Runtime
            val pairing = PairingManager(
                store,
                onChanged = { exec.execute { publishStatus(); runtime.advertiser.update(txtRecord(store, tvRef.get())) } },
                closeController = { id -> runtime.server.closeController(id) },
            )
            val router = HapRouter(store, pairing, { tvRef.get() }, afterWrite = { conn -> scheduleRefresh(conn) })
            val server = HapServer(router, pairing) { Log.d(TAG, it) }
            val advertiser = Advertiser(app)
            runtime = Runtime(app, store, backend, pairing, server, advertiser, tvRef, exec)
            rt = runtime

            val port = server.start()
            advertiser.onStateChanged = { publishStatus() }
            advertiser.start(port, txtRecord(store, tvRef.get()))
            registerReceivers(runtime)
            TvPower.onForegroundChanged = { scheduleRefresh(null, 150) }
            exec.execute { tvRef.get().refresh() }  // primera foto, para detectar cambios después
            publishStatus(port)
            Log.i(TAG, "HomeKit listo en el puerto $port (c#=${tvRef.get().let { store.configNumber(it.db.signature()) }})")
        } catch (t: Throwable) {
            Log.e(TAG, "no se pudo iniciar HomeKit", t)
            _status.value = _status.value.copy(running = false, error = t.message ?: t.javaClass.simpleName)
        } finally {
            starting = false
        }
    }

    private fun buildController(app: Application, store: HomeKitStore, backend: TvBackend): TvController {
        val inputs = InputScanner.scan(app, store)
        return TvController(AccessoryDb(inputs), store, backend)
    }

    private fun txtRecord(store: HomeKitStore, tv: TvController): Map<String, String> = linkedMapOf(
        "c#" to store.configNumber(tv.db.signature()).toString(),
        "ff" to "0",
        "id" to store.deviceId,
        "md" to "Hogar TV",
        "pv" to "1.1",
        "s#" to "1",
        "sf" to if (store.isPaired()) "0" else "1",
        "ci" to Setup.CATEGORY_TELEVISION.toString(),
        "sh" to store.setupHash,
    )

    // ---------------------------------------------------------------- estado y eventos

    private fun publishStatus(port: Int? = null) {
        val r = rt ?: return
        val paired = r.store.isPaired()
        _status.value = HomeKitStatus(
            running = true,
            advertising = r.advertiser.registered,
            paired = paired,
            setupCode = r.store.setupCode,
            qrPayload = r.store.qrPayload,
            controllers = r.store.pairings().map { it.id },
            port = port ?: _status.value.port,
            error = null,
        )
    }

    private val pendingRefresh = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Relee el estado en el hilo de eventos y avisa de lo que cambió a los suscritos (salvo [except]). */
    private fun scheduleRefresh(except: Conn?, delayMs: Long = 250) {
        val r = rt ?: return
        if (!pendingRefresh.compareAndSet(false, true)) return
        r.exec.schedule({
            pendingRefresh.set(false)
            try {
                val tv = r.tv.get()
                val changed = tv.refresh()
                if (changed.isNotEmpty()) r.server.notifyChanged(changed, { tv.read(it) }, except)
            } catch (t: Throwable) {
                Log.w(TAG, "refresco: ${t.message}")
            }
        }, delayMs, TimeUnit.MILLISECONDS)
        // Tras una orden, se vuelve a mirar cuando expira el valor optimista por si la acción no ocurrió.
        if (except != null) {
            r.exec.schedule({ scheduleRefresh(null, 0) }, TvController.HOLD_MS + 300, TimeUnit.MILLISECONDS)
        }
    }

    private fun registerReceivers(r: Runtime) {
        val app = r.app
        val state = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = scheduleRefresh(null, 400)
        }
        register(app, state, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
            addAction("android.media.VOLUME_CHANGED_ACTION"); addAction("android.media.STREAM_MUTE_CHANGED_ACTION")
        })
        val packages = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { r.exec.schedule({ rescan() }, 3, TimeUnit.SECONDS) }
        }
        register(app, packages, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addDataScheme("package")
        })
    }

    private fun register(app: Application, r: BroadcastReceiver, f: IntentFilter) {
        if (android.os.Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(r, f)
    }

    /** Vuelve a leer las apps instaladas; si cambian, sube c# y se re-anuncia. */
    private fun rescan() {
        val r = rt ?: return
        try {
            val next = buildController(r.app, r.store, r.backend)
            if (next.db.signature() == r.tv.get().db.signature()) return
            r.tv.set(next)
            r.advertiser.update(txtRecord(r.store, next))
            r.exec.execute { next.refresh() }
        } catch (t: Throwable) {
            Log.w(TAG, "rescan: ${t.message}")
        }
    }

    /** Borra emparejamientos y estrena código; cierra conexiones y actualiza el anuncio. */
    fun resetPairing() {
        val r = rt ?: return
        r.exec.execute {
            try {
                r.store.resetPairing()
                r.server.closeAll()
                r.advertiser.update(txtRecord(r.store, r.tv.get()))
                publishStatus()
            } catch (t: Throwable) {
                Log.w(TAG, "reset: ${t.message}")
            }
        }
    }
}
