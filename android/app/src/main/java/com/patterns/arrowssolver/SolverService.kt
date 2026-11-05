package com.patterns.arrowssolver

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
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
import android.view.MotionEvent
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
 * Floating bubble over the Arrows game. All overlays are TYPE_ACCESSIBILITY_OVERLAY windows, so no
 * "draw over other apps" permission is needed. Screenshots via takeScreenshot (API 30+), taps and
 * drags via dispatchGesture. The engine runs on a worker thread; it never touches views directly.
 */
class SolverService : AccessibilityService(), Device {

    companion object {
        const val STATS = "stats"
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
    private var bubble: View? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var panel: LinearLayout? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private var statusView: TextView? = null
    private var planView: PlanView? = null
    private var mapView: MapView? = null
    private var mapShown = false
    private var inGame = true
    private var panelOpen = false
    private var planShown = true

    override fun onServiceConnected() {
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        main.post { buildUi(); syncForeground(); refresh() }
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
        if (pkg in IGNORE) return // our own screen counts as leaving the game: hide the bubble there too
        // Only real activity switches count. Keyboards, toasts, dialogs and other framework windows
        // (class names under "android.") also fire this event and must not hide the bubble.
        val cls = e.className?.toString() ?: return
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
        listOfNotNull(bubble, panel, planView, mapView).forEach { runCatching { wm.removeView(it) } }
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

    private fun chip(text: String, bg: Int, fg: Int = Ui.TEXT, weight: Float = 1f, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        setTextColor(fg)
        textSize = 14f
        typeface = Ui.semiBold(this@SolverService)
        gravity = Gravity.CENTER
        maxLines = 1
        includeFontPadding = false
        setPadding(dp(10), 0, dp(10), 0)
        minHeight = dp(42)
        background = GradientDrawable().apply { cornerRadius = dp(21).toFloat(); setColor(bg) }
        setOnClickListener { onClick() }
        contentDescription = text
        layoutParams = LinearLayout.LayoutParams(0, dp(42), weight).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi() {
        planView = PlanView(this).also { wm.addView(it, overlayLp(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, false)) }
        mapView = MapView(this).also { wm.addView(it, overlayLp(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, false)) }

        val size = dp(52)
        bubbleLp = overlayLp(size, size, true).apply { val a = safeArea(); x = a.left + dp(8); y = a.top + dp(4) }
        bubble = BubbleView(this).apply {
            contentDescription = "Arrows Solver"
            var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragged = false
            setOnTouchListener { _, ev ->
                val lp = bubbleLp!!
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> { downX = ev.rawX; downY = ev.rawY; startX = lp.x; startY = lp.y; dragged = false }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = ev.rawX - downX; val dy = ev.rawY - downY
                        if (dragged || abs(dx) + abs(dy) > dp(8)) {
                            dragged = true
                            val a = safeArea()
                            lp.x = (startX + dx.toInt()).coerceIn(a.left, a.right - size)
                            lp.y = (startY + dy.toInt()).coerceIn(a.top, a.bottom - size)
                            wm.updateViewLayout(this, lp); placePanel()
                        }
                    }
                    MotionEvent.ACTION_UP -> if (!dragged) { panelOpen = !panelOpen; refresh(); placePanel() }
                }
                true
            }
        }
        wm.addView(bubble, bubbleLp)

        statusView = TextView(this).apply {
            setTextColor(Ui.TEXT); textSize = 14f
            typeface = Ui.medium(this@SolverService)
            setLineSpacing(0f, 1.15f)
            setPadding(dp(8), dp(4), dp(8), dp(8))
            text = lastStatus
        }
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(chip("Analyze", Ui.RAISED) { panelOpen = false; refresh(); launch { engine.analyze() } })
            addView(chip("Solve", Ui.ACCENT, Color.WHITE) { panelOpen = false; refresh(); launch { engine.solve() } })
            addView(chip("Stop", Ui.RAISED, Ui.RED) { stop = true; status("Stopping…") })
        }
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(chip("Zoom out", Ui.RAISED, weight = 1.3f) { panelOpen = false; refresh(); launch { engine.zoomOutFully() } })
            addView(chip("Map", Ui.RAISED) {
                mapShown = !mapShown
                if (mapShown && engine.board == null) status("Tap Analyze first, then Map shows the whole level")
                mapView?.drawing = engine.mapDrawing(); mapView?.invalidate(); refresh()
            })
            addView(chip("Plan", Ui.RAISED) { planShown = !planShown; refresh() })
            addView(chip("\u2715", Ui.CARD, weight = 0.6f) { panelOpen = false; refresh() }.apply { contentDescription = "Close panel" })
        }
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(12), dp(10), dp(10))
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(Ui.CARD) }
            elevation = dp(8).toFloat()
            addView(statusView); addView(row1); addView(row2)
        }
        panelLp = overlayLp(dp(340), WindowManager.LayoutParams.WRAP_CONTENT, true)
        wm.addView(panel, panelLp)
        placePanel()
    }

    /** Screen area not covered by status bar, navigation bar or camera cutout (overlays may draw anywhere). */
    private fun safeArea(): Rect {
        val m = wm.currentWindowMetrics
        val i = m.windowInsets.getInsetsIgnoringVisibility(
            android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout(),
        )
        return Rect(i.left, i.top, m.bounds.width() - i.right, m.bounds.height() - i.bottom)
    }

    /** Panel under the bubble, or above it when there is no room below; never off screen. */
    private fun placePanel() {
        val lp = panelLp ?: return; val b = bubbleLp ?: return
        val a = safeArea()
        val ph = panel?.height?.takeIf { it > 0 } ?: dp(170)
        lp.x = b.x.coerceIn(a.left, maxOf(a.left, a.right - lp.width))
        val below = b.y + b.height + dp(8)
        lp.y = if (below + ph <= a.bottom) below else maxOf(a.top, b.y - ph - dp(8))
        panel?.let { wm.updateViewLayout(it, lp) }
    }

    /** Set [inGame] from the app actually in front (events alone miss some switches, e.g. after unlock). */
    private fun syncForeground() {
        val pkg = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull() ?: return
        if (pkg !in IGNORE) inGame = pkg == GAME
    }

    private fun refresh() {
        // accessibility overlays also draw over the lock screen: show nothing while the phone is locked
        val show = inGame && !getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
        bubble?.visibility = if (show) View.VISIBLE else View.GONE
        panel?.visibility = if (show && panelOpen) View.VISIBLE else View.GONE
        planView?.visibility = if (show && planShown && planView?.drawing != null) View.VISIBLE else View.GONE
        mapView?.visibility = if (show && mapShown && mapView?.drawing != null) View.VISIBLE else View.GONE
    }

    private fun trackTick() {
        // only between runs, in the game, with the drawing switched on, and only with game-window screenshots
        // (full-screen shots would see our own drawing)
        if (!inGame || running.get() || !planShown || engine.board == null || !overlaysHiddenFromCapture) return
        val d = runCatching { engine.trackOnce() }.getOrNull()
        main.post { planView?.drawing = d; planView?.invalidate(); refresh() }
    }

    private fun launch(task: () -> Unit) {
        if (!running.compareAndSet(false, true)) { status("Busy — tap Stop first"); return }
        stop = false
        (bubble as? BubbleView)?.busy = true
        worker.execute {
            try { task() } catch (t: Throwable) { status("⚠ Error: ${t.javaClass.simpleName}: ${t.message}") }
            finally { engine.resetTracking(); running.set(false); main.post { (bubble as? BubbleView)?.busy = false } }
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
        main.post { statusView?.text = msg }
    }

    override fun boardShift(dx: Float, dy: Float, ms: Long) = onMain { planView?.shift(dx, dy, ms) }

    override fun arrowLeaving(id: Int, cells: FloatArray, dx: Int, dy: Int) = onMain { planView?.leave(id, cells, dx, dy) }

    override fun recordSolved(arrows: Int, ms: Long) {
        val p = getSharedPreferences(STATS, MODE_PRIVATE)
        p.edit().putInt("levels", p.getInt("levels", 0) + 1).putInt("arrows", p.getInt("arrows", 0) + arrows)
            .putLong("ms", p.getLong("ms", 0) + ms).apply()
    }

    override fun showMap(d: MapDrawing?) {
        main.post { mapView?.drawing = d; mapView?.invalidate(); refresh() }
    }

    override fun showPlan(d: PlanDrawing?) {
        onMain { planView?.drawing = d; planView?.invalidate(); refresh() }
        if (d == null && Looper.myLooper() != Looper.getMainLooper()) Thread.sleep(100) // let it leave the screen before the next screenshot
    }

    private var debugN = 0
    private val debugDir get() = getExternalFilesDir("debug")

    override fun debugBegin() {
        debugN = 0
        debugDir?.listFiles()?.forEach { it.delete() }
    }

    override fun debug(msg: String) {
        android.util.Log.i("ArrowsSolver", "dbg $msg")
        runCatching { java.io.File(debugDir, "log.txt").appendText("${System.currentTimeMillis()} $msg\n") }
    }

    override fun overlayRects(): List<Rect> {
        val out = ArrayList<Rect>()
        onMain {
            for (v in listOfNotNull(bubble, panel)) {
                if (v.visibility != View.VISIBLE || !v.isAttachedToWindow) continue
                val loc = IntArray(2); v.getLocationOnScreen(loc)
                out.add(Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height))
            }
            mapView?.takeIf { it.visibility == View.VISIBLE }?.box?.let { out.add(Rect(it)) }
        }
        return out
    }

    /** null = not tried yet, true = window-only screenshots work, false = use full-screen shots. */
    @Volatile private var windowShots: Boolean? = null
    override val overlaysHiddenFromCapture get() = windowShots == true

    /** Android 14+: screenshot only the game's window, so our bubble, panel and drawings never appear in it. */
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
        runCatching { java.io.File(debugDir, "f%03d.png".format(debugN++)).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        b.recycle()
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
                val b = bmp
                if (b != null) {
                    val px = IntArray(b.width * b.height)
                    b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
                    for (r in covered) for (y in maxOf(0, r.top) until minOf(b.height, r.bottom)) {
                        java.util.Arrays.fill(px, y * b.width + maxOf(0, r.left), y * b.width + minOf(b.width, r.right), Ui.BG)
                    }
                    val shot = Shot(b.width, b.height, px, lineColor)
                    shot.heartsHidden = covered.any { it.intersects((0.33 * b.width).toInt(), (0.085 * b.height).toInt(), (0.67 * b.width).toInt(), (0.135 * b.height).toInt()) }
                    runCatching {
                        java.io.File(debugDir, "f%03d.png".format(debugN++)).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                    b.recycle()
                    return shot
                }
                Thread.sleep(400) // screenshots are rate-limited (~3/s)
            }
            return null
        }
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

/** Whole-level mini-map in the lower part of the screen (touch-through). */
class MapView(ctx: Context) : View(ctx) {
    var drawing: MapDrawing? = null
    /** Last drawn panel area in screen coordinates (excluded from screenshots). */
    @Volatile var box: Rect? = null
    private val loc = IntArray(2)
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = (Ui.CARD and 0x00FFFFFF) or 0xF2000000.toInt() }
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = Ui.RAISED }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    override fun onDraw(c: Canvas) {
        val d = drawing ?: return
        val pad = 24f
        val boxW = width - 2 * pad
        val boxH = height * 0.42f
        val cell = minOf(boxW / (d.w + 1), boxH / (d.h + 1))
        val w = cell * (d.w + 1); val h = cell * (d.h + 1)
        val left = (width - w) / 2; val top = height - h - height * 0.06f
        getLocationOnScreen(loc)
        box = Rect((left - 16).toInt() + loc[0], (top - 16).toInt() + loc[1], (left + w + 16).toInt() + loc[0], (top + h + 16).toInt() + loc[1])
        c.drawRoundRect(left - 16, top - 16, left + w + 16, top + h + 16, 44f, 44f, bg)
        c.drawRoundRect(left - 16, top - 16, left + w + 16, top + h + 16, 44f, 44f, frame)
        stroke.strokeWidth = maxOf(2f, cell * 0.28f)
        for ((pts, color) in d.paths) {
            stroke.color = color; fill.color = color
            val p = Path()
            for (i in pts.indices step 2) {
                val x = left + (pts[i] + 1f) * cell; val y = top + (pts[i + 1] + 1f) * cell
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            c.drawPath(p, stroke)
            c.drawCircle(left + (pts[pts.size - 2] + 1f) * cell, top + (pts[pts.size - 1] + 1f) * cell, maxOf(3f, cell * 0.45f), fill)
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

/**
 * The floating button, styled after Apple's Liquid Glass (regular variant): a frosted body that dims what is behind
 * it so the glyph stays legible, light pooling at the lower edge (depth), a brighter lens band just inside the edge
 * (refraction), a specular rim brightest top-left with an echo bottom-right, a small glint and a soft shadow.
 * Overlay windows can't sample or blur the screen behind them, so every layer is painted.
 * While Analyze/Solve runs it shows a spinner instead of the icon: a faint ring plus a quarter arc, one turn a second.
 */
class BubbleView(ctx: Context) : View(ctx) {
    var busy = false
        set(v) {
            field = v
            // respect "Remove animations": the spinner then stays still
            still = android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
            invalidate()
        }
    private var still = false
    private val dp = resources.displayMetrics.density
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lens = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.3f * dp }
    private val glint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * dp; strokeCap = Paint.Cap.ROUND; color = 0xCCFFFFFF.toInt()
    }
    // the requested icon: a rounded square at 50% and a navigation arrow, 1.5 wide strokes on a 24 unit grid
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0x80FFFFFF.toInt(); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val nav = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        setShadowLayer(1.5f * dp, 0f, 0.75f * dp, 0x59000000)
    }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x80FFFFFF.toInt() }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = Color.WHITE }
    private val oval = android.graphics.RectF()
    private val framePath = Path()
    private val navPath = Path()
    private var cx = 0f
    private var cy = 0f
    private var r = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val clamp = android.graphics.Shader.TileMode.CLAMP
        cx = w / 2f; cy = h / 2f
        val outer = minOf(cx, cy)
        r = outer - 3f * dp // padding: room for the shadow, so the glass reads as a layer floating above the game
        shadow.shader = android.graphics.RadialGradient(cx, cy + dp, outer, intArrayOf(0x59000000, 0x2E000000, 0), floatArrayOf(0.7f, r / outer, 1f), clamp)
        body.shader = android.graphics.LinearGradient(0f, cy - r, 0f, cy + r, 0xCC4A5180.toInt(), 0xCC262A40.toInt(), clamp)
        glow.shader = android.graphics.RadialGradient(cx, cy + r * 0.9f, r * 1.1f, 0x705B71FF, 0x005B71FF, clamp)
        lens.shader = android.graphics.RadialGradient(cx, cy, r, intArrayOf(0, 0, 0x2EFFFFFF, 0x0AFFFFFF), floatArrayOf(0f, 0.72f, 0.93f, 1f), clamp)
        sheen.shader = android.graphics.LinearGradient(0f, cy - r * 0.92f, 0f, cy - r * 0.1f, 0x38FFFFFF, 0x00FFFFFF, clamp)
        // SweepGradient: 0 = 3 o'clock, clockwise. Brightest at top-left (0.625), echo at bottom-right (0.125).
        rim.shader = android.graphics.SweepGradient(
            cx, cy,
            intArrayOf(0x55FFFFFF, 0x99FFFFFF.toInt(), 0x22FFFFFF, 0xF2FFFFFF.toInt(), 0x22FFFFFF, 0x55FFFFFF),
            floatArrayOf(0f, 0.125f, 0.375f, 0.625f, 0.875f, 1f),
        )
        // icon on the spinner's 24 unit grid (its ring is r=9 units = 0.42r), so both are the same size
        val u = r * 0.42f / 9f
        frame.strokeWidth = 1.5f * u; nav.strokeWidth = 1.5f * u
        framePath.rewind()
        framePath.moveTo(12f, 22f)
        framePath.cubicTo(7.28595f, 22f, 4.92893f, 22f, 3.46447f, 20.5355f)
        framePath.cubicTo(2f, 19.0711f, 2f, 16.714f, 2f, 12f)
        framePath.cubicTo(2f, 7.28595f, 2f, 4.92893f, 3.46447f, 3.46447f)
        framePath.cubicTo(4.92893f, 2f, 7.28595f, 2f, 12f, 2f)
        framePath.cubicTo(16.714f, 2f, 19.0711f, 2f, 20.5355f, 3.46447f)
        framePath.cubicTo(22f, 4.92893f, 22f, 7.28595f, 22f, 12f)
        framePath.cubicTo(22f, 16.714f, 22f, 19.0711f, 20.5355f, 20.5355f)
        framePath.cubicTo(19.0711f, 22f, 16.714f, 22f, 12f, 22f)
        framePath.close()
        navPath.rewind()
        navPath.moveTo(13.4227f, 17.3618f)
        navPath.lineTo(16.9348f, 8.19598f)
        navPath.cubicTo(17.2164f, 7.46107f, 16.5389f, 6.78361f, 15.804f, 7.06521f)
        navPath.lineTo(6.63824f, 10.5773f)
        navPath.cubicTo(5.80779f, 10.8955f, 5.78079f, 12.06f, 6.5981f, 12.3083f)
        navPath.lineTo(10.0751f, 13.3648f)
        navPath.cubicTo(10.3455f, 13.447f, 10.553f, 13.6545f, 10.6352f, 13.9249f)
        navPath.lineTo(11.6917f, 17.4019f)
        navPath.cubicTo(11.94f, 18.2192f, 13.1045f, 18.1922f, 13.4227f, 17.3618f)
        navPath.close()
        val m = android.graphics.Matrix().apply { setScale(u, u); postTranslate(cx - 12f * u, cy - 12f * u) }
        framePath.transform(m); navPath.transform(m)
    }

    override fun onDraw(c: Canvas) {
        c.drawCircle(cx, cy, minOf(cx, cy), shadow)
        c.drawCircle(cx, cy, r, body)
        c.drawCircle(cx, cy, r, glow)
        c.drawCircle(cx, cy, r, lens)
        c.drawOval(cx - r * 0.62f, cy - r * 0.92f, cx + r * 0.62f, cy - r * 0.1f, sheen)
        c.drawCircle(cx, cy, r - rim.strokeWidth / 2, rim)
        val gr = r - 3f * dp
        oval.set(cx - gr, cy - gr, cx + gr, cy + gr)
        c.drawArc(oval, 205f, 30f, false, glint)
        if (busy) {
            // the requested SVG spinner (ring r=9, 2 thick, at 50% + a 90 degree arc), scaled to sit well inside the glass
            val sr = r * 0.42f
            track.strokeWidth = sr * 2f / 9f; arc.strokeWidth = track.strokeWidth
            oval.set(cx - sr, cy - sr, cx + sr, cy + sr)
            c.drawCircle(cx, cy, sr, track)
            val turn = if (still) 0f else (android.os.SystemClock.uptimeMillis() % 1000L) / 1000f * 360f
            c.drawArc(oval, -90f + turn, 90f, false, arc)
            if (!still) postInvalidateOnAnimation()
            return
        }
        c.drawPath(framePath, frame)
        c.drawPath(navPath, nav)
    }
}
