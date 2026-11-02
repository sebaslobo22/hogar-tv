package live.hogar.receiver

import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Receptor AirPlay (pantalla y audio desde iPhone, iPad y Mac). Lo arranca HogarApp.
 * Se anuncia como "TV Daewoo" en el puerto TCP 7100 (o el siguiente libre; AirScreen puede tener el 7000).
 */
object AirPlayReceiver {
    private var core: AirPlayCore? = null
    private val lock = Any()

    fun start(context: Context) {
        val app = context.applicationContext
        Thread({
            try {
                // El servicio en primer plano mantiene vivo el proceso mientras se transmite.
                try {
                    val i = Intent(app, ReceiverService::class.java)
                    if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i) else app.startService(i)
                } catch (t: Throwable) {
                    Log.w("no se pudo iniciar el servicio en primer plano; se arranca directo", t)
                }
                startCore(app)
            } catch (t: Throwable) {
                Log.e("no se pudo iniciar el receptor AirPlay", t)
            }
        }, "hogar-receiver-start").also { it.isDaemon = true }.start()
    }

    fun stop() {
        synchronized(lock) {
            try { core?.stop() } catch (t: Throwable) { Log.w("stop", t) }
            core = null
        }
    }

    internal fun startCore(context: Context) {
        synchronized(lock) {
            if (core != null) return
            val c = AirPlayCore(context.applicationContext)
            c.start()
            core = c
        }
    }
}
