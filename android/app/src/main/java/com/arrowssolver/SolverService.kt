package com.arrowssolver

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Panel over the Arrows game, opened with the system accessibility button (the Arrows Solver shortcut).
 * All overlays are TYPE_ACCESSIBILITY_OVERLAY windows, so no
 * "draw over other apps" permission is needed. Screenshots via takeScreenshot (API 30+), taps and
 * drags via dispatchGesture. The engine runs on a worker thread; it never touches views directly.
 */
class SolverService : AccessibilityService(), Device {

    companion object {
        /** Appearance setting shared with the setup screen: "system", "light" or "dark" (the only thing the app saves). */
        fun themeMode(c: Context): String = c.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("theme", "system") ?: "system"

        fun setThemeMode(c: Context, mode: String) {
            c.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("theme", mode).apply()
            instance?.onThemeChanged()
        }

        const val GAME = "com.ecffri.arrows"
        @Volatile var instance: SolverService? = null; private set
        @Volatile var lastStatus: String = "Ready. Start a level, then tap Analyze or Solve."; private set
        private val IGNORE = setOf("com.android.systemui", "android")
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    /** Keeps the green/yellow drawing glued to the real arrows between runs (re-measured ~3 times a second). */
    private val tracker = Executors.newSingleThreadScheduledExecutor()
    private val running = AtomicBoolean(false)
    @Volatile private var stop = false
    override val stopRequested get() = stop

    private lateinit var wm: WindowManager
    private val levelDb by lazy { runCatching { assets.open("levels.bin").use { LevelDb.load(it) } }.getOrNull() }
    private val engine by lazy { Engine(this) { levelDb } }
    private var panel: View? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private var statusView: TextView? = null
    /** While Analyze/Solve/Center runs: spinner + live status at the bottom centre (the strip our touches never reach). */
    private var busyPill: View? = null
    private var busyText: TextView? = null
    private var planView: PlanView? = null
    private var mapView: MapView? = null
    private var mapShown = false
    private var mapHolder: View? = null
    private var inGame = true
    private var panelOpen = false
    /** The plan drawing over the game is always on. */
    private val planShown = true

    override fun onServiceConnected() {
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        main.post { buildUi(); syncForeground(); refresh() }
        runCatching { getExternalFilesDir("debug")?.deleteRecursively(); deleteSharedPreferences("stats") } // older versions kept these
        accessibilityButtonController.registerAccessibilityButtonCallback(object : android.accessibilityservice.AccessibilityButtonController.AccessibilityButtonCallback() {
            override fun onClicked(controller: android.accessibilityservice.AccessibilityButtonController) {
                android.util.Log.i("ArrowsSolver", "accessibility button: open=${!panelOpen} inGame=$inGame")
                main.post { setPanel(!panelOpen) }
            }
        })
        tracker.scheduleWithFixedDelay({ trackTick() }, 1500, 300, TimeUnit.MILLISECONDS)
        // The lock screen is system UI, which onAccessibilityEvent ignores: watch the screen state directly.
        val f = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_SCREEN_OFF); addAction(android.content.Intent.ACTION_USER_PRESENT); addAction(android.content.Intent.ACTION_SCREEN_ON)
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, f, RECEIVER_NOT_EXPORTED) else registerReceiver(screenReceiver, f)
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg in IGNORE) return // our own screen counts as leaving the game: hide the panel there too
        // Only real activity switches count. Keyboards, toasts, dialogs and other framework windows
        // (class names under "android.") also fire this event and must not hide the panel.
        val cls = e.className?.toString() ?: return
        // our own overlay windows (the drawer, the pill) fire this event too; only our setup screen means "left the game"
        if (pkg == packageName && cls != MainActivity::class.java.name) return
        if (pkg != GAME && cls.startsWith("android.")) return
        val nowInGame = pkg == GAME
        if (nowInGame == inGame) return
        android.util.Log.i("ArrowsSolver", "foreground: $pkg/$cls -> inGame=$nowInGame")
        inGame = nowInGame
        if (!inGame && running.get()) { stop = true; status("Stopped: left the game") }
        main.post { refresh() }
    }

    override fun onInterrupt() {}

    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: android.content.Intent) {
            if (i.action == android.content.Intent.ACTION_SCREEN_OFF && running.get()) { stop = true; status("Stopped: screen turned off") }
            main.post { refresh() }
            // unlocking can flash the launcher and return to the game without a window event: ask who is in front
            // (the lock screen / shade can still be the active window at first, so ask again a little later)
            if (i.action != android.content.Intent.ACTION_SCREEN_OFF) for (ms in longArrayOf(700, 1500, 3000)) main.postDelayed({ syncForeground(); refresh() }, ms)
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        stop = true
        instance = null
        listOfNotNull(panel, busyPill, planView).forEach { runCatching { wm.removeView(it) } }
        worker.shutdownNow()
        tracker.shutdownNow()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun overlayLp(w: Int, h: Int, touchable: Boolean) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    /**
     * Material 3 context in the app's Appearance setting (System / Light / Dark, see [themeMode]), coloured with the
     * phone's Dynamic Color (wallpaper) palette on Android 12+, else with the overlay theme's seed.
     */
    private fun themed(): Context {
        val dark = when (themeMode(this)) {
            "light" -> false
            "dark" -> true
            else -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        val cfg = android.content.res.Configuration(resources.configuration).apply {
            uiMode = (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
                (if (dark) android.content.res.Configuration.UI_MODE_NIGHT_YES else android.content.res.Configuration.UI_MODE_NIGHT_NO)
        }
        val base = android.view.ContextThemeWrapper(createConfigurationContext(cfg), if (dark) R.style.Theme_Arrows_Overlay else R.style.Theme_Arrows_Overlay_Light)
        return com.google.android.material.color.DynamicColors.wrapContextIfAvailable(base)
    }

    private var analyzeBtn: View? = null
    private var solveBtn: View? = null
    private var stopBtn: View? = null

    private var statusIcon: android.widget.ImageView? = null
    private var mapChip: com.google.android.material.chip.Chip? = null

    private fun buildUi() {
        planView = PlanView(this).also { wm.addView(it, overlayLp(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, false)) }
        buildDrawer()
    }

    /** The app's Appearance setting changed: build the drawer and pill again in the new theme. */
    fun onThemeChanged() {
        main.post {
            listOfNotNull(panel, busyPill).forEach { runCatching { wm.removeView(it) } }
            buildDrawer(); refresh()
        }
    }

    private fun buildDrawer() {
        val inf = android.view.LayoutInflater.from(themed())
        val d = inf.inflate(R.layout.top_drawer, null)
        statusView = d.findViewById(R.id.status)
        statusIcon = d.findViewById(R.id.statusIcon)
        d.findViewById<View>(R.id.content).setPadding(dp(16), safeArea().top + dp(8), dp(16), 0)
        d.findViewById<View>(R.id.close).setOnClickListener { setPanel(false) }
        d.findViewById<View>(R.id.handle).setOnClickListener { setPanel(false) }
        analyzeBtn = d.findViewById<View>(R.id.analyze).apply { setOnClickListener { setPanel(false); launch { engine.analyze() } } }
        solveBtn = d.findViewById<View>(R.id.solve).apply { setOnClickListener { setPanel(false); launch { engine.solve() } } }
        stopBtn = d.findViewById<View>(R.id.stop).apply { setOnClickListener { stop = true; status("Stopping…") } }
        val holder = d.findViewById<android.widget.FrameLayout>(R.id.mapHolder)
        val map = MapView(holder.context).also { holder.addView(it) }
        map.drawing = mapView?.drawing // keep the map when the drawer is rebuilt (theme change)
        mapView = map
        mapHolder = holder
        mapChip = d.findViewById<com.google.android.material.chip.Chip>(R.id.map).apply {
            isChecked = mapShown
            setOnClickListener {
                mapShown = isChecked
                if (mapShown && engine.board == null) status("Tap Analyze first, then Map shows the whole level")
                if (!running.get()) mapView?.drawing = engine.mapDrawing()
                refresh()
            }
        }
        panel = d
        showStatus(lastStatus)

        val pill = inf.inflate(R.layout.busy_pill, null)
        busyText = pill.findViewById<TextView>(R.id.busyText).apply { maxWidth = (resources.displayMetrics.widthPixels * 0.55).toInt() }
        pill.setOnClickListener { setPanel(true) }
        busyPill = pill
        wm.addView(pill, overlayLp(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, true).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (resources.displayMetrics.heightPixels * 0.025).toInt()
        })

        panelLp = overlayLp(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, true)
        wm.addView(d, panelLp)
    }

    /** Opens or closes the top drawer, sliding it down from the top edge (or back up). */
    private fun setPanel(open: Boolean) {
        val d = panel ?: return
        if (open == panelOpen) return
        panelOpen = open
        if (open) {
            refresh()
            d.translationY = -dp(240).toFloat(); d.alpha = 0f
            d.animate().translationY(0f).alpha(1f).setDuration(260).setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
        } else {
            d.animate().translationY(-d.height.toFloat()).alpha(0f).setDuration(200).withEndAction { refresh() }.start()
        }
    }

    /** Status line: "⚠ …" from the engine shows the error icon, a solved level the check icon; no symbols in the text. */
    private fun showStatus(msg: String) {
        val error = msg.startsWith("⚠")
        val text = msg.removePrefix("⚠").trim()
        statusView?.text = text
        busyText?.text = text
        statusIcon?.apply {
            when {
                error -> { setImageResource(R.drawable.ms_error); imageTintList = android.content.res.ColorStateList.valueOf(com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorError)); visibility = View.VISIBLE }
                text.startsWith("Solved") -> { setImageResource(R.drawable.ms_check_circle); imageTintList = android.content.res.ColorStateList.valueOf(com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)); visibility = View.VISIBLE }
                else -> visibility = View.GONE
            }
        }
    }

    /** Screen area not covered by status bar, navigation bar or camera cutout (overlays may draw anywhere). */
    private fun safeArea(): Rect {
        val m = wm.currentWindowMetrics
        val i = m.windowInsets.getInsetsIgnoringVisibility(
            android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout(),
        )
        return Rect(i.left, i.top, m.bounds.width() - i.right, m.bounds.height() - i.bottom)
    }

    /** Set [inGame] from the app actually in front (events alone miss some switches, e.g. after unlock). */
    private fun syncForeground() {
        val pkg = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull() ?: return
        if (pkg !in IGNORE) inGame = pkg == GAME
    }

    private fun refresh() {
        // accessibility overlays also draw over the lock screen: show nothing while the phone is locked
        val show = inGame && !probing && !getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
        panel?.visibility = if (show && panelOpen) View.VISIBLE else View.GONE
        // while something runs only Stop is offered; otherwise the actions
        val busy = running.get()
        stopBtn?.visibility = if (busy) View.VISIBLE else View.GONE
        listOfNotNull(analyzeBtn, solveBtn).forEach { it.visibility = if (busy) View.GONE else View.VISIBLE }
        busyPill?.visibility = if (show && running.get()) View.VISIBLE else View.GONE
        planView?.visibility = if (show && planShown && planView?.drawing != null) View.VISIBLE else View.GONE
        mapHolder?.visibility = if (mapShown && mapView?.drawing != null) View.VISIBLE else View.GONE
    }

    private fun trackTick() {
        // only between runs, in the game, with the drawing switched on, and only with game-window screenshots
        // (full-screen shots would see our own drawing)
        if (!inGame || running.get() || !planShown || engine.board == null || !overlaysHiddenFromCapture) return
        val d = runCatching { engine.trackOnce() }.getOrNull()
        main.post { if (!running.get() && engine.board != null) { planView?.drawing = d; planView?.invalidate(); refresh() } }
    }

    private fun launch(task: () -> Unit) {
        if (!running.compareAndSet(false, true)) { status("Busy — tap Stop first"); return }
        stop = false
        main.post { refresh() }
        worker.execute {
            try { findA11yButton(); task() } catch (t: Throwable) { status("⚠ Error: ${t.javaClass.simpleName}: ${t.message}") }
            finally { engine.resetTracking(); running.set(false); main.post { refresh() } }
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) { block(); return }
        val l = CountDownLatch(1)
        main.post { try { block() } finally { l.countDown() } }
        l.await(2, TimeUnit.SECONDS)
    }

    // ---------------------------------------------------------------- Device

    override fun status(msg: String) {
        lastStatus = msg
        android.util.Log.i("ArrowsSolver", msg)
        if (msg.startsWith("⚠") && !msg.startsWith("⚠ Stopped")) logError(msg)
        main.post { showStatus(msg) }
    }

    override fun boardShift(dx: Float, dy: Float, ms: Long) = onMain { planView?.shift(dx, dy, ms) }

    override fun arrowLeaving(id: Int, cells: FloatArray, dx: Int, dy: Int) = onMain { planView?.leave(id, cells, dx, dy) }

    override fun showMap(d: MapDrawing?) {
        main.post { mapView?.drawing = d; refresh() }
    }

    override fun showPlan(d: PlanDrawing?) {
        onMain { planView?.drawing = d; planView?.invalidate(); refresh() }
        if (d == null && Looper.myLooper() != Looper.getMainLooper()) Thread.sleep(100) // let it leave the screen before the next screenshot
    }

    /**
     * Nothing about a level is kept on the phone: debug lines live only in memory for the current run. When something
     * goes wrong, the error and the lines leading up to it are appended to errors.log
     * (Android/data/com.arrowssolver/files/errors.log) so the problem can be read later.
     */
    private val recent = ArrayDeque<String>()

    override fun debugBegin() = synchronized(recent) { recent.clear() }

    override fun debug(msg: String) {
        android.util.Log.i("ArrowsSolver", "dbg $msg")
        synchronized(recent) { recent.addLast("${System.currentTimeMillis()} $msg"); while (recent.size > 300) recent.removeFirst() }
    }

    private fun logError(msg: String) {
        val lines = synchronized(recent) { recent.toList().also { recent.clear() } }
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        runCatching {
            val f = java.io.File(getExternalFilesDir(null), "errors.log")
            if (f.length() > 1_000_000) f.renameTo(java.io.File(f.parentFile, "errors.old.log"))
            f.appendText("=== $stamp  $msg\n" + lines.joinToString("\n", postfix = "\n\n"))
        }
    }

    override fun overlayRects(): List<Rect> {
        val out = ArrayList<Rect>()
        onMain {
            for (v in listOfNotNull(panel)) {
                if (v.visibility != View.VISIBLE || !v.isAttachedToWindow) continue
                val loc = IntArray(2); v.getLocationOnScreen(loc)
                out.add(Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height))
            }
        }
        a11yButton?.let { out.add(Rect(it)) } // a tap there would open the accessibility shortcut, not reach the game
        return out
    }

    /** The system's floating accessibility button on screen (null: not shown). */
    @Volatile private var a11yButton: Rect? = null
    /** True while our own overlays are hidden for [findA11yButton]. */
    @Volatile private var probing = false
    /** Pixels of the last game-window screenshot. */
    @Volatile private var lastPx: IntArray? = null

    /**
     * The floating accessibility button is drawn by System UI over every app, and Android doesn't say where it is.
     * A game-window screenshot leaves it out and a full-display one shows it, so it is where the two differ in a
     * small patch touching the left or right edge (it docks there). Our own overlays are hidden for that moment.
     */
    private fun findA11yButton(): Unit = synchronized(captureLock) {
        if (windowShots == false || android.os.Build.VERSION.SDK_INT < 34) return
        probing = true
        onMain { refresh() }
        try {
            Thread.sleep(120) // let our overlays leave the screen
            if (captureGameWindow(null) == null) return
            val game = lastPx ?: return
            Thread.sleep(360) // screenshot rate limit
            val b = displayBitmap() ?: return
            val w = b.width; val h = b.height
            val full = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }
            b.recycle()
            if (game.size != full.size) return
            a11yButton = A11yButton.find(game, full, w, h)?.let { Rect(it[0], it[1], it[2], it[3]) }
            debug("floating accessibility button: $a11yButton")
        } finally {
            probing = false
            lastPx = null
            main.post { refresh() }
        }
    }

    /** null = not tried yet, true = window-only screenshots work, false = use full-screen shots. */
    @Volatile private var windowShots: Boolean? = null
    override val overlaysHiddenFromCapture get() = windowShots == true

    /** Android 14+: screenshot only the game's window, so our panel and drawings never appear in it. */
    private fun captureGameWindow(lineColor: Int?): Shot? {
        if (android.os.Build.VERSION.SDK_INT < 34 || windowShots == false) return null
        val root = runCatching { rootInActiveWindow }.getOrNull()
        val win = root?.takeIf { it.packageName?.toString() == GAME }?.windowId
            ?: runCatching { windows.firstOrNull { w -> w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION && w.root?.packageName?.toString() == GAME }?.id }.getOrNull()
        if (win == null) {
            if (windowShots == null) debug("window-only screenshots: no game window (active root=${root?.packageName}, windows=${runCatching { windows.size }.getOrNull()})")
            return null
        }
        val wait = lastShot + 340 - android.os.SystemClock.uptimeMillis()
        if (wait > 0) Thread.sleep(wait) // Android allows about one screenshot every 333 ms; asking sooner fails
        lastShot = android.os.SystemClock.uptimeMillis()
        val latch = CountDownLatch(1)
        var bmp: Bitmap? = null
        takeScreenshotOfWindow(win, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(r: ScreenshotResult) {
                try {
                    val hw = Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)
                    bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hw?.recycle()
                } finally { r.hardwareBuffer.close(); latch.countDown() }
            }
            override fun onFailure(errorCode: Int) { latch.countDown() }
        })
        latch.await(5, TimeUnit.SECONDS)
        val b = bmp ?: return null
        val full = wm.currentWindowMetrics.bounds
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        // must cover the whole display and really contain the game (Unity draws into a SurfaceView)
        var lit = 0; var n = 0
        for (i in px.indices step 997) { n++; if (((px[i] shr 16 and 255) + (px[i] shr 8 and 255) + (px[i] and 255)) > 60) lit++ }
        val ok = b.width == full.width() && b.height == full.height() && lit > n / 2
        if (windowShots == null) { windowShots = ok; debug("window-only screenshots: $ok (${b.width}x${b.height}, lit $lit/$n)") }
        if (!ok) { b.recycle(); return null }
        b.recycle()
        if (probing) lastPx = px // only the accessibility-button check needs it
        return Shot(full.width(), full.height(), px, lineColor)
    }

    private val captureLock = Any()
    private var lastShot = 0L

    override fun capture(lineColor: Int?): Shot? = synchronized(captureLock) {
        captureGameWindow(lineColor)?.let { return it }
        // Window shots work on this phone, so a failure is the screenshot rate limit (the overlay tracker may have
        // just taken one): wait and retry. A full-screen shot would contain our own drawing, over the hearts too.
        if (windowShots == true) {
            repeat(4) { Thread.sleep(350); captureGameWindow(lineColor)?.let { return it } }
            return null
        }
        // Fallback: whole screen. Overlays stay visible (hiding them made the screen blink); their areas are painted as
        // plain background in the screenshot, and the parser and the tap picker skip these rects.
        val covered = overlayRects().map { Rect(it.left - 6, it.top - 6, it.right + 6, it.bottom + 6) }
        run {
            repeat(5) {
                val b = displayBitmap()
                if (b != null) {
                    val px = IntArray(b.width * b.height)
                    b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
                    for (r in covered) for (y in maxOf(0, r.top) until minOf(b.height, r.bottom)) {
                        java.util.Arrays.fill(px, y * b.width + maxOf(0, r.left), y * b.width + minOf(b.width, r.right), Ui.BG)
                    }
                    val shot = Shot(b.width, b.height, px, lineColor)
                    shot.heartsHidden = covered.any { it.intersects((0.33 * b.width).toInt(), (0.085 * b.height).toInt(), (0.67 * b.width).toInt(), (0.135 * b.height).toInt()) }
                    b.recycle()
                    return shot
                }
                Thread.sleep(400) // screenshots are rate-limited (~3/s)
            }
            return null
        }
    }

    /** The whole display as seen on screen (our overlays and system UI included). */
    private fun displayBitmap(): Bitmap? {
        val latch = CountDownLatch(1)
        var bmp: Bitmap? = null
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(r: ScreenshotResult) {
                try {
                    val hw = Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)
                    bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hw?.recycle()
                } finally { r.hardwareBuffer.close(); latch.countDown() }
            }
            override fun onFailure(errorCode: Int) { latch.countDown() }
        })
        latch.await(5, TimeUnit.SECONDS)
        return bmp
    }

    private fun gesture(path: Path, ms: Long): Boolean =
        dispatch(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, ms)).build(), ms)

    override fun pinch(ax0: Float, ay0: Float, ax1: Float, ay1: Float, bx0: Float, by0: Float, bx1: Float, by1: Float, ms: Long): Boolean {
        val a = Path().apply { moveTo(ax0, ay0); lineTo(ax1, ay1) }
        val b = Path().apply { moveTo(bx0, by0); lineTo(bx1, by1) }
        return dispatch(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(a, 0, ms))
                .addStroke(GestureDescription.StrokeDescription(b, 0, ms))
                .build(), ms,
        )
    }

    private fun dispatch(g: GestureDescription, ms: Long): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        main.post {
            val sent = dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) { ok = true; latch.countDown() }
                override fun onCancelled(d: GestureDescription?) { latch.countDown() }
            }, null)
            if (!sent) latch.countDown()
        }
        latch.await(ms + 3000, TimeUnit.MILLISECONDS)
        return ok
    }

    override fun tap(x: Float, y: Float) = gesture(Path().apply { moveTo(x, y) }, 60)

    override fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) =
        gesture(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, ms)
}

/**
 * Full-screen, touch-through drawing of the analysed arrows over the game's own (green = can go now).
 * Arrows are kept in board cells. Where the board sits on screen is a transform that glides to every new fix and
 * moves in step with our own drags, so between the (rate-limited, ~3 a second) screenshots it stays on the board
 * at the display's frame rate. A tapped arrow slides out the way the game moves it: 50 cells a second
 * (ArrowView.MoveOutStraight), head first, its body following its own path.
 */
class PlanView(private val ctx: Context) : View(ctx) {
    var drawing: PlanDrawing? = null
        set(d) {
            val old = field
            field = d
            if (d == null) { glide?.cancel(); leaving.clear(); invalidate(); return }
            shapes = d.paths.map { (pts, color) -> Shape(pts, color) }
            gone.clear()
            val to = floatArrayOf(d.ox, d.oy, d.sx, d.sy)
            if (old == null) { glide?.cancel(); to.copyInto(m); to.copyInto(target) } else moveTo(to, 250, android.view.animation.DecelerateInterpolator())
            invalidate()
        }

    /** One arrow, ready to draw: its path in cells, tail point, head point and head angle. */
    private class Shape(pts: FloatArray, val color: Int) {
        val path = Path()
        val tx = pts[0]; val ty = pts[1]
        val hx = pts[pts.size - 2]; val hy = pts[pts.size - 1]
        val deg = if (pts.size < 4) 0f else angle(hx - pts[pts.size - 4], hy - pts[pts.size - 3])
        init { path.moveTo(pts[0], pts[1]); for (i in 2 until pts.size step 2) path.lineTo(pts[i], pts[i + 1]) }
    }

    private class Leave(val pts: FloatArray, val ex: Float, val ey: Float, val color: Int, val t0: Long) {
        val len = (2 until pts.size step 2).sumOf { i -> kotlin.math.hypot(pts[i] - pts[i - 2], pts[i + 1] - pts[i - 1]).toDouble() }.toFloat()
    }

    private var shapes = emptyList<Shape>()
    private val gone = HashSet<Int>()
    private val leaving = ArrayList<Leave>()
    /** Shown transform (ox, oy, sx, sy) and the one it is moving to. */
    private val m = FloatArray(4)
    private val target = FloatArray(4)
    private var glide: android.animation.ValueAnimator? = null

    private fun moveTo(to: FloatArray, ms: Long, ease: android.animation.TimeInterpolator) {
        val from = m.copyOf()
        to.copyInto(target)
        glide?.cancel()
        glide = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms; interpolator = ease
            addUpdateListener { a -> val f = a.animatedValue as Float; for (i in 0..3) m[i] = from[i] + (to[i] - from[i]) * f; invalidate() }
            start()
        }
    }

    /** The board is being dragged by (dx, dy) px over [ms] by our own gesture: follow it. */
    fun shift(dx: Float, dy: Float, ms: Long) {
        if (drawing == null) return
        moveTo(target.copyOf().also { it[0] += dx; it[1] += dy }, ms, android.view.animation.LinearInterpolator())
    }

    fun leave(id: Int, pts: FloatArray, dx: Int, dy: Int) {
        val d = drawing ?: return
        if (pts.size < 2) return
        val k = d.ids.indexOf(id)
        gone.add(id)
        // The game's slide becomes visible ~130 ms after our tap gesture reports done (measured on a screen recording:
        // input is read on the next game frame, the tween starts the frame after, then render + compose).
        leaving.add(Leave(pts, dx.toFloat(), dy.toFloat(), if (k >= 0) d.paths[k].second else 0xFF3DDC84.toInt(), android.os.SystemClock.uptimeMillis() + START_DELAY))
        invalidate()
    }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        // Same geometry as the game (ArrowView), in cells: body ~0.2 wide (a touch wider here to cover the game's
        // lavender), rounded corners, ArrowHead sprite 256 px at 300 px/unit = 0.853 cell, ArrowEnd circle as tail cap.
        strokeWidth = 0.25f; pathEffect = android.graphics.CornerPathEffect(0.2f)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val sprite = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val loc = IntArray(2)
    private val rect = android.graphics.RectF()
    private val snakePath = Path()
    private val triangle = Path().apply { moveTo(0f, -0.38f); lineTo(-0.32f, 0.26f); lineTo(0.32f, 0.26f); close() }

    /** The game's own white sprites, extracted from the user's APK by tools/extract_levels.py (not in the repo). */
    private val head: Bitmap? = runCatching { ctx.assets.open("game/ArrowHead.png").use { android.graphics.BitmapFactory.decodeStream(it) } }.getOrNull()
    private val tail: Bitmap? = runCatching { ctx.assets.open("game/ArrowEnd.png").use { android.graphics.BitmapFactory.decodeStream(it) } }.getOrNull()

    private fun arrow(c: Canvas, path: Path, color: Int, tx: Float, ty: Float, hx: Float, hy: Float, deg: Float) {
        stroke.color = color; fill.color = color
        sprite.colorFilter = android.graphics.PorterDuffColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN)
        c.drawPath(path, stroke)
        tail?.let { rect.set(tx - 0.13f, ty - 0.13f, tx + 0.13f, ty + 0.13f); c.drawBitmap(it, null, rect, sprite) }
        c.save(); c.translate(hx, hy); c.rotate(deg)
        val h = head
        if (h != null) { rect.set(-0.46f, -0.46f, 0.46f, 0.46f); c.drawBitmap(h, null, rect, sprite) } else c.drawPath(triangle, fill)
        c.restore()
    }

    override fun onDraw(c: Canvas) {
        val d = drawing ?: return
        getLocationOnScreen(loc)
        // the game's top bar (title, hearts, Hint, progress bar) sits over its board: keep our drawing under it too
        c.clipRect(0f, (BAND_TOP * rootView.height).toFloat() - loc[1], width.toFloat(), height.toFloat())
        c.translate(m[0] - loc[0], m[1] - loc[1]); c.scale(m[2], m[3])
        for ((k, sh) in shapes.withIndex()) if (d.ids[k] !in gone) arrow(c, sh.path, sh.color, sh.tx, sh.ty, sh.hx, sh.hy, sh.deg)
        val now = android.os.SystemClock.uptimeMillis()
        leaving.removeAll { (now - it.t0) * SPEED / 1000f > it.len + OUT }
        for (l in leaving) {
            val d = maxOf(0L, now - l.t0) * SPEED / 1000f
            snake(l, d, minOf(SLACK, d))
            arrow(c, snakePath, l.color, sx0, sy0, sx1, sy1, angle(l.ex, l.ey))
        }
        if (leaving.isNotEmpty()) postInvalidateOnAnimation()
    }

    private var sx0 = 0f; private var sy0 = 0f; private var sx1 = 0f; private var sy1 = 0f

    /**
     * [snakePath] = the stretch [d - s, d + len + s] of the arrow's path continued straight on from its head; the slack
     * [s] covers the game's arrow when its frame timing puts it a little ahead of or behind ours.
     */
    private fun snake(l: Leave, d: Float, s: Float) {
        snakePath.rewind()
        val p = l.pts; val n = p.size / 2
        var acc = 0f; var started = false
        for (i in 0 until n) {
            val x0 = p[2 * i]; val y0 = p[2 * i + 1]
            val last = i == n - 1
            val x1 = if (last) x0 + l.ex * (OUT + l.len + 1) else p[2 * i + 2]
            val y1 = if (last) y0 + l.ey * (OUT + l.len + 1) else p[2 * i + 3]
            val seg = kotlin.math.hypot(x1 - x0, y1 - y0)
            if (seg == 0f) continue
            val a0 = maxOf(d - s, acc); val a1 = minOf(d + l.len + s, acc + seg)
            if (a1 >= a0) {
                val u0 = (a0 - acc) / seg; val u1 = (a1 - acc) / seg
                if (!started) { sx0 = x0 + (x1 - x0) * u0; sy0 = y0 + (y1 - y0) * u0; snakePath.moveTo(sx0, sy0); started = true }
                sx1 = x0 + (x1 - x0) * u1; sy1 = y0 + (y1 - y0) * u1
                snakePath.lineTo(sx1, sy1)
            }
            acc += seg
            if (acc >= d + l.len + s) break
        }
    }

    private companion object {
        /**
         * ArrowView.MoveOutStraight: DOVirtual.Float 0 -> 50 in 1 s, linear, then on at the same speed. On a
         * screen recording the game's arrow ran ~8% faster than ours at 50, so 54.
         */
        const val SPEED = 54f
        /** Cells travelled past the head before the drawing is dropped (off any board). */
        const val OUT = 60f
        const val START_DELAY = 130L
        /** Cells our sliding arrow may stretch at each end to keep the game's covered (measured scatter ~0.5 cell). */
        const val SLACK = 0.6f
        fun angle(dx: Float, dy: Float) = Math.toDegrees(kotlin.math.atan2(dx.toDouble(), -dy.toDouble())).toFloat()
    }
}

/**
 * Whole-level map inside the drawer: a small, view-only reference (green = can go now, orange = blocked,
 * red = waiting). Each arrow is drawn tail to head with a real arrowhead, like the game's own arrows.
 */
class MapView(ctx: Context) : View(ctx) {
    var drawing: MapDrawing? = null
        set(v) { field = v; requestLayout(); invalidate() }
    private val maxH = 180 * ctx.resources.displayMetrics.density
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    init { contentDescription = "Map of the whole level" }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        val d = drawing
        val h = if (d == null) 0 else minOf(maxH, w * (d.h + 1f) / (d.w + 1f)).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(c: Canvas) {
        val d = drawing ?: return
        val cell = minOf(width / (d.w + 1f), height / (d.h + 1f))
        val left = (width - cell * (d.w + 1)) / 2; val top = (height - cell * (d.h + 1)) / 2
        stroke.strokeWidth = maxOf(1.5f, cell * 0.22f)
        for ((pts, color) in d.paths) {
            stroke.color = color; fill.color = color
            fun x(i: Int) = left + (pts[i] + 1f) * cell
            fun y(i: Int) = top + (pts[i + 1] + 1f) * cell
            val p = Path()
            for (i in pts.indices step 2) if (i == 0) p.moveTo(x(i), y(i)) else p.lineTo(x(i), y(i))
            c.drawPath(p, stroke)
            // arrowhead at the head (last point), pointing along the last step
            val n = pts.size
            val hx = x(n - 2); val hy = y(n - 2)
            var dx = 0f; var dy = -1f
            if (n >= 4) { dx = hx - x(n - 4); dy = hy - y(n - 4); val l = kotlin.math.hypot(dx, dy); if (l > 0f) { dx /= l; dy /= l } }
            val len = maxOf(4f, cell * 0.9f); val half = len * 0.55f
            val tip = Path().apply {
                moveTo(hx + dx * len * 0.5f, hy + dy * len * 0.5f)
                lineTo(hx - dx * len * 0.5f - dy * half, hy - dy * len * 0.5f + dx * half)
                lineTo(hx - dx * len * 0.5f + dy * half, hy - dy * len * 0.5f - dx * half)
                close()
            }
            c.drawPath(tip, fill)
        }
    }
}

/**
 * Look and feel taken from the game itself (REA + screenshots of its Settings screen, v0.24.0):
 * background #1E1F2D, cards #32374E, raised/selected #404668, text and icons #AEB6E1, accent #5B71FF,
 * hearts red #FF4B5D. The game uses the commercial Gordita font; Poppins (OFL) is the closest free match.
 */
object Ui {
    const val BG = 0xFF1E1F2D.toInt()
    const val CARD = 0xFF32374E.toInt()
    const val RAISED = 0xFF404668.toInt()
    const val TEXT = 0xFFAEB6E1.toInt()
    const val ACCENT = 0xFF5B71FF.toInt()
    const val RED = 0xFFFF4B5D.toInt()
    private var med: android.graphics.Typeface? = null
    private var semi: android.graphics.Typeface? = null
    fun medium(c: Context) = med ?: runCatching { android.graphics.Typeface.createFromAsset(c.assets, "fonts/Poppins-Medium.ttf") }
        .getOrDefault(android.graphics.Typeface.DEFAULT).also { med = it }
    fun semiBold(c: Context) = semi ?: runCatching { android.graphics.Typeface.createFromAsset(c.assets, "fonts/Poppins-SemiBold.ttf") }
        .getOrDefault(android.graphics.Typeface.DEFAULT_BOLD).also { semi = it }
}
