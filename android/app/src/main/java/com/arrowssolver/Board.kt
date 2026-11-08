package com.arrowssolver

import kotlin.math.hypot

enum class Dir(val dx: Int, val dy: Int) { U(0, -1), D(0, 1), L(-1, 0), R(1, 0) }

/** A lattice point of the board (column, row) in board coordinates. */
data class Cell(val c: Int, val r: Int) {
    operator fun plus(d: Dir) = Cell(c + d.dx, r + d.dy)
    fun dist(o: Cell) = hypot((c - o.c).toDouble(), (r - o.r).toDouble())
}

/** One arrow: cells from tail to head; [dir] is where the head points. */
class Arrow(val id: Int, val cells: List<Cell>, val dir: Dir) {
    val head: Cell get() = cells.last()
}

/**
 * Game rule (verified on device): tapping an arrow makes it slide out head-first, its body following
 * its own path like a snake. It leaves only if every cell on the straight ray from its head to the
 * board edge is empty; otherwise a life is lost.
 */
class Board(
    val arrows: List<Arrow>,
    val minC: Int, val maxC: Int, val minR: Int, val maxR: Int,
    /** Lattice cells we actually saw on screen; an unseen cell on a ray counts as blocked. */
    val observed: Set<Cell>,
) {
    fun inBounds(x: Cell) = x.c in minC..maxC && x.r in minR..maxR

    fun occupancy(removed: Set<Int>): HashMap<Cell, Int> {
        val m = HashMap<Cell, Int>()
        for (a in arrows) if (a.id !in removed) for (x in a.cells) m[x] = a.id
        return m
    }

    fun ray(a: Arrow): List<Cell> {
        val out = ArrayList<Cell>()
        var x = a.head + a.dir
        while (inBounds(x)) { out.add(x); x += a.dir }
        return out
    }

    fun isFree(a: Arrow, occ: Map<Cell, Int>): Boolean {
        for (x in ray(a)) {
            val o = occ[x]
            when {
                o == null -> if (x !in observed) return false
                o != a.id -> return false
                // its own body never blocks: ArrowGrid.Collides only looks for other arrows
            }
        }
        return true
    }

    fun free(removed: Set<Int>): List<Arrow> {
        val occ = occupancy(removed)
        return arrows.filter { it.id !in removed && isFree(it, occ) }
    }

    /** Greedy removal layers (removing an arrow never blocks another); null if the board can't be cleared. */
    fun plan(removed: Set<Int> = emptySet()): List<List<Int>>? {
        val gone = removed.toMutableSet()
        val layers = ArrayList<List<Int>>()
        while (true) {
            val f = free(gone).map { it.id }
            if (f.isEmpty()) break
            layers.add(f); gone.addAll(f)
        }
        return if (gone.size == arrows.size) layers else null
    }
}
