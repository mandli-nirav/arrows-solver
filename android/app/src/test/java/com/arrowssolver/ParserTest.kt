package com.arrowssolver

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ParserTest {
    private val w = 1080
    private val h = 2400
    private val lat = Lattice(20.0, 4.0, 40.0, 40.0)

    private fun fill(px: IntArray, x0: Int, y0: Int, x1: Int, y1: Int, c: Int) { for (y in y0 until y1) for (x in x0 until x1) px[y * w + x] = c }

    @Test fun anArrowheadsFaintEdgesDoNotHideItsNode() {
        val px = IntArray(w * h) { 0xFF1E202D.toInt() }
        fill(px, lat.px(5), lat.py(15) - 3, lat.px(8) + 1, lat.py(15) + 4, 0xFFAAB6F3.toInt())
        // a dozen anti-aliased edge pixels, blends of line and background that match the button colour
        fill(px, lat.px(8) + 6, lat.py(15) - 9, lat.px(8) + 9, lat.py(15) - 5, 0xFF44485F.toInt())
        val v = Parser.parse(Shot(w, h, px, 0xAAB6F3), lat, emptyList())
        assertNotNull(v.node[Cell(8, 15)])
    }

    @Test fun aButtonHidesTheNodesUnderIt() {
        val px = IntArray(w * h) { 0xFF1E202D.toInt() }
        fill(px, lat.px(12) - 50, lat.py(30) - 50, lat.px(12) + 50, lat.py(30) + 50, 0xFF424768.toInt())
        val v = Parser.parse(Shot(w, h, px, 0xAAB6F3), lat, emptyList())
        assertNull(v.node[Cell(12, 30)])
    }
}
