package com.patterns.arrowssolver

import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sign
import kotlin.math.min
import kotlin.math.roundToInt

/** What the engine needs from the phone (implemented by the accessibility service). */
interface Device {
    val stopRequested: Boolean
    fun capture(lineColor: Int?): Shot?
    fun tap(x: Float, y: Float): Boolean
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Boolean
    /** Two fingers at once: a from (ax0,ay0) to (ax1,ay1), b from (bx0,by0) to (bx1,by1). */
    fun pinch(ax0: Float, ay0: Float, ax1: Float, ay1: Float, bx0: Float, by0: Float, bx1: Float, by1: Float, ms: Long): Boolean
    fun overlayRects(): List<Rect>
    fun status(msg: String)
    fun showPlan(d: PlanDrawing?)
    /** Whole-level mini-map drawn by the app (the game can't zoom out far enough for wide boards). */
    fun showMap(d: MapDrawing?)
    /** Debug trail: frames + numbers saved on the phone (Android/data/<pkg>/files/debug). */
    fun debugBegin()
    fun debug(msg: String)
}

class PlanDrawing(val paths: List<Pair<FloatArray, Int>>)

/** Board-space drawing of the whole level: w x h cells, polylines in cell units with colours. */
class MapDrawing(val w: Int, val h: Int, val paths: List<Pair<FloatArray, Int>>)

private enum class Side { LEFT, RIGHT, TOP, BOTTOM }

/**
 * Analyse: screenshot -> lattice -> lines/arrowheads, panning to see boards wider than the screen.
 * Solve: one tap at a time; before every tap the screen must match the model exactly, no arrow may be
 * moving and the hearts must be intact. Any doubt stops the run instead of risking a life.
 */
class Engine(private val dev: Device, private val levelDb: () -> LevelDb?) {
    var board: Board? = null; private set
    private var lineColor: Int? = null
    private var lat: Lattice? = null
    private var di = 0 // board cell = on-screen lattice index + (di, dj)
    private var dj = 0
    private var heartBase = 0
    private val removed = HashSet<Int>()
    /** Board cells whose look never matched the model (e.g. a specially drawn arrow); ignored when verifying. */
    private val dontCare = HashSet<Cell>()
    /** Every cell any arrow of the matched level ever covered (null when the board was read from screen only). */
    private var everCells: Set<Cell>? = null
    /** Solve starts with this wide a board search (set when analysis could not track the camera to the end). */
    private var startWide = 0
    /** Red arrows: tapped while blocked earlier; they leave on their own once free, never tap them. */
    private val pending = HashSet<Int>()
    /** Free arrows the camera can't bring into tapping range (dragging toward them doesn't move the board). */
    private val unreachable = HashSet<Int>()
    private var panTarget = -1
    private var prePan: Pair<Int, Int>? = null

    private fun fail(msg: String): Boolean { dev.status("⚠ $msg"); return false }
    private fun sleep(ms: Long) = Thread.sleep(ms)

    private class Cam(var shot: Shot, var l: Lattice, var ci: Int, var cj: Int)

    fun analyze(attempt: Int = 0): Boolean {
        board = null; everCells = null; removed.clear(); dontCare.clear(); pending.clear(); unreachable.clear(); prePan = null; dev.showPlan(null); dev.debugBegin()
        dev.status("Reading the screen\u2026")
        var (s0, l0) = settle() ?: return false
        // Big boards start zoomed in: zoom out as far as the game allows so fewer pans are needed.
        if (Side.values().any { touches(s0, it) }) {
            if (!zoomOutFully()) return false
            val r = settle() ?: return false
            s0 = r.first; l0 = r.second
        }
        if (s0.guidePx > 3000) {
            if (!gridViewOff(s0)) return false
            val r = settle() ?: return false
            s0 = r.first; l0 = r.second
        }
        lineColor = s0.lineColor
        heartBase = s0.heartPx
        dev.debug("start lattice=$l0 line=${Integer.toHexString(s0.lineColor)} hearts=${s0.heartPx}")
        val sv = Survey(l0.p)
        sv.add(Parser.parse(s0, l0, dev.overlayRects()), 0, 0)
        val cam = Cam(s0, l0, 0, 0)

        // 1) Look the level up among the levels shipped with the game: exact board, even the parts off-screen.
        dev.status("Looking the level up…")
        val db = levelDb()
        var found = db?.match(sv)
        dev.debug("match#1 ${found?.second ?: "no level database"}")
        if (found?.first == null || found.first!!.gone.isNotEmpty()) {
            // 2) Not sure yet, or the level is half played (other arrows may be gone too): see the whole board.
            val err = explore(sv, cam)
            if (err == ZOOMED && attempt < 2) { dev.debug("zoom changed while exploring, starting over"); return analyze(attempt + 1) }
            if (err != null && found?.first == null) return fail(if (err == ZOOMED) "The zoom keeps changing; leave the screen alone and try again" else err)
            if (err != null) {
                // The level is already identified; exploring only looks for more cleared arrows. Keep the match,
                // and let Solve re-find the board over a wide area (the camera may sit after the failed pan).
                dev.debug("exploring stopped ($err); keeping the first match")
                startWide = 16
            } else {
                found = db?.match(sv)
                dev.debug("match#2 ${found?.second ?: "no level database"}")
            }
        }
        val m = found?.first
        val b: Board
        val label: String
        if (m != null) {
            b = m.v.toBoard()
            everCells = b.arrows.flatMapTo(HashSet()) { it.cells }
            di = cam.ci - m.oc; dj = cam.cj - m.or
            removed.addAll(m.gone)
            val occ0 = b.occupancy(removed)
            for (g in sv.red) occ0[Cell(g.c - m.oc, g.r - m.or)]?.let { pending.add(it) }
            dev.debug("gone=${m.gone.size} pending=$pending")
            label = "${m.v.level.modeName} level ${m.v.w}×${m.v.h}"
        } else {
            // 3) Unknown level (the game can also generate levels): fall back to reading every line.
            val res = sv.build()
            dev.debug("build: conflicts=${sv.conflicts.take(10)} problems=${res.problems.take(10)} arrows=${res.board?.arrows?.size}")
            b = res.board ?: return fail("Couldn't read the board: ${res.problems.take(3).joinToString("; ")}")
            di = cam.ci; dj = cam.cj
            label = "board ${b.maxC - b.minC + 1}×${b.maxR - b.minR + 1} (read from screen)"
        }
        val layers = b.plan(removed) ?: return fail("${b.arrows.size} arrows can't all be cleared, so the reading is wrong. Not solving.")
        board = b; lat = cam.l
        learnDontCare(b, cam.shot, cam.l)
        dev.showPlan(drawing(b, cam.l, di, dj))
        dev.showMap(mapDrawing())
        val left = b.arrows.size - removed.size
        val extra = (if (removed.isNotEmpty()) " (${removed.size} already cleared)" else "") +
            (if (pending.isNotEmpty()) ", ${pending.size} red waiting" else "")
        dev.status("${label.replaceFirstChar { it.uppercase() }}: $left arrows left$extra, ${layers.size} rounds. " +
            "${layers[0].size} can go now (green). Tap Solve.")
        return true
    }

    /** Wait until the camera holds still (a new level glides from zoomed-in to its start view). */
    private fun settle(): Pair<Shot, Lattice>? {
        var s0 = dev.capture(lineColor) ?: run { fail("Screenshot failed"); return null }
        var l0 = Grid.find(s0) ?: run { fail("No arrow grid found. Open a level first."); return null }
        for (attempt in 0 until 15) {
            if (dev.stopRequested) { fail("Stopped"); return null }
            sleep(700)
            val s1 = dev.capture(lineColor) ?: run { fail("Screenshot failed"); return null }
            val l1 = Grid.find(s1) ?: continue
            val still = abs(l1.p - l0.p) < 0.3 && abs(l1.ax - l0.ax) < 1.0 && abs(l1.ay - l0.ay) < 1.0
            s0 = s1; l0 = l1
            if (still) return Pair(s0, l0)
            dev.status("Waiting for the board to stop moving\u2026")
        }
        fail("The board keeps moving; try again in a moment")
        return null
    }

    private fun emptyAt(s: Shot, x: Int, y: Int, r: Int, rects: List<Rect>) =
        x - r >= 0 && x + r < s.w && y - r >= s.top && y + r < s.bottom &&
            s.lineCount(x - r, y - r, x + r, y + r) == 0 && s.uiCount(x - r, y - r, x + r, y + r) == 0 &&
            rects.none { Rect.intersects(it, Rect(x - r, y - r, x + r, y + r)) }

    /** Arrows the model still has but whose every visible cell shows the emptied dot: they were cleared before. */
    private fun vanished(b: Board, v: View, di: Int, dj: Int): List<Int> {
        val seen = HashMap<Int, Int>(); val emptied = HashMap<Int, Int>()
        val occ = b.occupancy(removed)
        for ((k, o) in v.node) {
            val a = occ[Cell(k.c + di, k.r + dj)] ?: continue
            if (a in pending) continue
            seen[a] = (seen[a] ?: 0) + 1
            if (!o.v && v.dot[k] == true) emptied[a] = (emptied[a] ?: 0) + 1
        }
        return seen.filter { (a, n) -> n >= 2 && emptied[a] == n }.keys.toList()
    }

    /** The game's '#' grid view draws guide lines that hide the board's empty cells: tap '#' to switch it off. */
    private fun gridViewOff(s: Shot): Boolean {
        if (s.hashX < 0) return fail("Turn off the # grid view, then try again")
        dev.status("Switching the # grid view off\u2026")
        dev.tap(s.hashX.toFloat(), s.hashY.toFloat())
        sleep(800)
        val s2 = dev.capture(lineColor) ?: return fail("Screenshot failed")
        dev.debug("grid view off: guide px ${s.guidePx} -> ${s2.guidePx}")
        return if (s2.guidePx > 3000) fail("Couldn't switch the # grid view off; tap # yourself, then try again") else true
    }

    /** Pan until no side of the board is cut off by the screen edge. Returns an error message, or null. */
    private fun explore(sv: Survey, cam: Cam): String? {
        for (side in Side.values()) {
            var guard = 0
            dev.debug("side $side touches=${touches(cam.shot, side)}")
            while (touches(cam.shot, side) && guard++ < 12) {
                if (dev.stopRequested) return "Stopped"
                dev.status("Board is bigger than the screen, panning ${side.name.lowercase()}…")
                val l = cam.l
                val moved = pan(cam.shot, l, side) ?: return "No empty spot to drag the board from"
                val (s, nl) = settle() ?: return "Lost the board after panning"
                if (abs(nl.p - l.p) > 0.03 * l.p) return ZOOMED
                val v = Parser.parse(s, nl, dev.overlayRects())
                val ei = cam.ci - ((l.ax + moved.first - nl.ax) / l.p).roundToInt()
                val ej = cam.cj - ((l.ay + moved.second - nl.ay) / l.q).roundToInt()
                val ri = if (moved.first != 0f) abs(ei - cam.ci) + 4 else 2
                val rj = if (moved.second != 0f) abs(ej - cam.cj) + 4 else 2
                val reg = sv.register(v, ei, ej, ri, rj)
                dev.debug("pan $side moved=$moved lattice=$nl expect=($ei,$ej)±($ri,$rj) reg=$reg")
                if (reg == null) return "Couldn't line up the panned screenshots"
                if (reg.di == cam.ci && reg.dj == cam.cj) break // camera is at its limit
                sv.add(v, reg.di, reg.dj)
                cam.ci = reg.di; cam.cj = reg.dj; cam.shot = s; cam.l = nl
            }
        }
        return null
    }

    /** Cells that already disagree with the model before any tap (special arrow styles, overlays) are ignored later. */
    private fun learnDontCare(b: Board, s: Shot, l: Lattice) {
        val occ = b.occupancy(removed)
        val v = Parser.parse(s, l, dev.overlayRects(), nodesOnly = true)
        for ((k, o) in v.node) {
            val g = Cell(k.c + di, k.r + dj)
            if (o.v != (g in occ)) dontCare.add(g)
        }
        dev.debug("dontCare ${dontCare.size}/${v.node.size} ${dontCare.take(12)}")
    }

    fun solve(): Boolean {
        if (board == null && !analyze()) return false
        val b = board!!
        dev.showPlan(null)
        var retries = 0
        var wide = startWide // >0 after a pan: search a wider window when locating the board
        startWide = 0
        var reanalysed = false
        var lastTap = -1
        while (true) {
            if (dev.stopRequested) return fail("Stopped (${removed.size}/${b.arrows.size} cleared)")
            val occ = b.occupancy(removed)
            val s = dev.capture(lineColor) ?: return fail("Screenshot failed")
            if (heartBase > 300 && s.heartPx < heartBase * 0.85) return fail("A heart was lost — stopped to protect the rest")
            if (s.busyPx > 40) { sleep(250); continue } // an arrow is still sliding
            if (s.guidePx > 3000) { if (!gridViewOff(s)) return false; continue }
            if (occ.isEmpty()) {
                dev.status("Solved ✓ ${b.arrows.size} arrows cleared")
                return true
            }
            val cur = lat!!
            val nl = Grid.find(s, cur, useDots = true)
            val view = nl?.let { Parser.parse(s, it, dev.overlayRects(), nodesOnly = true) }
            val loc = if (nl == null || view == null) null else {
                val ei = di - ((cur.ax - nl.ax) / cur.p).roundToInt()
                val ej = dj - ((cur.ay - nl.ay) / cur.q).roundToInt()
                locate(b, occ, view, ei, ej, 2 + wide, 2 + wide)
            }
            // Arrows cleared before we started can turn up later as emptied dots. Accept that only when the board
            // position is unambiguous AND, with those arrows removed, every visible cell then matches exactly:
            // wrongly dropping a present arrow could make a blocked arrow look free and cost a life.
            if (loc != null && view != null && loc.bad > 0 && loc.secondBad >= loc.bad + 20) {
                val gone = vanished(b, view, loc.di, loc.dj)
                if (gone.isNotEmpty()) {
                    val trial = HashSet(removed).apply { addAll(gone) }
                    val left = badAt(b, b.occupancy(trial), view, loc.di, loc.dj)
                    if (left == 0) { removed.addAll(gone); dev.debug("already cleared (seen now): $gone"); continue }
                    dev.debug("not marking $gone as cleared: $left other differences remain")
                }
            }
            if (nl == null || loc == null || loc.bad > 0) {
                // The game ignores taps made while the camera still glides (InputUiView: isIgnoreClick = isCameraMoving).
                // If the only difference is the arrow we just tapped, still sitting there unchanged, tap it again.
                if (loc != null && view != null && lastTap >= 0 && retries >= 4) {
                    val extra = view.node.filter { (k, o) -> o.v && Cell(k.c + loc.di, k.r + loc.dj) !in occ && Cell(k.c + loc.di, k.r + loc.dj) !in dontCare }
                        .keys.map { Cell(it.c + loc.di, it.r + loc.dj) }.toSet()
                    val missing = view.node.count { (k, o) -> !o.v && Cell(k.c + loc.di, k.r + loc.dj) in occ && Cell(k.c + loc.di, k.r + loc.dj) !in dontCare }
                    if (extra.isNotEmpty() && missing == 0 && b.arrows[lastTap].cells.containsAll(extra)) {
                        dev.debug("tap on arrow $lastTap was ignored by the game; trying it again")
                        removed.remove(lastTap); lastTap = -1; retries = 0; continue
                    }
                }
                if (loc != null && view != null && (retries == 0 || retries == 30)) {
                    val off = view.node.mapNotNull { (k, o) ->
                        val g = Cell(k.c + loc.di, k.r + loc.dj)
                        if (g in dontCare || o.v == (g in occ)) null
                        else "$g saw=${o.v} dot=${view.dot[k]} arrow=${occ[g]} px=(${nl!!.px(k.c)},${nl.py(k.r)})"
                    }
                    dev.debug("mismatch try=$retries at (${loc.di},${loc.dj}) second=${loc.secondBad}: $off")
                }
                // As the board empties the game re-centres the camera by itself (CameraMoveSystem.FreeAreaFocusY),
                // possibly by more than the normal +-2 cell search: widen it.
                if (retries == 3 && wide < 16) { wide = 16; dev.debug("widening board search (camera may have re-centred)") }
                if (retries == 6) {
                    val fl = Grid.find(s)
                    if (fl != null && abs(fl.p - cur.p) > 0.03 * cur.p) {
                        // The game also changes the zoom by itself as the board empties. The level is still the same:
                        // keep the model, take the new grid and search widely for where the board now sits.
                        dev.debug("zoom changed during solve: ${cur.p} -> ${fl.p}; re-locating the board")
                        lat = fl; retries = 0; wide = 30; continue
                    }
                }
                if (++retries > 30) {
                    if (!reanalysed && removed.isEmpty()) {
                        reanalysed = true
                        if (!analyze()) return false
                        retries = 0; continue
                    }
                    return fail("Screen doesn't match the plan (${loc?.bad ?: "?"} cells off) — stopped for safety")
                }
                sleep(300); continue
            }
            if (loc.secondBad <= 2) return fail("Can't tell exactly where the board is — stopped for safety")
            // the camera must hold still between two frames, or the game would ignore the tap
            val moving = abs(nl.ax - cur.ax) > 1.0 || abs(nl.ay - cur.ay) > 1.0
            retries = 0; wide = 0
            lat = nl; di = loc.di; dj = loc.dj
            if (moving) { sleep(250); continue }
            prePan?.let { if (it == Pair(di, dj) && panTarget >= 0) { unreachable.add(panTarget); dev.debug("drag didn't move the board; skipping arrow $panTarget for now") } }
            prePan = null
            // a red (waiting) arrow slides out by itself as soon as its way is clear
            val autoGone = pending.filter { it !in removed && b.isFree(b.arrows[it], occ) }
            if (autoGone.isNotEmpty()) { removed.addAll(autoGone); dev.debug("red arrows leaving by themselves: $autoGone"); continue }
            val free = b.free(removed).filter { it.id !in pending }
            if (free.isEmpty()) return fail("No arrow can move; the reading must be wrong")
            val pick = chooseTap(b, free, occ, s, nl)
            if (pick == null) {
                prePan = Pair(di, dj)
                val moved = panToward(s, nl, free) ?: return fail("The free arrows are out of reach (off-screen, and the board won't pan there)")
                wide = (maxOf(abs(moved.first), abs(moved.second)) / nl.p).roundToInt() * 2 + 4
                sleep(500); continue
            }
            dev.status("Clearing ${removed.size + 1}/${b.arrows.size}…")
            unreachable.clear() // the board changed: earlier unreachable arrows may be fine now
            if (!dev.tap(pick.x, pick.y)) return fail("Tap was rejected")
            removed.add(pick.arrow)
            lastTap = pick.arrow
            dev.showMap(mapDrawing())
            sleep(350)
        }
    }

    private companion object { const val ZOOMED = "zoom changed" }

    private data class Loc(val di: Int, val dj: Int, val bad: Int, val secondBad: Int)

    /** Visible cells that disagree with the model when board cell = view index + (a, c). */
    private fun badAt(b: Board, occ: Map<Cell, Int>, v: View, a: Int, c: Int): Int {
        var bad = 0
        val ec = everCells
        for ((k, o) in v.node) {
            val g = Cell(k.c + a, k.r + c)
            if (g in dontCare) continue
            if (o.v != (g in occ)) bad++
            // emptied-cell dots mark exactly the cells where arrows used to be: a fingerprint of the level
            else if (!o.v) {
                if (ec != null) { if ((v.dot[k] == true) != (g in ec)) bad++ }
                else if (v.dot[k] == true && !b.inBounds(g)) bad++
            }
        }
        return bad
    }

    /** Where is the board now? Lines must sit exactly on remaining arrows; dots never outside the board. */
    private fun locate(b: Board, occ: Map<Cell, Int>, v: View, ei: Int, ej: Int, ri: Int, rj: Int): Loc? {
        var best: Loc? = null; var second = Int.MAX_VALUE
        for (a in ei - ri..ei + ri) for (c in ej - rj..ej + rj) {
            val bad = badAt(b, occ, v, a, c)
            val bb = best
            if (bb == null || bad < bb.bad || (bad == bb.bad && abs(a - ei) + abs(c - ej) < abs(bb.di - ei) + abs(bb.dj - ej))) {
                if (bb != null) second = min(second, bb.bad)
                best = Loc(a, c, bad, 0)
            } else second = min(second, bad)
        }
        return best?.copy(secondBad = second)
    }

    private data class Tap(val arrow: Int, val x: Float, val y: Float)

    /** Pick the visible cell of a free arrow that is farthest from any blocked arrow. */
    private fun chooseTap(b: Board, free: List<Arrow>, occ: Map<Cell, Int>, s: Shot, l: Lattice): Tap? {
        val freeIds = free.map { it.id }.toSet()
        val blockedCells = occ.filterValues { it !in freeIds }.keys
        val rects = dev.overlayRects().map { Rect(it.left - l.p.toInt(), it.top - l.p.toInt(), it.right + l.p.toInt(), it.bottom + l.p.toInt()) }
        val ui = (0.6 * l.p).toInt()
        var best: Tap? = null; var bestScore = -1.0
        // first pass avoids cells we can't verify (special arrow look); second pass allows them
        for (allowOdd in listOf(false, true)) {
        if (best != null) break
        for (a in free) for (x in a.cells) {
            if (!allowOdd && x in dontCare) continue
            val sx = l.px(x.c - di); val sy = l.py(x.r - dj)
            val edge = maxOf(14.0, 0.35 * l.p)
            if (sx < edge || sx > s.w - edge || sy < s.top + 0.5 * l.q || sy > s.bottom - 0.5 * l.q) continue
            if (rects.any { it.contains(sx, sy) } || s.uiCount(sx - ui, sy - ui, sx + ui, sy + ui) > 0) continue
            if (Parser.inEdgeHandle(s, sx, sy, l.p)) continue
            val clear = blockedCells.minOfOrNull { it.dist(x) } ?: 99.0
            if (clear > bestScore) { bestScore = clear; best = Tap(a.id, sx.toFloat(), sy.toFloat()) }
        }
        }
        return best
    }

    /** Does the board continue past this side? Lines crossing the edge, or emptied-cell dots right at it. */
    private fun touches(s: Shot, side: Side): Boolean {
        val d = 24 // dots are ~8 px wide and 35-57 px apart: look a little way in from the edge
        return when (side) {
            Side.LEFT -> s.lineCount(0, s.top, 2, s.bottom - 1) >= 4 || s.dotCount(0, s.top, d, s.bottom - 1) >= 12
            Side.RIGHT -> s.lineCount(s.w - 3, s.top, s.w - 1, s.bottom - 1) >= 4 || s.dotCount(s.w - d, s.top, s.w - 1, s.bottom - 1) >= 12
            Side.TOP -> s.lineCount(0, s.top, s.w - 1, s.top + 2) >= 4 || s.dotCount(0, s.top, s.w - 1, s.top + d) >= 12
            Side.BOTTOM -> s.lineCount(0, s.bottom - 3, s.w - 1, s.bottom - 1) >= 4 || s.dotCount(0, s.bottom - d, s.w - 1, s.bottom - 1) >= 12
        }
    }

    /** Drag the board so the hidden [side] comes into view. Returns how far the finger moved. */
    private fun pan(s: Shot, l: Lattice, side: Side): Pair<Float, Float>? {
        val dx = when (side) { Side.LEFT -> 0.42f * s.w; Side.RIGHT -> -0.42f * s.w; else -> 0f }
        val dy = when (side) { Side.TOP -> 0.35f * (s.bottom - s.top); Side.BOTTOM -> -0.35f * (s.bottom - s.top); else -> 0f }
        return drag(s, l, dx, dy)
    }

    /** Pan toward the free arrow nearest the middle of the screen. */
    private fun panToward(s: Shot, l: Lattice, free: List<Arrow>): Pair<Float, Float>? {
        val cx = s.w / 2.0; val cy = (s.top + s.bottom) / 2.0
        var bx = cx; var by = cy; var bd = Double.MAX_VALUE
        for (a in free) if (a.id !in unreachable) for (x in a.cells) {
            val px = l.x(x.c - di); val py = l.y(x.r - dj)
            val d = hypot(px - cx, py - cy)
            if (d < bd) { bd = d; bx = px; by = py; panTarget = a.id }
        }
        if (bd == Double.MAX_VALUE) return null
        var dx = when {
            bx < 0.05 * s.w -> min(0.42 * s.w, cx - bx)
            bx > 0.95 * s.w -> -min(0.42 * s.w, bx - cx)
            else -> 0.0
        }
        var dy = if (dx != 0.0) 0.0 else when {
            by < s.top + l.q -> min(0.35 * (s.bottom - s.top), cy - by)
            by > s.bottom - l.q -> -min(0.35 * (s.bottom - s.top), by - cy)
            else -> 0.0
        }
        // on screen but hidden by the panel or the '#' button: nudge it toward the middle
        if (dx == 0.0 && dy == 0.0) dy = if (by < cy) 4 * l.q else -4 * l.q
        dev.debug("panToward target=(${bx.toInt()},${by.toInt()}) d=(${dx.toInt()},${dy.toInt()})")
        return drag(s, l, dx.toFloat(), dy.toFloat())
    }

    /**
     * Two-finger pan. In the game (InputUiView.OnPointerDown) arrows are only picked when fingerCount <= 1, and a single
     * finger within 2.5 cells of an arrow marks it "touching", which locks the camera (CameraMoveSystem._lockedUntilFingerUp).
     * Two fingers landing together never pick an arrow; their midpoint pans the board (PanGesture/MoveDolly).
     * Starts stay >= 12% of the width from the screen edges so Android's edge "back" swipe never triggers.
     */
    private fun drag(s: Shot, l: Lattice, dx: Float, dy: Float): Pair<Float, Float>? {
        val ux = sign(dx); val uy = sign(dy)
        if (ux == 0f && uy == 0f) return null
        val mx = 0.12f * s.w; val my = 0.06f * (s.bottom - s.top)
        val x0 = when { ux > 0 -> mx; ux < 0 -> s.w - mx; else -> s.w / 2f }
        val y0 = when { uy > 0 -> s.top + my; uy < 0 -> s.bottom - my; else -> (s.top + s.bottom) / 2f }
        val room = if (ux != 0f) s.w - 2 * mx else (s.bottom - s.top) - 2 * my
        val len = min(max(abs(dx), abs(dy)), room)
        if (len < 2f * l.p) return null
        val gap = max(90f, 1.5f * l.p.toFloat())
        val ox = if (ux != 0f) 0f else gap / 2; val oy = if (ux != 0f) gap / 2 else 0f
        val ok = dev.pinch(
            x0 - ox, y0 - oy, x0 - ox + ux * len, y0 - oy + uy * len,
            x0 + ox, y0 + oy, x0 + ox + ux * len, y0 + oy + uy * len, 900,
        )
        dev.debug("2-finger drag from (${x0.toInt()},${y0.toInt()}) by (${(ux * len).toInt()},${(uy * len).toInt()}) ok=$ok")
        if (!ok) return null
        sleep(700) // let the camera settle
        return Pair(ux * len, uy * len)
    }

    /**
     * Zoom out as far as the game allows: two fingers land together (so no arrow is picked) and pinch in.
     * Game limits (CameraZoomUtility, portrait): orthographic size 15..30 => at most 27 cells across a
     * 9:20 screen, unless the server-side LevelBasedZoom experiment is on (then the whole width + 12 cells).
     */
    fun zoomOutFully(): Boolean {
        dev.status("Zooming out\u2026")
        var (s, l) = settle() ?: return false
        for (i in 0 until 4) {
            if (dev.stopRequested) return fail("Stopped")
            val band = (s.bottom - s.top).toFloat()
            val cx = s.w / 2f; val y1 = s.top + 0.12f * band; val y2 = s.bottom - 0.12f * band; val ym = (y1 + y2) / 2
            val ok = dev.pinch(
                cx - 0.18f * s.w, y1, cx - 0.04f * s.w, ym - 0.06f * band,
                cx + 0.18f * s.w, y2, cx + 0.04f * s.w, ym + 0.06f * band, 450,
            )
            sleep(500)
            val (s2, l2) = settle() ?: return false
            dev.debug("zoomOut $i ok=$ok pitch ${l.p} -> ${l2.p}")
            val shrank = l2.p < l.p * 0.97
            s = s2; l = l2
            if (!ok || !shrank) break
        }
        dev.status("Zoomed out as far as the game allows (${(s.w / l.p).roundToInt()} cells across)")
        return true
    }

    /** Whole level for the mini-map: green = can go now, orange = blocked, red = waiting (tapped while blocked). */
    fun mapDrawing(): MapDrawing? {
        val b = board ?: return null
        val free = b.free(removed).map { it.id }.toSet()
        val paths = b.arrows.filter { it.id !in removed }.map { a ->
            val pts = FloatArray(a.cells.size * 2)
            a.cells.forEachIndexed { k, x -> pts[2 * k] = (x.c - b.minC).toFloat(); pts[2 * k + 1] = (x.r - b.minR).toFloat() }
            Pair(pts, when { a.id in pending -> 0xFFFF4B5D.toInt(); a.id in free -> 0xFF35E07A.toInt(); else -> 0xFFFF9F43.toInt() })
        }
        return MapDrawing(b.maxC - b.minC + 1, b.maxR - b.minR + 1, paths)
    }

    private fun drawing(b: Board, l: Lattice, di: Int, dj: Int): PlanDrawing {
        val free = b.free(removed).map { it.id }.toSet()
        val paths = b.arrows.filter { it.id !in removed }.map { a ->
            val pts = FloatArray(a.cells.size * 2)
            a.cells.forEachIndexed { k, x -> pts[2 * k] = l.x(x.c - di).toFloat(); pts[2 * k + 1] = l.y(x.r - dj).toFloat() }
            Pair(pts, if (a.id in free) 0xFF35E07A.toInt() else 0xFFFF9F43.toInt())
        }
        return PlanDrawing(paths)
    }

}
