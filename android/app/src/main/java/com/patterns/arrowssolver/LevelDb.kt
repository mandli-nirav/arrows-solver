package com.patterns.arrowssolver

import java.io.DataInputStream
import java.io.InputStream
import kotlin.math.max

/**
 * The 4,852 levels shipped in the game APK (levels-{main,daily,thunderrush} Addressables bundles),
 * packed into assets/levels.bin by levels/pack (see PROGRESS.md). Arrow = path vertices, head first,
 * cell index x + W*y; bends may be diagonal, heads are always axis-aligned.
 */
class Level(val mode: Int, val name: String, val w: Int, val h: Int, val flags: Int, val special: Int, val verts: Array<IntArray>) {
    val modeName get() = when (mode) { 0 -> "main"; 1 -> "daily"; else -> "thunder rush" }
}

/**
 * A level as it appears on screen. Verified on device: data y=0 is the BOTTOM row, so by default
 * row = H-1-y. The game can mirror levels at runtime, so [mx]/[my] add horizontal/vertical flips.
 */
class Variant(val level: Level, val mx: Boolean, val my: Boolean) {
    val w = level.w
    val h = level.h
    private fun col(x: Int) = if (mx) w - 1 - x else x
    private fun row(y: Int) = if (my) y else h - 1 - y

    /** Sorted packed (col, row, dir) of every arrowhead. */
    val heads: IntArray = IntArray(level.verts.size).also { hs ->
        for ((k, v) in level.verts.withIndex()) {
            val a = v[0]; val b = v[1]
            val ax = a % w; val ay = a / w
            val nx = ax + Integer.signum(b % w - ax); val ny = ay + Integer.signum(b / w - ay)
            hs[k] = pack(col(ax), row(ay), dirOf(col(ax) - col(nx), row(ay) - row(ny)))
        }
        hs.sort()
    }

    fun hasHead(c: Int, r: Int, d: Int) = c in 0 until w && r in 0 until h && heads.binarySearch(pack(c, r, d)) >= 0

    /** Every arrow's cells in board (screen-oriented) coordinates, head first. */
    val cells: List<List<Cell>> by lazy {
        level.verts.map { v ->
            val out = ArrayList<Cell>()
            var x = v[0] % w; var y = v[0] / w
            out.add(Cell(col(x), row(y)))
            for (k in 1 until v.size) {
                val tx = v[k] % w; val ty = v[k] / w
                val sx = Integer.signum(tx - x); val sy = Integer.signum(ty - y)
                while (x != tx || y != ty) { x += sx; y += sy; out.add(Cell(col(x), row(y))) }
            }
            out
        }
    }

    /** Arrow index per cell (-1 = empty). */
    val occ: IntArray by lazy {
        val o = IntArray(w * h) { -1 }
        cells.forEachIndexed { i, cs -> for (c in cs) o[c.r * w + c.c] = i }
        o
    }

    /** Orthogonal links between consecutive cells of one arrow: right of (c,r) and below (c,r). */
    private val links: Pair<BooleanArray, BooleanArray> by lazy {
        val hl = BooleanArray(w * h); val vl = BooleanArray(w * h)
        for (cs in cells) for (k in 1 until cs.size) {
            val a = cs[k - 1]; val b = cs[k]
            when {
                a.r == b.r && abs1(a.c - b.c) -> hl[a.r * w + minOf(a.c, b.c)] = true
                a.c == b.c && abs1(a.r - b.r) -> vl[minOf(a.r, b.r) * w + a.c] = true
            }
        }
        Pair(hl, vl)
    }

    fun occupied(c: Int, r: Int) = c in 0 until w && r in 0 until h && occ[r * w + c] >= 0
    fun hLinked(c: Int, r: Int) = c in 0 until w && r in 0 until h && links.first[r * w + c]
    fun vLinked(c: Int, r: Int) = c in 0 until w && r in 0 until h && links.second[r * w + c]

    fun toBoard(): Board {
        val arrows = cells.mapIndexed { i, cs ->
            val hd = cs[0]; val nx = cs[1]
            Arrow(i, cs.reversed(), Dir.values()[dirOf(hd.c - nx.c, hd.r - nx.r)])
        }
        val all = HashSet<Cell>(w * h * 2)
        for (c in 0 until w) for (r in 0 until h) all.add(Cell(c, r))
        return Board(arrows, 0, w - 1, 0, h - 1, all)
    }

    companion object {
        fun pack(c: Int, r: Int, d: Int) = (c shl 16) or (r shl 4) or d
        /** Dir ordinal: U, D, L, R. */
        fun dirOf(dx: Int, dy: Int) = when {
            dx == 0 && dy < 0 -> 0
            dx == 0 && dy > 0 -> 1
            dx < 0 -> 2
            else -> 3
        }
        private fun abs1(v: Int) = v == 1 || v == -1
    }
}

class LevelDb(val levels: List<Level>) {
    val variants: List<Variant> by lazy {
        levels.flatMap { l -> listOf(false, true).flatMap { mx -> listOf(false, true).map { my -> Variant(l, mx, my) } } }
    }

    /** [v] placed so that board cell (c,r) sits at survey/global cell (c+oc, r+or). */
    data class Match(val v: Variant, val oc: Int, val or: Int, val bad: Int, val total: Int, val gone: Set<Int>)

    /**
     * Find the shipped level the screen shows. Arrowheads seen on screen vote for (level, mirror, offset);
     * survivors are checked cell by cell (lines and links). Accept only a clear, unique winner.
     */
    fun match(sv: Survey): Pair<Match?, String> {
        val p = sv.pitch
        data class H(val c: Int, val r: Int, val d: Int, val q: Double)
        val seen = ArrayList<H>()
        for ((k, o) in sv.node) {
            if (!o.v) continue
            for (d in Dir.values()) {
                val pr = sv.head[Pair(k, d)] ?: continue
                if (pr.v[0] < 3 || pr.v[1] < 0.3 * p) continue
                val back = Cell(k.c - d.dx, k.r - d.dy)
                if (sv.node[back]?.v != true || !sv.linked(back, k)) continue
                seen.add(H(k.c, k.r, d.ordinal, pr.q))
            }
        }
        if (seen.size < 2) return Pair(null, "only ${seen.size} arrowheads visible")
        val anchors = seen.sortedByDescending { it.q }.take(4)
        val checks = seen.sortedByDescending { it.q }.take(24)
        val maxMiss = checks.size / 5
        val cands = HashSet<Triple<Int, Int, Int>>()
        for ((vi, v) in variants.withIndex()) {
            for (a in anchors) for (hk in v.heads) {
                if ((hk and 15) != a.d) continue
                val oc = a.c - (hk ushr 16); val or = a.r - ((hk ushr 4) and 0xFFF)
                var miss = 0
                for (ch in checks) if (!v.hasHead(ch.c - oc, ch.r - or, ch.d) && ++miss > maxMiss) break
                if (miss <= maxMiss) cands.add(Triple(vi, oc, or))
            }
        }
        if (cands.isEmpty()) return Pair(null, "no shipped level fits")
        val scored = cands.map { (vi, oc, or) -> score(variants[vi], oc, or, sv) }.sortedBy { it.bad }
        val best = scored.first()
        val second = scored.drop(1).firstOrNull { !samePlacement(it, best) }
        val okBest = best.bad <= max(3, (0.03 * best.total).toInt()) && best.total >= 150
        val okUnique = second == null || second.bad >= best.bad + max(15, (0.05 * best.total).toInt())
        val why = "best ${best.v.level.name} bad=${best.bad}/${best.total}" + (second?.let { ", next ${it.v.level.name} bad=${it.bad}" } ?: "")
        return if (okBest && okUnique) Pair(best, why) else Pair(null, "no clear match ($why)")
    }

    /**
     * Cell-by-cell agreement. An arrow whose every visible cell shows the "emptied" dot was already
     * cleared (level in progress): that is not a mismatch, it is reported in [Match.gone].
     */
    private fun score(v: Variant, oc: Int, or: Int, sv: Survey): Match {
        var bad = 0; var tot = 0
        val n = v.cells.size
        val seen = IntArray(n); val emptied = IntArray(n); val missing = IntArray(n)
        for ((k, o) in sv.node) {
            tot++
            val c = k.c - oc; val r = k.r - or
            val inside = c in 0 until v.w && r in 0 until v.h
            val a = if (inside) v.occ[r * v.w + c] else -1
            when {
                a >= 0 -> { seen[a]++; if (!o.v) { if (Cell(k.c, k.r) in sv.dot) emptied[a]++ else missing[a]++ } }
                o.v -> bad++
                !inside && Cell(k.c, k.r) in sv.dot -> bad++
            }
        }
        val gone = HashSet<Int>()
        for (a in 0 until n) {
            if (seen[a] >= 2 && emptied[a] == seen[a]) gone.add(a) else bad += emptied[a] + missing[a]
        }
        fun owner(c: Int, r: Int) = if (c in 0 until v.w && r in 0 until v.h) v.occ[r * v.w + c] else -1
        for ((k, o) in sv.hEdge) if (o.v >= 0) {
            val c = k.c - oc; val r = k.r - or
            if (owner(c, r) in gone) continue
            tot++; if (v.hLinked(c, r) != (o.v == 1)) bad++
        }
        for ((k, o) in sv.vEdge) if (o.v >= 0) {
            val c = k.c - oc; val r = k.r - or
            if (owner(c, r) in gone) continue
            tot++; if (v.vLinked(c, r) != (o.v == 1)) bad++
        }
        return Match(v, oc, or, bad, tot, gone)
    }

    /** Symmetric or duplicated levels can match in several ways that put identical arrows on screen. */
    private fun samePlacement(a: Match, b: Match): Boolean {
        if (a.v.heads.size != b.v.heads.size) return false
        for (hk in a.v.heads) {
            val c = (hk ushr 16) + a.oc - b.oc; val r = ((hk ushr 4) and 0xFFF) + a.or - b.or
            if (!b.v.hasHead(c, r, hk and 15)) return false
        }
        return true
    }

    companion object {
        fun load(inp: InputStream): LevelDb {
            DataInputStream(inp.buffered(1 shl 16)).use { d ->
                val magic = ByteArray(4); d.readFully(magic)
                require(String(magic) == "ARW1") { "bad levels.bin" }
                val n = d.readInt()
                val out = ArrayList<Level>(n)
                repeat(n) {
                    val mode = d.readUnsignedByte()
                    val nb = ByteArray(d.readUnsignedShort()); d.readFully(nb)
                    val w = d.readUnsignedByte(); val h = d.readUnsignedByte()
                    val flags = d.readInt(); val special = d.readInt()
                    val na = d.readUnsignedShort()
                    val verts = Array(na) { IntArray(d.readUnsignedByte()) { d.readUnsignedShort() } }
                    out.add(Level(mode, String(nb, Charsets.UTF_8), w, h, flags, special, verts))
                }
                return LevelDb(out)
            }
        }
    }
}
