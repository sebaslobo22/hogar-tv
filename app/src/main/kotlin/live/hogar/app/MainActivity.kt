package live.hogar.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import live.hogar.design.Hogar
import live.hogar.design.HogarRoot
import live.hogar.launcher.HomeScreen
import live.hogar.player.AudioScreen
import live.hogar.specs.SpecsScreen

private enum class Screen { Home, Specs, Audio }

class MainActivity : ComponentActivity() {
    /** Cuántas veces se pidió abrir el Centro de control (tecla de perfil del mando). */
    private var controlCenterRequests by mutableIntStateOf(0)
    /** Cuántas veces se pidió abrir Audio (botón AirPlay del visor Reproduciendo ahora). */
    private var audioRequests by mutableIntStateOf(0)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("open_control_center", false)) controlCenterRequests++
        if (intent.getBooleanExtra("open_audio", false)) audioRequests++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.getBooleanExtra("open_control_center", false) == true) controlCenterRequests++
        if (intent?.getBooleanExtra("open_audio", false) == true) audioRequests++
        val app = application as HogarApp
        setContent {
            var screen by rememberSaveable { mutableStateOf(Screen.Home) }
            LaunchedEffect(audioRequests) { if (audioRequests > 0) screen = Screen.Audio }
            // Como pantalla de inicio, "atrás" en Home no hace nada (igual que tvOS).
            BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }
            BackHandler(enabled = screen == Screen.Home) { }
            // La raíz dibuja el fondo con brillo y lo comparte con el vidrio de todas las pantallas.
            HogarRoot {
                AnimatedContent(
                    targetState = screen,
                    transitionSpec = {
                        // Profundidad (tvOS): al entrar, la pantalla saliente se aleja (0,94) y la nueva
                        // llega desde 1,04; al volver a Home, al revés.
                        val deeper = targetState != Screen.Home
                        (fadeIn(Hogar.Motion.smooth()) + scaleIn(Hogar.Motion.smooth(), initialScale = if (deeper) 1.04f else 0.94f)) togetherWith
                            (fadeOut(Hogar.Motion.smooth()) + scaleOut(Hogar.Motion.smooth(), targetScale = if (deeper) 0.94f else 1.04f))
                    },
                    label = "screen",
                ) { s ->
                    when (s) {
                        Screen.Home -> HomeScreen(onOpenSpecs = { screen = Screen.Specs }, onOpenAudio = { screen = Screen.Audio }, controlCenterRequests = controlCenterRequests)
                        Screen.Specs -> SpecsScreen(onBack = { screen = Screen.Home })
                        Screen.Audio -> AudioScreen(discovery = app.discovery, outputs = app.outputs, onBack = { screen = Screen.Home })
                    }
                }
            }
        }
    }
}
