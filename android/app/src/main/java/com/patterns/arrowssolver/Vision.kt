package com.patterns.arrowssolver

import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Board band as a fraction of screen height: hearts/progress header above, nav bar below. */
const val BAND_TOP = 0.16
const val BAND_BOTTOM = 0.955
private const val DEFAULT_LINE = 0xACB4F4

/**
 * One screenshot, classified per pixel. Colours were measured on the device:
 * line (172,180,244), empty-cell dot (58,62,85), '#' button (64,70,104),
 * moving arrow highlight (111..131,130..147,250), blocked arrow / hearts red (255,75,93).
 */
class Shot(val w: Int, val h: Int, px: IntArray, lineColorIn: Int?) {
    val top = (BAND_TOP * h).toInt()
    val bottom = (BAND_BOTTOM * h).toInt()
    val lineColor: Int = lineColorIn ?: detectLineColor(w, h, px, top, bottom)
    val line = BooleanArray(w * h)
    val dot = BooleanArray(w * h)
    /** Red = an arrow tapped while blocked; it waits and leaves by itself once its way is clear. */
    val red = BooleanArray(w * h)
    var busyPx = 0; private set
    var heartPx = 0; private set
    /** Pixels of the '#' grid-view guide lines (56,64,95) inside the band. */
    var guidePx = 0; private set
    /** Centre of the '#' button (bottom right), or -1. */
    var hashX = -1; private set
    var hashY = -1; private set
    private val lineSum = IntArray((w + 1) * (h + 1))
    private val uiSum = IntArray((w + 1) * (h + 1))

    init {
        val lr = (lineColor shr 16) and 255
        val lg = (lineColor shr 8) and 255
        val lb = lineColor and 255
        val hy0 = (0.085 * h).toInt(); val hy1 = (0.135 * h).toInt()
        val hx0 = (0.33 * w).toInt(); val hx1 = (0.67 * w).toInt()
        var hx = 0L; var hy = 0L; var hn = 0
        for (y in 0 until h) {
            val o = y * w
            var rl = 0; var ru = 0
            val inBand = y in top until bottom
            for (x in 0 until w) {
                val c = px[o + x]
                val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
                val red = r > 200 && g < 130 && b < 140
                val isLine = inBand && ((abs(r - lr) < 40 && abs(g - lg) < 40 && abs(b - lb) < 35) || red)
                line[o + x] = isLine
                this.red[o + x] = inBand && red
                dot[o + x] = abs(r - 58) < 8 && abs(g - 62) < 6 && abs(b - 85) < 6
                if (inBand && abs(r - 56) < 5 && abs(g - 64) < 5 && abs(b - 95) < 5) guidePx++
                val ui = abs(r - 66) < 8 && abs(g - 71) < 9 && abs(b - 104) < 9
                if (inBand && r in 95..150 && g in 115..165 && b > 235) busyPx++
                if (red && y in hy0..hy1 && x in hx0..hx1) heartPx++
                if (isLine) rl++
                if (ui) { ru++; if (x > 0.6 * w && y > 0.82 * h) { hx += x; hy += y; hn++ } }
                val i1 = (y + 1) * (w + 1) + x + 1
                lineSum[i1] = lineSum[i1 - (w + 1)] + rl
                uiSum[i1] = uiSum[i1 - (w + 1)] + ru
            }
        }
        if (hn > 200) { hashX = (hx / hn).toInt(); hashY = (hy / hn).toInt() }
    }

    private fun box(sum: IntArray, x0: Int, y0: Int, x1: Int, y1: Int): Int {
        val a = max(0, x0); val b = max(0, y0); val c = min(w - 1, x1); val d = min(h - 1, y1)
        if (a > c || b > d) return 0
        val W = w + 1
        return sum[(d + 1) * W + c + 1] - sum[b * W + c + 1] - sum[(d + 1) * W + a] + sum[b * W + a]
    }

    fun lineCount(x0: Int, y0: Int, x1: Int, y1: Int) = box(lineSum, x0, y0, x1, y1)
    fun uiCount(x0: Int, y0: Int, x1: Int, y1: Int) = box(uiSum, x0, y0, x1, y1)
    fun lineAt(x: Int, y: Int) = x in 0 until w && y in 0 until h && line[y * w + x]

    fun dotCount(x0: Int, y0: Int, x1: Int, y1: Int): Int {
        var n = 0
        for (y in max(0, y0)..min(h - 1, y1)) for (x in max(0, x0)..min(w - 1, x1)) if (dot[y * w + x]) n++
        return n
    }

    companion object {
        /** Most common bright colour in the band that is far from the background. */
        fun detectLineColor(w: Int, h: Int, px: IntArray, top: Int, bottom: Int): Int {
            val cnt = IntArray(4096); val sr = LongArray(4096); val sg = LongArray(4096); val sb = LongArray(4096)
            for (y in top until bottom step 3) for (x in 0 until w step 3) {
                val c = px[y * w + x]
                val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
                val k = (r shr 4 shl 8) or (g shr 4 shl 4) or (b shr 4)
                cnt[k]++; sr[k] += r.toLong(); sg[k] += g.toLong(); sb[k] += b.toLong()
            }
            val bg = cnt.indices.maxByOrNull { cnt[it] } ?: return DEFAULT_LINE
            fun mean(k: Int) = Triple(sr[k] / cnt[k], sg[k] / cnt[k], sb[k] / cnt[k])
            val (br, bgG, bb) = mean(bg)
            var best = -1
            for (k in cnt.indices) {
                if (cnt[k] < 50) continue
                val (r, g, b) = mean(k)
                val d2 = (r - br) * (r - br) + (g - bgG) * (g - bgG) + (b - bb) * (b - bb)
                if (d2 < 100 * 100 || r + g + b < br + bgG + bb + 200) continue
                if (r > 200 && g < 130) continue // red blocked arrow
                if (best < 0 || cnt[k] > cnt[best]) best = k
            }
            if (best < 0) return DEFAULT_LINE
            val (r, g, b) = mean(best)
            return (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
        }
    }
}

/** Node (i, j) of the on-screen lattice sits at pixel (ax + i*p, ay + j*q). */
data class Lattice(val ax: Double, val ay: Double, val p: Double, val q: Double) {
    fun x(i: Int) = ax + i * p
    fun y(j: Int) = ay + j * q
    fun px(i: Int) = x(i).roundToInt()
    fun py(j: Int) = y(j).roundToInt()
}

object Grid {
    /** Find the lattice. With [fixed], keep its pitch and only re-measure the phase. */
    fun find(s: Shot, fixed: Lattice? = null, useDots: Boolean = false): Lattice? {
        val col = DoubleArray(s.w); val row = DoubleArray(s.h)
        for (y in s.top until s.bottom) {
            val o = y * s.w
            for (x in 0 until s.w) if (s.line[o + x] || (useDots && s.dot[o + x])) { col[x] += 1.0; row[y] += 1.0 }
        }
        // A nearly cleared board has few lines left; the emptied-cell dots then carry the grid.
        val retry = { if (useDots) null else find(s, fixed, useDots = true) }
        if (col.sum() < 100) return retry()
        val p0 = fixed?.p ?: period(col) ?: return retry()
        val q0 = fixed?.q ?: period(row.copyOfRange(s.top, s.bottom)) ?: p0
        val (ax, p) = fit(col, p0, 0, s.w, fixed != null) ?: return retry()
        val (ay, q) = fit(row, q0, s.top, s.bottom, fixed != null) ?: return retry()
        if (abs(p - q) > 0.1 * p) return retry() // cells are square in this game
        return Lattice(ax, ay, p, q)
    }

    /** Smallest strong autocorrelation lag = grid pitch in pixels. */
    fun period(proj: DoubleArray, minLag: Int = 12): Double? {
        val n = proj.size
        val maxLag = min(240, n / 3)
        if (maxLag <= minLag + 2) return null
        val m = proj.average()
        val d = DoubleArray(n) { proj[it] - m }
        val ac = DoubleArray(maxLag + 2)
        for (lag in minLag - 1..maxLag + 1) {
            var s = 0.0
            for (i in 0 until n - lag) s += d[i] * d[i + lag]
            ac[lag] = s / (n - lag)
        }
        var best = 0.0
        for (lag in minLag..maxLag) best = max(best, ac[lag])
        if (best <= 0) return null
        for (lag in minLag..maxLag) {
            if (ac[lag] >= 0.75 * best && ac[lag] >= ac[lag - 1] && ac[lag] >= ac[lag + 1]) {
                val a = ac[lag - 1]; val b = ac[lag]; val c = ac[lag + 1]
                val den = a - 2 * b + c
                return lag + if (den != 0.0) 0.5 * (a - c) / den else 0.0
            }
        }
        return null
    }

    /** Phase (and pitch unless fixed) from peak centroids, least squares. */
    private fun fit(proj: DoubleArray, p: Double, lo: Int, hi: Int, fixed: Boolean): Pair<Double, Double>? {
        var bestPhi = 0.0; var bestS = -1.0; var phi = 0.0
        while (phi < p) {
            var s = 0.0; var x = phi
            while (x < hi) { if (x >= lo) s += proj[x.toInt()]; x += p }
            if (s > bestS) { bestS = s; bestPhi = phi }
            phi += 0.25
        }
        var peak = 0.0
        for (i in lo until hi) peak = max(peak, proj[i])
        if (peak <= 0) return null
        val ks = ArrayList<Double>(); val xs = ArrayList<Double>()
        val rad = max(2, (p / 4).toInt())
        var k = 0
        while (true) {
            val c = bestPhi + k * p
            if (c >= hi) break
            if (c >= lo) {
                var am = -1; var av = -1.0
                for (i in max(lo, (c - rad).toInt())..min(hi - 1, (c + rad).toInt())) if (proj[i] > av) { av = proj[i]; am = i }
                if (am >= 0 && av >= 0.25 * peak) {
                    var sw = 0.0; var sx = 0.0
                    for (i in max(lo, am - 3)..min(hi - 1, am + 3)) { sw += proj[i]; sx += proj[i] * i }
                    if (sw > 0) { ks.add(k.toDouble()); xs.add(sx / sw) }
                }
            }
            k++
        }
        if (ks.size < 2) return if (fixed) Pair(bestPhi, p) else null
        if (fixed || ks.size < 3) return Pair(xs.indices.sumOf { xs[it] - ks[it] * p } / xs.size, p)
        val mk = ks.average(); val mx = xs.average()
        var sxx = 0.0; var sxy = 0.0
        for (i in ks.indices) { sxx += (ks[i] - mk).pow(2); sxy += (ks[i] - mk) * (xs[i] - mx) }
        val b = sxy / sxx
        return Pair(mx - b * mk, b)
    }
}

/** An observation plus how trustworthy it is (distance from screen/band edge, in cells). */
class Obs<T>(val v: T, val q: Double)

/** Everything read from one screenshot, in that screenshot's lattice indices. */
class View(val lat: Lattice) {
    val node = HashMap<Cell, Obs<Boolean>>()
    val dot = HashMap<Cell, Boolean>()
    val red = HashMap<Cell, Boolean>()
    /** 1 = line joins this node to the next one right/down, 0 = no line, -1 = unclear. */
    val hEdge = HashMap<Cell, Obs<Int>>()
    val vEdge = HashMap<Cell, Obs<Int>>()
    /** Arrowhead probe per (node, outward direction): [tip pixels hit (0..4), base width px]. */
    val head = HashMap<Pair<Cell, Dir>, Obs<IntArray>>()
}

object Parser {
    fun parse(s: Shot, lat: Lattice, exclude: List<Rect>, nodesOnly: Boolean = false): View {
        val v = View(lat)
        val p = lat.p
        val m = max(6, (0.2 * p).toInt())
        val pad = (0.5 * p).toInt()
        val ex = exclude.map { Rect(it.left - pad, it.top - pad, it.right + pad, it.bottom + pad) }
        val uiR = (0.6 * p).toInt()
        fun visible(x: Int, y: Int): Boolean {
            if (x < m || x >= s.w - m || y < s.top + m || y >= s.bottom - m) return false
            if (ex.any { it.contains(x, y) }) return false
            if (inEdgeHandle(s, x, y, p)) return false
            return s.uiCount(x - uiR, y - uiR, x + uiR, y + uiR) == 0
        }
        fun quality(x: Int, y: Int) = min(min(x, s.w - x), min(y - s.top, s.bottom - y)) / p
        val iLo = ceil(-lat.ax / p).toInt(); val iHi = floor((s.w - lat.ax) / p).toInt()
        val jLo = ceil((s.top - lat.ay) / lat.q).toInt(); val jHi = floor((s.bottom - lat.ay) / lat.q).toInt()
        val nb = max(2, (0.075 * p).roundToInt())
        val db = max(3, (0.12 * p).roundToInt())
        for (i in iLo..iHi) for (j in jLo..jHi) {
            val x = lat.px(i); val y = lat.py(j)
            if (!visible(x, y)) continue
            val k = Cell(i, j)
            val q = quality(x, y)
            v.node[k] = Obs(s.lineCount(x - nb, y - nb, x + nb, y + nb) > 0, q)
            var dots = 0
            for (yy in y - db..y + db) for (xx in x - db..x + db) if (xx in 0 until s.w && yy in 0 until s.h && s.dot[yy * s.w + xx]) dots++
            v.dot[k] = dots >= 5
            var reds = 0
            for (yy in y - nb..y + nb) for (xx in x - nb..x + nb) if (xx in 0 until s.w && yy in 0 until s.h && s.red[yy * s.w + xx]) reds++
            v.red[k] = reds >= 4
            if (nodesOnly) continue
            // edges to the right / below: lavender across the middle of the gap?
            val t0 = (0.33 * p).roundToInt(); val t1 = (0.68 * p).roundToInt(); val hb = max(2, (0.1 * p).roundToInt())
            val xr = lat.px(i + 1); val yd = lat.py(j + 1)
            if (visible(xr, y)) {
                var hit = 0
                for (t in t0..t1) if (s.lineCount(x + t, y - hb, x + t, y + hb) > 0) hit++
                v.hEdge[k] = Obs(edgeVal(hit, t1 - t0 + 1), min(q, quality(xr, y)))
            }
            if (visible(x, yd)) {
                var hit = 0
                for (t in t0..t1) if (s.lineCount(x - hb, y + t, x + hb, y + t) > 0) hit++
                v.vEdge[k] = Obs(edgeVal(hit, t1 - t0 + 1), min(q, quality(x, yd)))
            }
            // arrowhead probes in each outward direction
            val tips = doubleArrayOf(0.2, 0.225, 0.25, 0.275).map { (it * p).roundToInt() }
            val bases = doubleArrayOf(-0.05, 0.0, 0.05, 0.1).map { (it * p).roundToInt() }
            val sp = max(1, (0.05 * p).roundToInt()); val wr = (0.33 * p).roundToInt()
            for (d in Dir.values()) {
                if (!visible(x + (d.dx * 0.4 * p).toInt(), y + (d.dy * 0.4 * p).toInt())) continue
                val pxp = -d.dy; val pyp = d.dx // perpendicular
                var tip = 0
                for (t in tips) if ((-sp..sp).any { u -> s.lineAt(x + d.dx * t + pxp * u, y + d.dy * t + pyp * u) }) tip++
                var width = 0
                for (t in bases) width = max(width, (-wr..wr).count { u -> s.lineAt(x + d.dx * t + pxp * u, y + d.dy * t + pyp * u) })
                v.head[Pair(k, d)] = Obs(intArrayOf(tip, width), q)
            }
        }
        return v
    }

    /**
     * Nothing OS draws an edge handle (dark rounded bar, ~22 px wide) on the left at about 14%..26% of the height.
     * It hides the board under it, and touching it can open the side panel: treat it as covered.
     */
    fun inEdgeHandle(s: Shot, x: Int, y: Int, p: Double) = x < 0.025 * s.w + 0.5 * p && y > 0.13 * s.h && y < 0.27 * s.h

    private fun edgeVal(hit: Int, n: Int): Int {
        val f = hit.toDouble() / n
        return if (f >= 0.95) 1 else if (f <= 0.15) 0 else -1
    }
}

/** Observations merged from several panned screenshots, in global lattice coordinates. */
class Survey(val pitch: Double) {
    val node = HashMap<Cell, Obs<Boolean>>()
    val dot = HashSet<Cell>()
    val red = HashSet<Cell>()
    val hEdge = HashMap<Cell, Obs<Int>>()
    val vEdge = HashMap<Cell, Obs<Int>>()
    val head = HashMap<Pair<Cell, Dir>, Obs<IntArray>>()
    val conflicts = ArrayList<String>()

    fun add(v: View, di: Int, dj: Int) {
        fun g(k: Cell) = Cell(k.c + di, k.r + dj)
        for ((k, o) in v.node) {
            val old = node[g(k)]
            if (old != null && old.v != o.v && min(old.q, o.q) >= 2.0) conflicts.add("node ${g(k)}")
            if (old == null || o.q > old.q) node[g(k)] = o
        }
        for ((k, d) in v.dot) if (d) dot.add(g(k))
        for ((k, d) in v.red) if (d) red.add(g(k))
        mergeEdges(hEdge, v.hEdge, ::g, "h")
        mergeEdges(vEdge, v.vEdge, ::g, "v")
        for ((k, o) in v.head) {
            val gk = Pair(g(k.first), k.second)
            val old = head[gk]
            if (old == null || o.q > old.q) head[gk] = o
        }
    }

    private fun mergeEdges(into: HashMap<Cell, Obs<Int>>, from: Map<Cell, Obs<Int>>, g: (Cell) -> Cell, tag: String) {
        for ((k, o) in from) {
            val gk = g(k)
            val old = into[gk]
            when {
                old == null -> into[gk] = o
                o.v < 0 -> {}
                old.v < 0 -> into[gk] = o
                else -> {
                    if (old.v != o.v && min(old.q, o.q) >= 2.0) conflicts.add("$tag-edge $gk")
                    if (o.q > old.q) into[gk] = o
                }
            }
        }
    }

    /** True if a and b are orthogonal neighbours joined by a line. */
    fun linked(a: Cell, b: Cell): Boolean = when {
        b.r == a.r && b.c == a.c + 1 -> hEdge[a]?.v == 1
        b.r == a.r && a.c == b.c + 1 -> hEdge[b]?.v == 1
        b.c == a.c && b.r == a.r + 1 -> vEdge[a]?.v == 1
        b.c == a.c && a.r == b.r + 1 -> vEdge[b]?.v == 1
        else -> false
    }

    data class Reg(val di: Int, val dj: Int, val bad: Int, val total: Int, val secondRate: Double)

    /** Find the index offset that maps [v] onto what we have already seen. */
    fun register(v: View, ei: Int, ej: Int, ri: Int, rj: Int): Reg? {
        var best: Reg? = null; var bestRate = 2.0; var secondRate = 2.0
        for (di in ei - ri..ei + ri) for (dj in ej - rj..ej + rj) {
            var bad = 0; var tot = 0
            for ((k, o) in v.node) { val g = node[Cell(k.c + di, k.r + dj)] ?: continue; tot++; if (g.v != o.v) bad++ }
            for ((k, o) in v.hEdge) { if (o.v < 0) continue; val g = hEdge[Cell(k.c + di, k.r + dj)] ?: continue; if (g.v < 0) continue; tot++; if (g.v != o.v) bad++ }
            for ((k, o) in v.vEdge) { if (o.v < 0) continue; val g = vEdge[Cell(k.c + di, k.r + dj)] ?: continue; if (g.v < 0) continue; tot++; if (g.v != o.v) bad++ }
            if (tot < 60) continue
            val rate = bad.toDouble() / tot
            val closer = best != null && rate == bestRate && abs(di - ei) + abs(dj - ej) < abs(best.di - ei) + abs(best.dj - ej)
            if (rate < bestRate || closer) {
                if (best != null) secondRate = min(secondRate, bestRate)
                bestRate = rate; best = Reg(di, dj, bad, tot, 0.0)
            } else secondRate = min(secondRate, rate)
        }
        val b = best ?: return null
        if (bestRate > 0.02 || secondRate < bestRate + 0.05) return null
        return b.copy(secondRate = secondRate)
    }

    class Result(val board: Board?, val problems: List<String>)

    /** Turn merged observations into arrows: each connected line is a path with exactly one arrowhead. */
    fun build(): Result {
        val problems = ArrayList<String>()
        if (conflicts.size > 3) problems.add("${conflicts.size} disagreements between screenshots (e.g. ${conflicts.take(3)})")
        val nodes = node.filterValues { it.v }.keys
        if (nodes.isEmpty()) return Result(null, listOf("no arrows found"))
        val adj = HashMap<Cell, MutableList<Cell>>()
        for (n in nodes) adj[n] = ArrayList()
        for ((map, d) in listOf(hEdge to Dir.R, vEdge to Dir.D)) for ((k, o) in map) {
            val a = k; val b = k + d
            if (a !in nodes || b !in nodes) { if (o.v == 1) problems.add("line without node at $k"); continue }
            when (o.v) {
                1 -> { adj[a]!!.add(b); adj[b]!!.add(a) }
                -1 -> problems.add("unclear link at $k")
            }
        }
        val seen = HashSet<Cell>()
        val arrows = ArrayList<Arrow>()
        for (n in nodes.sortedWith(compareBy({ it.r }, { it.c }))) {
            if (n in seen) continue
            val comp = ArrayList<Cell>(); val stack = ArrayDeque(listOf(n)); seen.add(n)
            while (stack.isNotEmpty()) {
                val u = stack.removeLast(); comp.add(u)
                for (w in adj[u]!!) if (seen.add(w)) stack.addLast(w)
            }
            val ends = comp.filter { adj[it]!!.size == 1 }
            if (comp.any { adj[it]!!.size > 2 } || ends.size != 2) { problems.add("tangled line near $n"); continue }
            val headTh = 0.3 * pitch; val tailTh = 0.225 * pitch
            var head: Pair<Cell, Dir>? = null; var tails = 0
            for (e in ends) {
                val nb = adj[e]!![0]
                val out = Dir.values().first { it.dx == e.c - nb.c && it.dy == e.r - nb.r }
                val o = this.head[Pair(e, out)]?.v
                when {
                    o == null -> {}
                    o[0] >= 3 && o[1] >= headTh -> head = if (head == null) Pair(e, out) else null.also { tails = -9 }
                    o[0] == 0 && o[1] <= tailTh -> tails++
                }
            }
            val h = head
            if (h == null || tails != 1) { problems.add("can't tell the arrowhead near $n"); continue }
            val path = ArrayList<Cell>()
            var cur = ends.first { it != h.first }; var prev: Cell? = null
            path.add(cur)
            while (cur != h.first) {
                val nxt = adj[cur]!!.first { it != prev }
                prev = cur; cur = nxt; path.add(cur)
            }
            arrows.add(Arrow(arrows.size, path, h.second))
        }
        if (problems.isNotEmpty()) return Result(null, problems)
        val b = Board(
            arrows,
            nodes.minOf { it.c }, nodes.maxOf { it.c }, nodes.minOf { it.r }, nodes.maxOf { it.r },
            node.keys.toHashSet(),
        )
        return Result(b, problems)
    }
}
