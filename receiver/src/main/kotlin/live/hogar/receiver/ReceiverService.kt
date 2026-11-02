package live.hogar.receiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/** Servicio en primer plano discreto que mantiene vivo el receptor AirPlay. */
class ReceiverService : Service() {
    private var wake: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(NotificationChannel("receptor", "Receptor AirPlay", NotificationManager.IMPORTANCE_MIN))
            }
            val n = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "receptor") else @Suppress("DEPRECATION") Notification.Builder(this)
            n.setContentTitle("Receptor AirPlay activo").setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, n.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(1, n.build())
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hogar:receptor").apply { setReferenceCounted(false); acquire() }
        } catch (t: Throwable) {
            Log.w("servicio: startForeground fallo", t)
        }
        AirPlayReceiver.startCore(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try { wake?.release() } catch (_: Throwable) {}
        super.onDestroy()
    }
}
