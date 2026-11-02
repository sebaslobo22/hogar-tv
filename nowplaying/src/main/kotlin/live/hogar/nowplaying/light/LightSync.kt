package live.hogar.nowplaying.light

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Sincroniza una lámpara Govee (API LAN) con la carátula y el audio de "Reproduciendo ahora".
 * Toda la red va en Dispatchers.IO; la UI solo lee [mode], [toast] y [toastId] (estado de Compose).
 * Un único supervisor reconcilia "lo que se quiere" (modo ≠ apagado y pantalla visible) con "lo que
 * corre", así activar, salir o pausar nunca se pisan.
 */
class LightSync(
    ctx: Context,
    private val energy: () -> Float,
    private val swell: () -> Float,
) {
    private val app = ctx.applicationContext
    private val prefs = LightPrefs(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wake = Channel<Unit>(Channel.CONFLATED)

    var mode by mutableIntStateOf(prefs.mode)
        private set
    var segments by mutableStateOf(prefs.segments)
        private set
    var toast by mutableStateOf<String?>(null)
        private set
    var toastId by mutableIntStateOf(0)
        private set

    @Volatile private var foreground = false
    @Volatile private var gaveUp = false
    @Volatile private var palette: List<LampColor> = emptyList()
    @Volatile private var paletteVersion = 0
    private var run: Run? = null

    private class Run(val session: GoveeSession, val saved: LampStatus?, val job: Job, val seg: Boolean) {
        @Volatile var lost = false
    }

    init {
        scope.launch {
            for (u in wake) reconcile()
            scope.cancel()
        }
    }

    // --- API para la UI (hilo principal) ---

    /** Apagado → Paleta → Paleta + ritmo → Apagado. */
    fun cycle() {
        mode = (mode + 1) % 3
        prefs.mode = mode
        gaveUp = false
        say("Iluminación: " + when (mode) { LightPrefs.MODE_PALETTE -> "Paleta"; LightPrefs.MODE_RHYTHM -> "Paleta + ritmo"; else -> "Apagado" })
        wake.trySend(Unit)
    }

    /** Opción oculta (mantener pulsado el botón): control por segmentos, experimental. */
    fun toggleSegments() {
        segments = !segments
        prefs.segments = segments
        say("Segmentos (experimental): " + if (segments) "sí" else "no")
        if (run != null) { gaveUp = false; wake.trySend(Unit) }
    }

    fun setPalette(argb: List<Int>) {
        if (argb.isEmpty()) return
        palette = argb.map { LampMath.fromArgb(it) }
        paletteVersion++
    }

    fun onStart() { foreground = true; gaveUp = false; wake.trySend(Unit) }

    /** Pantalla oculta o app en pausa: restaura la lámpara. El modo elegido se conserva. */
    fun onStop() { foreground = false; wake.trySend(Unit) }

    /** Restaura y libera todo (cierra el supervisor cuando termina). */
    fun release() { foreground = false; wake.trySend(Unit); wake.close() }

    private fun say(msg: String) { toast = msg; toastId++ }

    // --- Supervisor (IO) ---

    private suspend fun reconcile() {
        val want = mode != LightPrefs.MODE_OFF && foreground && !gaveUp
        val cur = run
        // Cambio de segmentos con la lámpara en marcha: se reinicia la sesión para (des)activar razer.
        if (cur != null && (!want || cur.lost || cur.seg != segments)) { stop(cur); return reconcile() }
        if (cur == null && want) start()
    }

    private suspend fun start() {
        var lamp = prefs.lamp
        var session: GoveeSession? = null
        var status: LampStatus? = null
        try {
            if (lamp != null) {
                session = GoveeSession(app, scope, lamp.ip)
                status = session.readStatus(1_200)
                if (status == null) { session.close(); session = null }
            }
            if (session == null) {
                val found = GoveeDiscovery.scan(app, 3_000)
                val pick = lamp?.let { l -> found.firstOrNull { it.device == l.device } } ?: GoveeDiscovery.pick(found)
                if (pick == null) {
                    gaveUp = true
                    mode = LightPrefs.MODE_OFF; prefs.mode = mode
                    say("No encontré lámparas Govee (activa Control LAN y reinicia la lámpara)")
                    return
                }
                lamp = pick
                prefs.lamp = pick // IP refrescada por id de dispositivo
                session = GoveeSession(app, scope, pick.ip)
                status = session.readStatus(2_000)
                say("Lámpara: Govee ${pick.sku.ifEmpty { pick.device.takeLast(4) }}")
            }
        } catch (_: Throwable) {
            runCatching { session?.close() }
            gaveUp = true
            say("No pude usar la red para la lámpara Govee")
            return
        }
        val s = session!!
        val seg = segments
        s.send(GoveeProtocol.turn(true))
        if (seg) s.send(GoveeProtocol.razer(Razer.enable()))
        val holder = arrayOfNulls<Run>(1)
        val job = scope.launch {
            val lost = !loop(s, seg)
            if (lost) { holder[0]?.lost = true; gaveUp = true; say("La lámpara Govee no responde"); wake.trySend(Unit) }
        }
        run = Run(s, status, job, seg).also { holder[0] = it }
    }

    private suspend fun stop(r: Run) {
        r.job.cancelAndJoin()
        run = null
        if (!r.lost) {
            val s = r.session
            if (r.seg) s.send(GoveeProtocol.razer(Razer.disable()))
            repeat(2) {
                restore(s, r.saved)
                delay(150)
            }
        }
        r.session.close()
    }

    private fun restore(s: GoveeSession, st: LampStatus?) {
        if (st == null) return
        if (!st.on) { s.send(GoveeProtocol.turn(false)); return }
        s.send(GoveeProtocol.turn(true))
        if (st.kelvin > 0) s.send(GoveeProtocol.color(0, 0, 0, st.kelvin)) else s.send(GoveeProtocol.color(st.r, st.g, st.b, 0))
        s.send(GoveeProtocol.brightness(st.brightness.coerceAtLeast(1)))
    }

    /** Bucle de envío. Devuelve false si la lámpara dejó de contestar 10 s. */
    private suspend fun loop(s: GoveeSession, seg: Boolean): Boolean {
        val fallback = listOf(LampMath.fromArgb(0xFFFFB46B.toInt()))
        val slew = SlewLimiter(3.5f)
        val paletteRate = RateLimiter(8f)
        val rhythmRate = RateLimiter(10f)
        var seenVersion = -1
        var shown = (palette.ifEmpty { fallback })[0]
        var from = shown
        var blendT = BLEND_SEC
        var t = 0f
        var last = SystemClock.elapsedRealtime()
        var nextPing = 0L
        var sentKey = Long.MIN_VALUE
        var sentBright = -1
        while (currentCoroutineContext().isActive) {
            val now = SystemClock.elapsedRealtime()
            val dt = ((now - last) / 1000f).coerceIn(0f, 0.25f)
            last = now
            if (now - s.lastReplyMs > LOST_MS) return false
            if (now >= nextPing) { nextPing = now + 2_000; s.ping() }

            val pal = palette.ifEmpty { fallback }
            if (seenVersion != paletteVersion) { seenVersion = paletteVersion; from = shown; blendT = 0f; t = 0f }
            t += dt
            blendT = (blendT + dt).coerceAtMost(BLEND_SEC)
            val rhythm = mode == LightPrefs.MODE_RHYTHM
            val beat = if (rhythm) (swell() / 0.06f).coerceIn(0f, 1f) else 0f

            var c = Fade.paletteAt(pal, t, CYCLE_SEC)
            if (blendT < BLEND_SEC) c = Fade.lerp(from, c, Fade.smooth(blendT / BLEND_SEC))
            if (beat > 0f) c = Fade.lerp(c, pal.maxByOrNull { it.level } ?: c, 0.30f * beat)
            shown = c

            val target = if (rhythm) Brightness.rhythm(energy(), beat) else Brightness.PALETTE
            val level = slew.step(target * c.level, dt)

            if ((if (rhythm) rhythmRate else paletteRate).tryAcquire(now)) {
                if (seg) {
                    val n = SEGMENTS
                    val rgb = IntArray(n) { i -> Fade.paletteAt(pal, t + i * 1.2f, CYCLE_SEC).rgb24() }
                    val key = rgb.fold(7L) { a, v -> a * 31 + v }
                    if (key != sentKey) { sentKey = key; s.send(GoveeProtocol.razer(Razer.segments(rgb))) }
                } else {
                    val k = if (c.isWhite) ((c.kelvin / 50f).toInt() * 50).toLong() else 0L
                    val key = (c.r8().toLong() shl 40) or (c.g8().toLong() shl 32) or (c.b8().toLong() shl 24) or k
                    if (key != sentKey) {
                        sentKey = key
                        if (c.isWhite) s.send(GoveeProtocol.color(0, 0, 0, k.toInt())) else s.send(GoveeProtocol.color(c.r8(), c.g8(), c.b8(), 0))
                    }
                }
                val pct = Brightness.percent(level)
                if (pct != sentBright) { sentBright = pct; s.send(GoveeProtocol.brightness(pct)) }
            }
            delay(TICK_MS)
        }
        return true
    }

    private companion object {
        const val CYCLE_SEC = 6f
        const val BLEND_SEC = 1.5f
        const val LOST_MS = 10_000L
        const val TICK_MS = 40L
        const val SEGMENTS = 10
    }
}
