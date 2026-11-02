package live.hogar.nowplaying

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import live.hogar.nowplaying.light.LightSync

/**
 * "Reproduciendo ahora" a pantalla completa para lo que suene en el TV. Se abre desde el
 * Centro de control, con la tecla de TV del mando, o sola (extra [EXTRA_AUTO]) como
 * salvapantallas; en ese caso cualquier tecla la cierra.
 */
class NowPlayingActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var tracker: MediaTracker
    private val granted = mutableStateOf(false)
    private val autoMode = mutableIntStateOf(AutoModePolicy.DEFAULT)

    // Fondo animado y reactividad al audio. El Visualizer solo se abre con RECORD_AUDIO; sin él, respira por tiempo.
    private val engine = MeshEngine(audioAllowed = {
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    })
    // Iluminación Govee: lee energía y pulso del motor del fondo; restaura la lámpara al salir.
    private val light by lazy { LightSync(this, energy = { engine.energy }, swell = { engine.swell }) }
    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* el motor reintenta solo */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
        tracker = MediaTracker(this, scope)
        val prefs = NowPlayingPrefs(this)
        autoMode.intValue = prefs.autoMode
        val auto = intent.getBooleanExtra(EXTRA_AUTO, false)
        // Se pide una sola vez y nunca como salvapantallas (el diálogo estorbaría).
        if (!auto && !prefs.audioAsked && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            prefs.audioAsked = true
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        setContent {
            NowPlayingScreen(
                tracker = tracker,
                lyrics = LyricsRepository(this),
                engine = engine,
                light = light,
                granted = granted.value,
                autoMode = autoMode.intValue,
                autoLaunched = auto,
                onCycleAuto = {
                    prefs.autoMode = AutoModePolicy.next(autoMode.intValue)
                    autoMode.intValue = prefs.autoMode
                },
                onOpenAccessSettings = ::openAccessSettings,
                onClose = ::finish,
            )
        }
    }

    override fun onStart() {
        super.onStart()
        granted.value = ListenerAccess.isGranted(this)
        tracker.start()
        engine.start(scope)
        light.onStart()
    }

    override fun onStop() {
        light.onStop()
        engine.stop()
        tracker.stop()
        super.onStop()
    }

    override fun onDestroy() {
        light.release()
        scope.cancel()
        super.onDestroy()
    }

    private fun openAccessSettings() {
        val intents = listOf(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), Intent(Settings.ACTION_SETTINGS))
        for (i in intents) if (runCatching { startActivity(i) }.isSuccess) return
    }

    companion object {
        const val EXTRA_AUTO = "auto"
    }
}
