package com.arrowssolver

/** Finds the system's floating accessibility button by comparing a game-window screenshot with a full-display one. */
object A11yButton {
    private const val BIN = 24

    /**
     * [game] and [full] are ARGB pixels of the same [w] x [h] screen. The button is the patch where they differ that
     * touches the left or right edge (it docks there) and is small; anything else that differs (a moving arrow) is
     * not it. Returns the union of such patches as [left, top, right, bottom], or null.
     */
    fun find(game: IntArray, full: IntArray, w: Int, h: Int): IntArray? {
        val bw = (w + BIN - 1) / BIN; val bh = (h + BIN - 1) / BIN
        val on = BooleanArray(bw * bh)
        for (y in h / 12 until h step 4) for (x in 0 until w step 4) { // top strip: status bar, heads-up notifications
            val a = game[y * w + x]; val b = full[y * w + x]
            val d = Math.abs((a shr 16 and 255) - (b shr 16 and 255)) + Math.abs((a shr 8 and 255) - (b shr 8 and 255)) + Math.abs((a and 255) - (b and 255))
            if (d > 90) on[(y / BIN) * bw + x / BIN] = true
        }
        var out: IntArray? = null
        val seen = BooleanArray(on.size)
        for (start in on.indices) {
            if (!on[start] || seen[start]) continue
            var l = bw; var t = bh; var r = -1; var b = -1
            val stack = ArrayDeque(listOf(start)); seen[start] = true
            while (stack.isNotEmpty()) {
                val i = stack.removeLast(); val x = i % bw; val y = i / bw
                l = minOf(l, x); r = maxOf(r, x); t = minOf(t, y); b = maxOf(b, y)
                for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                    val nx = x + dx; val ny = y + dy
                    if (nx !in 0 until bw || ny !in 0 until bh) continue
                    val j = ny * bw + nx
                    if (on[j] && !seen[j]) { seen[j] = true; stack.add(j) }
                }
            }
            val x0 = l * BIN; val y0 = t * BIN; val x1 = minOf(w, (r + 1) * BIN); val y1 = minOf(h, (b + 1) * BIN)
            val atEdge = x0 <= 2 * BIN || x1 >= w - 2 * BIN
            if (atEdge && x1 - x0 < w / 3 && y1 - y0 < h / 6) {
                val o = out
                out = if (o == null) intArrayOf(x0, y0, x1, y1) else intArrayOf(minOf(o[0], x0), minOf(o[1], y0), maxOf(o[2], x1), maxOf(o[3], y1))
            }
        }
        return out
    }
}
