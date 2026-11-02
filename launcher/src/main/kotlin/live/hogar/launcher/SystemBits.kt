package live.hogar.launcher

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.provider.Settings

/** Acciones del sistema que usa la pantalla de inicio. */
internal object SystemBits {

    /** Abre una app con fundido (sin la animación de escala del sistema: la dibuja Hogar). */
    fun launchApp(ctx: Context, app: AppEntry): Boolean {
        val options = ActivityOptions.makeCustomAnimation(ctx, android.R.anim.fade_in, R.anim.hogar_hold).toBundle()
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(if (app.leanback) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
            .setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            ctx.startActivity(intent, options)
            true
        } catch (_: Exception) {
            val fallback = ctx.packageManager.getLaunchIntentForPackage(app.packageName)
            if (fallback == null) false
            else runCatching { ctx.startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options) }.isSuccess
        }
    }

    fun openSettings(ctx: Context) {
        runCatching { ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    enum class NetKind { None, Wifi, Ethernet, Other }

    /** [ssid] solo si el sistema lo entrega (Android 10+ pide permiso de ubicación). */
    data class NetInfo(val kind: NetKind, val ssid: String?, val needsLocation: Boolean)

    @Suppress("DEPRECATION")
    fun network(ctx: Context): NetInfo {
        val app = ctx.applicationContext
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return NetInfo(NetKind.None, null, false)
        val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
            ?: return NetInfo(NetKind.None, null, false)
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetInfo(NetKind.Ethernet, null, false)
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                val granted = app.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
                val raw = runCatching { app.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid }.getOrNull()
                val name = raw?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" && it != "0x" }
                NetInfo(NetKind.Wifi, name, needsLocation = !granted && name == null)
            }
            else -> NetInfo(NetKind.Other, null, false)
        }
    }
}
