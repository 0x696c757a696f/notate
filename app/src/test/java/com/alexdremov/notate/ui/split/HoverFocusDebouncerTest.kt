package com.alexdremov.notate.ui.split

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HoverFocusDebouncerTest {
    private val d = HoverFocusDebouncer(delayMs = 80L)

    @Test
    fun `focus moves only after the pen stayed 80 ms over the other pane`() {
        assertNull(d.onHover(pane = 1, active = 0, now = 1_000L))
        assertNull(d.onHover(pane = 1, active = 0, now = 1_079L))
        assertEquals(1, d.onHover(pane = 1, active = 0, now = 1_081L))
    }

    @Test
    fun `the focused pane never asks for focus`() {
        assertNull(d.onHover(pane = 0, active = 0, now = 0L))
        assertNull(d.onHover(pane = 0, active = 0, now = 500L))
    }

    @Test
    fun `leaving and coming back restarts the clock`() {
        assertNull(d.onHover(1, 0, 1_000L))
        assertNull(d.onHover(0, 0, 1_050L)) // back over the focused pane
        assertNull(d.onHover(1, 0, 1_060L))
        assertNull(d.onHover(1, 0, 1_130L)) // only 70 ms since coming back
        assertEquals(1, d.onHover(1, 0, 1_141L))
    }

    @Test
    fun `passing over the divider does not count`() {
        assertNull(d.onHover(1, 0, 1_000L))
        assertNull(d.onHover(-1, 0, 1_060L))
        assertNull(d.onHover(1, 0, 1_100L))
        assertNull(d.onHover(1, 0, 1_150L))
    }

    @Test
    fun `remaining tells how long to wait`() {
        assertEquals(-1L, d.remaining(0L))
        d.onHover(1, 0, 1_000L)
        assertEquals(50L, d.remaining(1_030L))
        assertEquals(0L, d.remaining(2_000L))
        d.reset()
        assertEquals(-1L, d.remaining(2_000L))
    }
}
