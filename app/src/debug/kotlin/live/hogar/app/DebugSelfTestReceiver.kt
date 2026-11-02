package live.hogar.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import live.hogar.airplay.AirPlayOutputFactory

/** adb shell am broadcast -a live.hogar.DEBUG_SELFTEST --es name "Sala" --ef volume 1.0 */
class DebugSelfTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val name = intent.getStringExtra("name") ?: return
        val volume = intent.getFloatExtra("volume", 0.25f)
        val app = context.applicationContext as HogarApp
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            app.discovery.start()
            val speaker = withTimeoutOrNull(15_000) {
                app.discovery.speakers.first { list -> list.any { it.name == name } }.first { it.name == name }
            }
            Log.i(TAG, "Altavoces: " + app.discovery.speakers.value.joinToString { "${it.name} [${it.id}] ${it.host}" })
            if (speaker == null) { Log.w(TAG, "No encontré '$name'"); return@launch }
            val result = (app.outputs as AirPlayOutputFactory).selfTest(speaker, volume)
            Log.i(TAG, "Resultado: $result")
            app.discovery.stop()
        }
    }

    private companion object { const val TAG = "HogarSelfTest" }
}
