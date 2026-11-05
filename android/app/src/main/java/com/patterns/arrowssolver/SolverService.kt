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
        const val GAME = "com.ecffri.arrows"
        @Volatile var instance: SolverService? = null; private set
        @Volatile var lastStatus: String = "Ready. Start a level, then tap Analyze or Solve."; private set
        private val IGNORE = setOf("com.android.systemui", "android")
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
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
        main.post { buildUi(); refresh() }
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

    override fun onDestroy() {
        stop = true
        instance = null
        listOfNotNull(bubble, panel, planView, mapView).forEach { runCatching { wm.removeView(it) } }
        worker.shutdownNow()
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
        val screenH = resources.displayMetrics.heightPixels
        planView = PlanView(this).also { wm.addView(it, overlayLp(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, false)) }
        mapView = MapView(this).also { wm.addView(it, overlayLp(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, false)) }

        val size = dp(52)
        bubbleLp = overlayLp(size, size, true).apply { x = dp(8); y = (screenH * 0.03).toInt() }
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
                            lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt()
                            wm.updateViewLayout(this, lp); placePanel()
                        }
                    }
                    MotionEvent.ACTION_UP -> if (!dragged) { panelOpen = !panelOpen; refresh() }
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
            addView(chip("Analyze", Ui.RAISED) { launch { engine.analyze() } })
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

    private fun placePanel() {
        val lp = panelLp ?: return; val b = bubbleLp ?: return
        lp.x = b.x; lp.y = b.y + dp(60)
        panel?.let { wm.updateViewLayout(it, lp) }
    }

    private fun refresh() {
        bubble?.visibility = if (inGame) View.VISIBLE else View.GONE
        panel?.visibility = if (inGame && panelOpen) View.VISIBLE else View.GONE
        planView?.visibility = if (inGame && planShown && planView?.drawing != null) View.VISIBLE else View.GONE
        mapView?.visibility = if (inGame && mapShown && mapView?.drawing != null) View.VISIBLE else View.GONE
    }

    private fun launch(task: () -> Unit) {
        if (!running.compareAndSet(false, true)) { status("Busy — tap Stop first"); return }
        stop = false
        worker.execute {
            try { task() } catch (t: Throwable) { status("⚠ Error: ${t.javaClass.simpleName}: ${t.message}") } finally { running.set(false) }
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

    override fun showMap(d: MapDrawing?) {
        main.post { mapView?.drawing = d; mapView?.invalidate(); refresh() }
    }

    override fun showPlan(d: PlanDrawing?) {
        main.post { planView?.drawing = d; planView?.invalidate(); refresh() }
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
        }
        return out
    }

    override fun capture(lineColor: Int?): Shot? {
        onMain { listOfNotNull(bubble, panel, planView, mapView).forEach { it.alpha = 0f } }
        try {
            Thread.sleep(150) // let the hidden overlays leave the screen
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
                    val shot = Shot(b.width, b.height, px, lineColor)
                    runCatching {
                        java.io.File(debugDir, "f%03d.png".format(debugN++)).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                    b.recycle()
                    return shot
                }
                Thread.sleep(400) // screenshots are rate-limited (~3/s)
            }
            return null
        } finally {
            onMain { listOfNotNull(bubble, panel, planView, mapView).forEach { it.alpha = 1f } }
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

/** Full-screen, touch-through drawing of the analysed arrows (green = can go now). */
class PlanView(ctx: Context) : View(ctx) {
    var drawing: PlanDrawing? = null
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 9f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val loc = IntArray(2)

    override fun onDraw(c: Canvas) {
        val d = drawing ?: return
        getLocationOnScreen(loc)
        c.translate(-loc[0].toFloat(), -loc[1].toFloat())
        for ((pts, color) in d.paths) {
            if (pts.size < 2) continue
            stroke.color = color; stroke.alpha = 190
            val p = Path().apply { moveTo(pts[0], pts[1]); for (i in 2 until pts.size step 2) lineTo(pts[i], pts[i + 1]) }
            c.drawPath(p, stroke)
            fill.color = color
            c.drawCircle(pts[pts.size - 2], pts[pts.size - 1], 13f, fill)
        }
    }
}

/** Whole-level mini-map in the lower part of the screen (touch-through). */
class MapView(ctx: Context) : View(ctx) {
    var drawing: MapDrawing? = null
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

/** Round accent button with an arrow drawn the way the game draws its arrows (rounded line + solid head). */
class BubbleView(ctx: Context) : View(ctx) {
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.ACCENT }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x55FFFFFF }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val r = w / 2
        ring.strokeWidth = w * 0.04f
        c.drawCircle(r, r, r - ring.strokeWidth, disc)
        c.drawCircle(r, r, r - ring.strokeWidth, ring)
        line.strokeWidth = w * 0.085f
        // an L-shaped arrow, like the game's pieces, pointing up-right
        val p = Path().apply { moveTo(w * 0.32f, w * 0.70f); lineTo(w * 0.32f, w * 0.50f); lineTo(w * 0.60f, w * 0.50f); lineTo(w * 0.60f, w * 0.40f) }
        c.drawPath(p, line)
        val h = Path().apply { moveTo(w * 0.60f, w * 0.24f); lineTo(w * 0.72f, w * 0.42f); lineTo(w * 0.48f, w * 0.42f); close() }
        c.drawPath(h, head)
    }
}
