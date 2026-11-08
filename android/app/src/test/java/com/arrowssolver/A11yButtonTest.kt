package com.arrowssolver

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class A11yButtonTest {
    private val w = 400
    private val h = 800
    private fun screen() = IntArray(w * h) { 0xFF1E1F2D.toInt() }
    private fun fill(px: IntArray, x0: Int, y0: Int, x1: Int, y1: Int, c: Int) { for (y in y0 until y1) for (x in x0 until x1) px[y * w + x] = c }

    @Test fun findsButtonDockedAtEdge() {
        val game = screen(); val full = screen()
        fill(full, w - 60, 500, w, 560, 0xFF606060.toInt()) // the floating button, docked right
        fill(full, 150, 300, 200, 340, 0xFFFFFFFF.toInt()) // something else that differs mid-screen (a moving arrow)
        assertArrayEquals(intArrayOf(336, 480, 400, 576), A11yButton.find(game, full, w, h))
    }

    @Test fun noButtonNoRect() {
        assertNull(A11yButton.find(screen(), screen(), w, h))
    }
}
