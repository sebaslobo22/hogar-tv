package live.hogar.browser

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Environment
import android.os.SystemClock
import android.view.Choreographer
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import android.animation.ValueAnimator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import org.json.JSONTokener

enum class Overlay { None, Toolbar, Tabs, Context }

/** Una pestaña. [webView] solo existe mientras está "viva" (máx. 3); si no, queda la dirección y la foto. */
class BrowserTab(val id: Int, url: String, title: String) {
    var url by mutableStateOf(url)
    var title by mutableStateOf(title)
    var snapshot by mutableStateOf<ImageBitmap?>(null)
    var loading by mutableStateOf(false)
    var progress by mutableIntStateOf(0)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var webView: WebView? = null
    val isStart: Boolean get() = url.isEmpty()
}

/**
 * Estado y lógica del navegador. La interfaz (Compose) solo lee este estado; el WebView vive en
 * [webHost] (View clásica) y Compose se dibuja encima, transparente.
 */
class BrowserController(
    private val activity: BrowserActivity,
    private val webHost: FrameLayout,
    private val videoHost: FrameLayout,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = BrowserStore(activity)
    val icons = IconRepository(activity)

    val tabs = mutableStateListOf<BrowserTab>()
    private var activeId by mutableIntStateOf(-1)
    val active: BrowserTab get() = tabs.first { it.id == activeId }

    var overlay by mutableStateOf(Overlay.None)
        private set
    var editing by mutableStateOf(false)
    var favorites by mutableStateOf(BrowserData.DefaultFavorites)
        private set
    var history by mutableStateOf<List<Visit>>(emptyList())
        private set
    var desktop by mutableStateOf(true)
        private set
    var zoom by mutableIntStateOf(100)
        private set
    var scrollMode by mutableStateOf(false)
        private set
    var fullscreen by mutableStateOf(false)
        private set
    var contextLink by mutableStateOf<String?>(null)
        private set

    /** Posición del puntero en px de la ventana; solo se leen al dibujar (sin recomponer). */
    val px = mutableFloatStateOf(960f)
    val py = mutableFloatStateOf(540f)

    val pointerVisible: Boolean
        get() = overlay == Overlay.None && !fullscreen && !editing && !active.isStart && active.error == null

    private val defaultUa: String by lazy { WebSettings.getDefaultUserAgent(activity) }
    private var nextId = 1
    private val lru = ArrayList<Int>()
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    // --- arranque y ciclo de vida ---------------------------------------------------------------

    fun start(intent: Intent?) {
        val d = store.read()
        favorites = d.favorites
        history = d.history
        desktop = d.desktop
        zoom = d.zoom
        scrollMode = d.scrollMode
        d.tabs.take(TabPolicy.MAX_TABS).forEach { tabs += BrowserTab(nextId++, it.url, it.title) }
        if (tabs.isEmpty()) tabs += BrowserTab(nextId++, "", "")
        activeId = tabs[d.active.coerceIn(0, tabs.lastIndex)].id
        lru += activeId
        showActive()
        applyFocus()
        handleIntent(intent)
    }

    fun handleIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val url = intent.dataString ?: return
        if (url.startsWith("http://") || url.startsWith("https://")) openUrl(url)
        activity.intent = Intent(Intent.ACTION_MAIN)
    }

    fun onPause() {
        stopMoving()
        active.webView?.let { it.onPause(); it.pauseTimers() }
        CookieManager.getInstance().flush()
        persist()
    }

    fun onResume() {
        active.webView?.let { it.resumeTimers(); it.onResume() }
    }

    fun onDestroy() {
        persist()
        scope.cancel()
        webHost.removeAllViews()
        tabs.forEach { destroyWebView(it) }
    }

    /** Poca memoria: solo se conserva el WebView de la pestaña activa. */
    fun onLowMemory() {
        tabs.filter { it.id != activeId && it.webView != null }.forEach { destroyWebView(it) }
    }

    private fun persist() {
        val data = BrowserData(
            favorites = favorites, history = history,
            tabs = tabs.map { SavedTab(it.url, it.title) },
            active = tabs.indexOfFirst { it.id == activeId }.coerceAtLeast(0),
            desktop = desktop, zoom = zoom, scrollMode = scrollMode,
        )
        scope.launch(Dispatchers.IO) { store.write(data) }
    }

    // --- pestañas y WebViews --------------------------------------------------------------------

    private fun touch(id: Int) {
        lru.clear()
        lru.addAll(TabPolicy.touch(lru.toList(), id))
    }

    private fun showActive() {
        webHost.removeAllViews()
        val t = active
        val wv = ensureLive(t) ?: return
        (wv.parent as? ViewGroup)?.removeView(wv)
        webHost.addView(wv, FrameLayout.LayoutParams(-1, -1))
        wv.onResume()
        evict()
    }

    private fun applyFocus() {
        if (fullscreen) return
        val t = active
        if (overlay == Overlay.None && !t.isStart && t.error == null && !editing) t.webView?.requestFocus()
    }

    private fun ensureLive(t: BrowserTab): WebView? {
        if (t.isStart) return null
        t.webView?.let { return it }
        val wv = createWebView(t)
        t.webView = wv
        wv.loadUrl(t.url)
        return wv
    }

    private fun evict() {
        val live = tabs.filter { it.webView != null }.map { it.id }.toSet()
        for (id in TabPolicy.evictions(lru.toList(), live, activeId)) {
            tabs.firstOrNull { it.id == id }?.let { destroyWebView(it) }
        }
    }

    private fun destroyWebView(t: BrowserTab) {
        val wv = t.webView ?: return
        t.webView = null
        runCatching {
            wv.stopLoading()
            wv.webChromeClient = null
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.destroy()
        }
    }

    /** Foto pequeña de la pestaña para la rejilla: se dibuja el WebView en un mapa de bits del tamaño de la tarjeta. */
    private fun capture(t: BrowserTab) {
        val wv = t.webView ?: return
        if (wv.width <= 0 || wv.height <= 0) return
        runCatching {
            val bmp = Bitmap.createBitmap(SNAP_W, SNAP_H, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.scale(SNAP_W / wv.width.toFloat(), SNAP_H / wv.height.toFloat())
            wv.draw(c)
            t.snapshot = bmp.asImageBitmap()
        }
    }

    fun switchTo(id: Int) {
        if (tabs.none { it.id == id }) return
        if (id != activeId) {
            capture(active)
            active.webView?.onPause()
            activeId = id
        }
        touch(id)
        overlay = Overlay.None
        editing = false
        showActive()
        applyFocus()
        persist()
    }

    fun newTab(url: String = "") {
        if (!TabPolicy.canOpenNew(tabs.size)) {
            toast(activity.getString(R.string.tabs_max, TabPolicy.MAX_TABS))
            return
        }
        val t = BrowserTab(nextId++, url, "")
        tabs += t
        switchTo(t.id)
    }

    fun closeTab(id: Int) {
        val idx = tabs.indexOfFirst { it.id == id }
        if (idx < 0) return
        val wasActive = id == activeId
        val nextIdx = TabPolicy.activeAfterClose(tabs.size, idx)
        destroyWebView(tabs[idx])
        tabs.removeAt(idx)
        lru.remove(id)
        if (nextIdx == null) {
            val n = BrowserTab(nextId++, "", "")
            tabs += n
            activeId = n.id
            touch(n.id)
        } else if (wasActive) {
            activeId = tabs[nextIdx].id
            touch(activeId)
        }
        if (wasActive) { showActive(); }
        persist()
    }

    /** Abre [url] (enlace externo o "pestaña nueva"): en la pestaña en blanco si la hay, si no en una nueva. */
    fun openUrl(url: String) {
        val a = active
        if (a.isStart) navigate(url) else newTab(url)
    }

    fun navigate(input: String) {
        val url = UrlInput.resolve(input)
        if (url.isEmpty()) return
        editing = false
        overlay = Overlay.None
        val t = active
        t.error = null
        t.url = url
        t.title = ""
        val wv = t.webView
        if (wv == null) showActive() else wv.loadUrl(url)
        applyFocus()
    }

    /** Vuelve la pestaña activa a la página de inicio. */
    fun goHome() {
        val t = active
        destroyWebView(t)
        t.url = ""
        t.title = ""
        t.snapshot = null
        t.error = null
        t.loading = false
        editing = false
        overlay = Overlay.None
        showActive()
    }

    fun reloadOrStop() {
        val t = active
        val wv = t.webView ?: return
        if (t.loading) wv.stopLoading() else wv.reload()
    }

    fun goBack() { active.webView?.takeIf { it.canGoBack() }?.goBack() }
    fun goForward() { active.webView?.takeIf { it.canGoForward() }?.goForward() }

    fun retryAfterError() {
        val t = active
        t.error = null
        val wv = t.webView
        if (wv == null) showActive() else wv.reload()
        overlay = Overlay.None
        applyFocus()
    }

    // --- favoritos, ajustes ----------------------------------------------------------------------

    val isFavorite: Boolean get() = !active.isStart && Favorites.contains(favorites, active.url)

    fun toggleFavorite() {
        val t = active
        if (t.isStart) return
        favorites = Favorites.toggle(favorites, Site(t.url, t.title))
        persist()
    }

    fun toggleDesktop() {
        desktop = !desktop
        tabs.forEach { it.webView?.settings?.userAgentString = userAgent() }
        active.webView?.reload()
        persist()
    }

    fun zoomMore() = applyZoom(ZoomSteps.more(zoom))
    fun zoomLess() = applyZoom(ZoomSteps.less(zoom))

    private fun applyZoom(v: Int) {
        zoom = v
        tabs.forEach { it.webView?.settings?.textZoom = v }
        persist()
    }

    fun toggleScrollMode() {
        scrollMode = !scrollMode
        toast(activity.getString(if (scrollMode) R.string.scroll_mode_on else R.string.ptr_mode_on))
        persist()
    }

    private fun userAgent() = if (desktop) UserAgents.desktop(defaultUa) else UserAgents.mobile(defaultUa)

    // --- paneles ---------------------------------------------------------------------------------

    fun openToolbar() {
        stopMoving()
        if (fullscreen) return
        overlay = Overlay.Toolbar
    }

    fun openTabs() {
        stopMoving()
        capture(active)
        overlay = Overlay.Tabs
    }

    fun closeOverlay() {
        overlay = Overlay.None
        editing = false
        applyFocus()
    }

    private fun toggleToolbar() {
        when {
            fullscreen -> Unit
            overlay == Overlay.None -> openToolbar()
            active.isStart -> Unit
            else -> closeOverlay()
        }
    }

    fun onBack() {
        val t = active
        when {
            customView != null -> hideCustomView()
            editing -> editing = false
            overlay == Overlay.Tabs || overlay == Overlay.Context -> {
                overlay = if (overlay == Overlay.Context) Overlay.None else (if (t.isStart) Overlay.None else Overlay.Toolbar)
                applyFocus()
            }
            overlay == Overlay.Toolbar && !t.isStart -> closeOverlay()
            t.error != null -> { t.error = null; if (t.webView?.canGoBack() == true) t.webView?.goBack() else goHome() }
            t.webView?.canGoBack() == true -> t.webView?.goBack()
            tabs.size > 1 -> closeTab(t.id)
            else -> activity.finish()
        }
    }

    // --- mando: teclas ---------------------------------------------------------------------------

    private var okDown = false
    private var okLong = false
    private var okJob: Job? = null
    private val heldL = BooleanArray(4) // 0 izq, 1 der, 2 arriba, 3 abajo
    private var moveJob: Job? = null
    private var dirKey = 0
    private var holdStartMs = 0L

    private val inOverlay: Boolean
        get() = overlay != Overlay.None || active.isStart || active.error != null || editing

    /** true si el evento se consumió aquí; false lo deja pasar a la interfaz / al sistema. */
    fun handleKey(e: KeyEvent): Boolean {
        if (customView != null) return false
        val code = e.keyCode
        if (code == KeyEvent.KEYCODE_SETTINGS || code == KeyEvent.KEYCODE_MENU) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) toggleToolbar()
            return true
        }
        if (inOverlay) {
            if (moveJob != null) stopMoving()
            return false
        }
        return when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                onArrow(code, e)
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                onOk(e)
                true
            }
            else -> false
        }
    }

    private fun onArrow(code: Int, e: KeyEvent) {
        if (scrollMode) {
            if (e.action != KeyEvent.ACTION_DOWN) return
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> scrollPage(-1)
                KeyEvent.KEYCODE_DPAD_DOWN -> scrollPage(1)
                KeyEvent.KEYCODE_DPAD_LEFT -> if (e.repeatCount == 0) goBack()
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (e.repeatCount == 0) goForward()
            }
            return
        }
        val i = when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> 0
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1
            KeyEvent.KEYCODE_DPAD_UP -> 2
            else -> 3
        }
        when (e.action) {
            KeyEvent.ACTION_DOWN -> if (e.repeatCount == 0) {
                heldL[i] = true
                nudge()
                startMoving()
            }
            KeyEvent.ACTION_UP -> {
                heldL[i] = false
                if (heldL.none { it }) stopMoving() else holdStartMs = SystemClock.uptimeMillis()
            }
        }
    }

    private fun dirX() = (if (heldL[1]) 1 else 0) - (if (heldL[0]) 1 else 0)
    private fun dirY() = (if (heldL[3]) 1 else 0) - (if (heldL[2]) 1 else 0)

    private fun bounds(): Pair<Float, Float> {
        val w = if (webHost.width > 0) webHost.width.toFloat() else 1920f
        val h = if (webHost.height > 0) webHost.height.toFloat() else 1080f
        return w to h
    }

    /** Salto inmediato de un toque corto, para que pulsar una vez siempre mueva algo. */
    private fun nudge() {
        val (w, h) = bounds()
        val p = PointerMath.step(px.floatValue, py.floatValue, dirX(), dirY(), PointerMath.TAP_STEP, 1f, w, h)
        px.floatValue = p.x
        py.floatValue = p.y
        hover()
    }

    private fun startMoving() {
        holdStartMs = SystemClock.uptimeMillis()
        dirKey = dirX() * 3 + dirY()
        if (moveJob?.isActive == true) return
        moveJob = scope.launch {
            var last = nextFrame()
            var accX = 0f
            var accY = 0f
            var topDwell = 0f
            var lastHover = 0L
            while (heldL.any { it }) {
                val now = nextFrame()
                val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
                last = now
                val dx = dirX()
                val dy = dirY()
                val key = dx * 3 + dy
                if (key != dirKey) { dirKey = key; holdStartMs = SystemClock.uptimeMillis() }
                val (w, h) = bounds()
                val speed = PointerMath.speed(SystemClock.uptimeMillis() - holdStartMs)
                val p = PointerMath.step(px.floatValue, py.floatValue, dx, dy, speed, dt, w, h)
                px.floatValue = p.x
                py.floatValue = p.y
                // Borde: la página se desplaza mientras se empuja contra él.
                val wv = active.webView
                if (wv != null) {
                    val vx = if (dx != 0) EdgeScroll.axis(p.x, w) else 0f
                    val vy = if (dy != 0) EdgeScroll.axis(p.y, h) else 0f
                    val goX = vx != 0f && (vx > 0) == (dx > 0)
                    val goY = vy != 0f && (vy > 0) == (dy > 0)
                    val (sx, rx) = EdgeScroll.split(accX, if (goX) vx * dt else 0f)
                    val (sy, ry) = EdgeScroll.split(accY, if (goY) vy * dt else 0f)
                    accX = rx; accY = ry
                    if (sx != 0 || sy != 0) wv.scrollBy(sx, sy)
                    // Empujar hacia arriba contra el borde de una página ya en su inicio abre la barra.
                    if (dy < 0 && p.y <= 1f && !wv.canScrollVertically(-1)) {
                        topDwell += dt
                        if (topDwell > 0.35f) { heldL.fill(false); openToolbar(); break }
                    } else topDwell = 0f
                }
                val t = SystemClock.uptimeMillis()
                if (t - lastHover > 100) { lastHover = t; hover() }
            }
            moveJob = null
            hover()
        }
    }

    private fun stopMoving() {
        heldL.fill(false)
        moveJob?.cancel()
        moveJob = null
        okJob?.cancel()
        okDown = false
    }

    private fun onOk(e: KeyEvent) {
        when (e.action) {
            KeyEvent.ACTION_DOWN -> if (e.repeatCount == 0 && !okDown) {
                okDown = true
                okLong = false
                okJob?.cancel()
                okJob = scope.launch {
                    delay(LONG_PRESS_MS)
                    if (okDown) { okLong = true; openContext() }
                }
            }
            KeyEvent.ACTION_UP -> {
                okJob?.cancel()
                if (okDown && !okLong) click()
                okDown = false
            }
        }
    }

    // --- mando: puntero sobre la página ----------------------------------------------------------

    private fun hover() {
        val wv = active.webView ?: return
        val t = SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(t, t, MotionEvent.ACTION_HOVER_MOVE, px.floatValue, py.floatValue, 0)
        ev.source = InputDevice.SOURCE_MOUSE
        wv.dispatchGenericMotionEvent(ev)
        ev.recycle()
    }

    private fun click() {
        val wv = active.webView ?: return
        wv.requestFocus()
        val x = px.floatValue
        val y = py.floatValue
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        down.source = InputDevice.SOURCE_TOUCHSCREEN
        wv.dispatchTouchEvent(down)
        down.recycle()
        scope.launch {
            delay(40)
            val t2 = SystemClock.uptimeMillis()
            val up = MotionEvent.obtain(t, t2, MotionEvent.ACTION_UP, x, y, 0)
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            wv.dispatchTouchEvent(up)
            up.recycle()
        }
    }

    /** Espera al siguiente cuadro de pantalla (el bucle del puntero solo corre mientras hay una flecha pulsada). */
    private suspend fun nextFrame(): Long = suspendCancellableCoroutine { cont ->
        val cb = Choreographer.FrameCallback { cont.resume(it) }
        Choreographer.getInstance().postFrameCallback(cb)
        cont.invokeOnCancellation { Choreographer.getInstance().removeFrameCallback(cb) }
    }

    private var scrollAnim: ValueAnimator? = null

    /** Salto de ~80 % del alto visible con una curva corta (modo desplazamiento). */
    private fun scrollPage(dir: Int) {
        val wv = active.webView ?: return
        if (scrollAnim?.isRunning == true) return
        val total = EdgeScroll.pageStep(wv.height) * dir
        var done = 0
        scrollAnim = ValueAnimator.ofInt(0, total).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val v = it.animatedValue as Int
                wv.scrollBy(0, v - done)
                done = v
            }
            start()
        }
    }

    private fun openContext() {
        val wv = active.webView ?: return
        val w = wv.width.coerceAtLeast(1)
        val h = wv.height.coerceAtLeast(1)
        var shown = false
        fun show(link: String?) {
            if (shown) return
            shown = true
            stopMoving()
            contextLink = link
            overlay = Overlay.Context
        }
        val js = "(function(){var x=${px.floatValue}*window.innerWidth/$w,y=${py.floatValue}*window.innerHeight/$h;" +
            "var e=document.elementFromPoint(x,y);while(e&&!(e.tagName==='A'&&e.href))e=e.parentElement;return e?e.href:'';})()"
        wv.evaluateJavascript(js) { r ->
            val link = runCatching { JSONTokener(r).nextValue() as? String }.getOrNull()
                ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            show(link)
        }
        scope.launch { delay(700); show(null) }
    }

    fun openContextLinkInNewTab() {
        val link = contextLink
        overlay = Overlay.None
        contextLink = null
        if (link != null) newTab(link) else applyFocus()
    }

    // --- WebView ---------------------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(tab: BrowserTab): WebView {
        val wv = WebView(activity)
        wv.layoutParams = FrameLayout.LayoutParams(-1, -1)
        wv.setBackgroundColor(Color.WHITE)
        wv.isFocusable = true
        wv.isFocusableInTouchMode = true
        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.allowFileAccess = false
        s.allowContentAccess = false
        @Suppress("DEPRECATION")
        s.allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        s.allowUniversalAccessFromFileURLs = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.safeBrowsingEnabled = true
        s.javaScriptCanOpenWindowsAutomatically = false
        s.setSupportMultipleWindows(false)
        s.setSupportZoom(false)
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.userAgentString = userAgent()
        s.textZoom = zoom
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        wv.setDownloadListener { url, ua, cd, mime, _ -> startDownload(url, ua, cd, mime) }
        wv.webViewClient = client(tab)
        wv.webChromeClient = chrome(tab)
        return wv
    }

    private fun client(tab: BrowserTab) = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val u = request.url.toString()
            // Solo http(s) y about:blank; intent:, market:, file:, javascript: no se siguen.
            return !(u.startsWith("http://") || u.startsWith("https://") || u == "about:blank")
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            tab.loading = true
            tab.error = null
            if (url.startsWith("http")) tab.url = url
            updateNav(tab, view)
        }

        override fun onPageFinished(view: WebView, url: String) {
            tab.loading = false
            if (url.startsWith("http")) tab.url = url
            view.title?.takeIf { it.isNotBlank() }?.let { tab.title = it }
            updateNav(tab, view)
            if (url.startsWith("http") && tab.error == null) {
                history = History.add(history, Visit(url, tab.title, System.currentTimeMillis()))
                persist()
            }
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            if (url.startsWith("http")) tab.url = url
            updateNav(tab, view)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) failPage(tab, activity.getString(R.string.err_net))
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel() // nunca se continúa con un certificado malo
            failPage(tab, activity.getString(R.string.err_ssl))
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // El motor de la página murió (casi siempre por memoria): se descarta ese WebView y se avisa.
            val owner = tabs.firstOrNull { it.webView === view } ?: tab
            destroyWebView(owner)
            owner.loading = false
            owner.error = activity.getString(R.string.err_crash)
            if (owner.id == activeId) { webHost.removeAllViews(); overlay = Overlay.None }
            return true
        }
    }

    private fun failPage(tab: BrowserTab, message: String) {
        tab.loading = false
        tab.error = message
        if (tab.id == activeId) { stopMoving(); overlay = Overlay.None }
    }

    private fun updateNav(tab: BrowserTab, view: WebView) {
        tab.canGoBack = view.canGoBack()
        tab.canGoForward = view.canGoForward()
    }

    private fun chrome(tab: BrowserTab) = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            tab.progress = newProgress
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            if (!title.isNullOrBlank()) tab.title = title
        }

        // Cámara, micrófono y ubicación: siempre denegados.
        override fun onPermissionRequest(request: PermissionRequest) {
            request.deny()
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            callback.invoke(origin, false, false)
        }

        // No hay selector de archivos en el TV.
        override fun onShowFileChooser(
            webView: WebView, filePathCallback: ValueCallback<Array<Uri>>, fileChooserParams: FileChooserParams,
        ): Boolean {
            filePathCallback.onReceiveValue(null)
            return true
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) { callback.onCustomViewHidden(); return }
            stopMoving()
            customView = view
            customCallback = callback
            fullscreen = true
            overlay = Overlay.None
            videoHost.addView(view, FrameLayout.LayoutParams(-1, -1))
            videoHost.visibility = View.VISIBLE
            view.isFocusable = true
            view.requestFocus()
            activity.setKeepScreenOn(true)
        }

        override fun onHideCustomView() {
            hideCustomView()
        }
    }

    private fun hideCustomView() {
        val v = customView ?: return
        customView = null
        videoHost.removeView(v)
        videoHost.visibility = View.GONE
        customCallback?.onCustomViewHidden()
        customCallback = null
        fullscreen = false
        activity.setKeepScreenOn(false)
        applyFocus()
    }

    private fun startDownload(url: String, ua: String?, contentDisposition: String?, mime: String?) {
        if (!(url.startsWith("http://") || url.startsWith("https://"))) {
            toast(activity.getString(R.string.dl_failed))
            return
        }
        try {
            val name = URLUtil.guessFileName(url, contentDisposition, mime)
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle(name)
                .setMimeType(mime)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            if (!ua.isNullOrEmpty()) req.addRequestHeader("User-Agent", ua)
            CookieManager.getInstance().getCookie(url)?.let { req.addRequestHeader("Cookie", it) }
            (activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            toast(activity.getString(R.string.dl_started, name))
        } catch (_: Exception) {
            toast(activity.getString(R.string.dl_failed))
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val SNAP_W = 528
        const val SNAP_H = 297
        const val LONG_PRESS_MS = 550L
    }
}
