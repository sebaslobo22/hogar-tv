package live.hogar.nowplaying.light

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import live.hogar.nowplaying.MediaTracker
import kotlin.math.exp

/**
 * Motor de la lámpara Govee, de vida larga: lo posee [live.hogar.nowplaying.MediaListener] (vive mientras
 * haya acceso a notificaciones), sigue la sesión de medios activa con [MediaTracker] (Apple Music, Spotify,
 * AirPlay publicado por el receptor...), saca la paleta de la carátula de alta resolución con [ArtPalette]
 * y reparte esa paleta por los tramos de la lámpara según el [LightMethod] elegido.
 *
 * Toda la red va en Dispatchers.IO; la UI solo lee [mode], [toast] y [toastId] (estado de Compose).
 * Un único supervisor reconcilia "lo que se quiere" con "lo que corre". Nunca toca la pantalla (sin wake
 * locks ni flags de ventana). Si la lámpara no contesta 10 s se detiene y se avisa una vez.
 */
class LightEngine private constructor(ctx: Context) {
    private val app = ctx.applicationContext
    private val prefs = LightPrefs(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val wake = Channel<Unit>(Channel.CONFLATED)

    var mode by mutableIntStateOf(prefs.mode)
        private set
    var toast by mutableStateOf<String?>(null)
        private set
    var toastId by mutableIntStateOf(0)
        private set

    private var tracker: MediaTracker? = null
    private var stateJob: Job? = null
    @Volatile private var attached = false
    @Volatile private var playing = false
    @Volatile private var lastPlayingMs = Long.MIN_VALUE / 2
    @Volatile private var gaveUp = false
    @Volatile private var notified = false
    @Volatile private var palette: List<LampColor> = emptyList()
    @Volatile private var paletteVersion = 0
    @Volatile private var method: LightMethod = prefs.method(prefs.lamp?.device)
    @Volatile private var testUntil = 0L
    private var lastTestAt = Long.MIN_VALUE / 2
    private var lastArt: Any? = null
    private var run: Run? = null

    private class Run(val session: GoveeSession, val saved: LampStatus?, val job: Job, val method: LightMethod) {
        @Volatile var lost = false
    }

    // HomeKit (y cualquier otro) puede cambiar el modo escribiendo en LightPrefs: el motor lo sigue.
    // Debe quedar en un campo: SharedPreferences solo guarda una referencia débil al oyente.
    private val modeListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "mode") {
            val m = prefs.mode
            if (m != mode) { mode = m; gaveUp = false; notified = false; wake.trySend(Unit) }
        }
    }

    init {
        scope.launch { for (u in wake) reconcile() }
        prefs.registerListener(modeListener)
    }

    // --- Ciclo de vida (hilo principal; lo llama MediaListener) ---

    /** Empieza a seguir los medios. Idempotente. */
    fun attach() {
        if (attached) return
        attached = true
        val t = tracker ?: MediaTracker(app, main).also { tracker = it }
        t.start()
        stateJob = main.launch { t.state.collect { onMedia(it) } }
        wake.trySend(Unit)
    }

    /** Deja de seguir los medios y restaura la lámpara. */
    fun detach() {
        if (!attached) return
        attached = false
        stateJob?.cancel(); stateJob = null
        tracker?.stop()
        playing = false
        wake.trySend(Unit)
    }

    private fun onMedia(st: live.hogar.nowplaying.NowPlayingState) {
        val was = playing
        playing = st.track != null && st.playback.playing
        if (playing) {
            lastPlayingMs = SystemClock.elapsedRealtime()
            if (!was) { gaveUp = false; wake.trySend(Unit) }
        } else if (was) {
            main.launch { delay(IDLE_MS + 200); wake.trySend(Unit) }
        }
        val art = st.art
        if (art != null && art !== lastArt) {
            lastArt = art
            main.launch {
                val res = withContext(Dispatchers.Default) { runCatching { ArtPalette.fromBitmap(art) }.getOrNull() }
                if (res != null && res.colors.isNotEmpty()) {
                    palette = res.colors.map { LampMath.fromArgb(it) }
                    paletteVersion++
                    Log.i(TAG, "paleta de la carátula: " + res.colors.joinToString(" ") { "#%06X".format(it and 0xFFFFFF) } + if (res.monochrome) " (monocroma)" else "")
                }
            }
        }
        wake.trySend(Unit)
    }

    // --- API para la UI ---

    /** Apagado → Paleta → Paleta + ritmo → Apagado. */
    fun cycle() {
        mode = (mode + 1) % 3
        prefs.mode = mode
        gaveUp = false; notified = false
        say("Iluminación: " + when (mode) { LightPrefs.MODE_PALETTE -> "Paleta"; LightPrefs.MODE_RHYTHM -> "Paleta + ritmo"; else -> "Apagado" } + " · ${method.label}")
        wake.trySend(Unit)
    }

    /**
     * "Prueba de tramos": 4 s de arcoíris fijo con el método actual. Si se repite en los 15 s siguientes
     * (el dueño no vio el arcoíris) pasa al método siguiente y lo guarda para esta lámpara.
     */
    fun testSegments() {
        val now = SystemClock.elapsedRealtime()
        var extra = 0L
        if (now - lastTestAt < 15_000) {
            method = method.next(); prefs.setMethod(prefs.lamp?.device, method); extra = 1_500
        }
        lastTestAt = now
        testUntil = now + 4_000 + extra
        gaveUp = false; notified = false
        say("Prueba de tramos · ${method.label}. ¿Ves el arcoíris? Si no, mantén otra vez para probar el siguiente método")
        wake.trySend(Unit)
        scope.launch { delay(4_300 + extra); wake.trySend(Unit) }
    }

    private fun say(msg: String) { toast = msg; toastId++ }

    // --- Supervisor (IO) ---

    private suspend fun reconcile() {
        val now = SystemClock.elapsedRealtime()
        val testing = now < testUntil
        val live = mode != LightPrefs.MODE_OFF && attached && (playing || now - lastPlayingMs < IDLE_MS) && !gaveUp
        val want = live || (testing && !gaveUp)
        val cur = run
        if (cur != null && (!want || cur.lost || cur.method != method)) { stop(cur); return reconcile() }
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
                if (pick == null) { giveUp("No encontré lámparas Govee (activa Control LAN y reinicia la lámpara)"); return }
                lamp = pick
                prefs.lamp = pick
                method = prefs.method(pick.device)
                session = GoveeSession(app, scope, pick.ip)
                status = session.readStatus(2_000)
            }
        } catch (_: Throwable) {
            runCatching { session?.close() }
            giveUp("No pude usar la red para la lámpara Govee")
            return
        }
        val s = session!!
        val m = method
        s.send(GoveeProtocol.turn(true))
        if (m == LightMethod.GRADIENT || m == LightMethod.RAZER) s.send(GoveeProtocol.razer(Razer.enable()))
        val holder = arrayOfNulls<Run>(1)
        val job = scope.launch {
            val ok = loop(s, m)
            if (!ok) { holder[0]?.lost = true; giveUp("La lámpara Govee no responde"); wake.trySend(Unit) }
        }
        run = Run(s, status, job, m).also { holder[0] = it }
    }

    /** Detiene y avisa UNA vez (toast + notificación discreta) hasta que el dueño vuelva a activar la luz. */
    private fun giveUp(msg: String) {
        gaveUp = true
        Log.w(TAG, msg)
        if (notified) return
        notified = true
        say(msg)
        postNotification(msg)
    }

    private fun postNotification(msg: String) {
        runCatching {
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Luz Govee", NotificationManager.IMPORTANCE_LOW))
            val n = android.app.Notification.Builder(app, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_error).setContentTitle("Luz Govee").setContentText(msg)
                .setAutoCancel(true).build()
            nm.notify(NOTIF_ID, n)
        }
    }

    private suspend fun stop(r: Run) {
        r.job.cancelAndJoin()
        run = null
        if (!r.lost) {
            val s = r.session
            if (r.method == LightMethod.GRADIENT || r.method == LightMethod.RAZER) s.send(GoveeProtocol.razer(Razer.disable()))
            repeat(2) { restore(s, r.saved); delay(150) }
        }
        r.session.close()
    }

    /** Devuelve la lámpara al estado que tenía al empezar. */
    private fun restore(s: GoveeSession, st: LampStatus?) {
        if (st == null) return
        if (!st.on) { s.send(GoveeProtocol.turn(false)); return }
        s.send(GoveeProtocol.turn(true))
        if (st.kelvin > 0) s.send(GoveeProtocol.color(0, 0, 0, st.kelvin)) else s.send(GoveeProtocol.color(st.r, st.g, st.b, 0))
        s.send(GoveeProtocol.brightness(st.brightness.coerceAtLeast(1)))
    }

    private fun audioAllowed() = app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Bucle de envío. Devuelve false si la lámpara dejó de contestar 10 s. */
    private suspend fun loop(s: GoveeSession, m: LightMethod): Boolean {
        val energy = LightEnergy(::audioAllowed)
        try {
            val fallback = listOf(LampMath.fromArgb(0xFFFFB46B.toInt()))
            val slew = SlewLimiter(3.5f)
            val paletteRate = RateLimiter(8f)
            val rhythmRate = RateLimiter(10f)
            val testRate = RateLimiter(4f)
            val n = SegmentMath.segmentCount(m)
            var seenVersion = paletteVersion
            var pal = palette.ifEmpty { fallback }
            var prevPal = pal
            var blendT = BLEND_SEC
            var shift = 0f
            var shiftTarget = 0f
            var beatHold = 0f
            var lastStep = 0L
            var last = SystemClock.elapsedRealtime()
            var nextPing = 0L
            var sentKey = Long.MIN_VALUE
            var sentAt = 0L
            var sentBright = -1
            var testStart = 0L
            while (currentCoroutineContext().isActive) {
                val now = SystemClock.elapsedRealtime()
                val dt = ((now - last) / 1000f).coerceIn(0f, 0.25f)
                last = now
                if (now - s.lastReplyMs > LOST_MS) return false
                if (now >= nextPing) { nextPing = now + 2_000; s.ping() }

                // --- Prueba de tramos: arcoíris fijo, ignora paleta y ritmo ---
                if (now < testUntil) {
                    if (testStart == 0L) { testStart = now; sentBright = -1; sentKey = Long.MIN_VALUE }
                    if (testRate.tryAcquire(now)) {
                        if (sentBright != 80) { sentBright = 80; s.send(GoveeProtocol.brightness(80)) }
                        if (m == LightMethod.COLORS) {
                            val c = SegmentMath.RAINBOW[((now - testStart) * SegmentMath.RAINBOW.size / 4_000L).toInt().coerceIn(0, SegmentMath.RAINBOW.size - 1)]
                            s.send(GoveeProtocol.color((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF, 0))
                        } else for (p in SegmentMath.frame(m, SegmentMath.rainbow(n))) s.send(p)
                    }
                    delay(TICK_MS); continue
                }
                if (testStart != 0L) { testStart = 0L; sentKey = Long.MIN_VALUE; sentBright = -1 }
                if (mode == LightPrefs.MODE_OFF) { delay(TICK_MS); continue }

                if (seenVersion != paletteVersion) { seenVersion = paletteVersion; prevPal = pal; pal = palette.ifEmpty { fallback }; blendT = 0f }
                blendT = (blendT + dt).coerceAtMost(BLEND_SEC)

                val rhythm = mode == LightPrefs.MODE_RHYTHM
                val beat = if (rhythm) energy.step(dt) else 0f
                val colorsMethod = m == LightMethod.COLORS
                if (beat > 0f) {
                    beatHold = maxOf(beatHold, beat)
                    if (now - lastStep > (if (colorsMethod) 450L else 250L)) { lastStep = now; shiftTarget += if (colorsMethod) 1f else 0.5f }
                }
                beatHold = (beatHold - dt * 4f).coerceAtLeast(0f)
                // Deriva lenta: ~2 s por color en "Colores"; los degradados fluyen más despacio.
                shiftTarget += dt * (if (colorsMethod) (if (rhythm) 0.12f else 0.5f) else (if (rhythm) 0.10f else 0.20f))
                shift += (shiftTarget - shift) * (1f - exp(-dt / 0.25f))

                val target = if (rhythm) Brightness.rhythm(energy.energy, beatHold) else Brightness.PALETTE
                val level = slew.step(target, dt)

                if ((if (rhythm) rhythmRate else paletteRate).tryAcquire(now)) {
                    if (colorsMethod) {
                        var c = SegmentMath.sampleCyclic(pal, shift)
                        if (blendT < BLEND_SEC) c = Fade.lerp(SegmentMath.sampleCyclic(prevPal, shift), c, Fade.smooth(blendT / BLEND_SEC))
                        val k = if (c.isWhite) ((c.kelvin / 50f).toInt() * 50).toLong() else 0L
                        val key = (c.r8().toLong() shl 40) or (c.g8().toLong() shl 32) or (c.b8().toLong() shl 24) or k
                        if (key != sentKey) {
                            sentKey = key
                            if (c.isWhite) s.send(GoveeProtocol.color(0, 0, 0, k.toInt())) else s.send(GoveeProtocol.color(c.r8(), c.g8(), c.b8(), 0))
                        }
                        val pct = Brightness.percent(level * c.level)
                        if (pct != sentBright) { sentBright = pct; s.send(GoveeProtocol.brightness(pct)) }
                    } else {
                        // El brillo de cada tramo va horneado en el RGB; el global queda al 100 %.
                        if (sentBright != 100) { sentBright = 100; s.send(GoveeProtocol.brightness(100)) }
                        var rgb = SegmentMath.gradient(pal, n, shift, level)
                        if (blendT < BLEND_SEC) rgb = mix(SegmentMath.gradient(prevPal, n, shift, level), rgb, Fade.smooth(blendT / BLEND_SEC))
                        val key = rgb.fold(7L) { a, v -> a * 31 + v }
                        // Razer vuelve a su efecto si pasa ~1 min sin datos: se reenvía cada 2 s aunque no cambie.
                        if (key != sentKey || now - sentAt > 2_000) {
                            sentKey = key; sentAt = now
                            for (p in SegmentMath.frame(m, rgb)) s.send(p)
                        }
                    }
                }
                delay(TICK_MS)
            }
            return true
        } finally {
            energy.release()
        }
    }

    private fun mix(a: IntArray, b: IntArray, t: Float): IntArray = IntArray(a.size) { i ->
        fun ch(sh: Int) = (((a[i] shr sh) and 0xFF) * (1f - t) + ((b[i] shr sh) and 0xFF) * t).toInt().coerceIn(0, 255)
        (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    companion object {
        private const val TAG = "HogarLamp"
        private const val CHANNEL = "luz_govee"
        private const val NOTIF_ID = 4202
        private const val BLEND_SEC = 1.5f
        private const val LOST_MS = 10_000L
        private const val TICK_MS = 40L
        /** Sin reproducir durante este tiempo la lámpara vuelve a su estado. */
        private const val IDLE_MS = 20_000L

        @Volatile private var instance: LightEngine? = null

        fun get(ctx: Context): LightEngine =
            instance ?: synchronized(this) { instance ?: LightEngine(ctx.applicationContext).also { instance = it } }
    }
}
