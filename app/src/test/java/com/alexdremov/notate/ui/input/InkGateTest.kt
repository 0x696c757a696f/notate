package com.alexdremov.notate.ui.input

import com.alexdremov.notate.ui.input.InkGate.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InkGateTest {
    private val updates = mutableListOf<Boolean>()
    private val gate = InkGate { updates.add(it) }

    @Test
    fun `ink is allowed when nothing blocks it`() {
        assertTrue(gate.isInkAllowed)
    }

    @Test
    fun `lifting the stylus off the toolbar keeps ink off while the popup it opened is shown`() {
        gate.set(Reason.TOOLBAR_TOUCH, true)
        // The tap opens pen settings, which takes window focus...
        gate.set(Reason.TOOL_POPUP, true)
        gate.set(Reason.WINDOW_UNFOCUSED, true)
        // ...and only then does the toolbar see the stylus go up.
        gate.set(Reason.TOOLBAR_TOUCH, false)

        assertFalse(gate.isInkAllowed)
        assertEquals(false, updates.last())

        gate.set(Reason.TOOL_POPUP, false)
        gate.set(Reason.WINDOW_UNFOCUSED, false)
        assertTrue(gate.isInkAllowed)
        assertEquals(true, updates.last())
    }

    @Test
    fun `any unfocused window blocks ink on its own`() {
        gate.set(Reason.WINDOW_UNFOCUSED, true)
        assertFalse(gate.isInkAllowed)
        gate.set(Reason.WINDOW_UNFOCUSED, false)
        assertTrue(gate.isInkAllowed)
    }

    @Test
    fun `every update reports the current state so outside writes get corrected`() {
        gate.set(Reason.SIDEBAR, true)
        gate.set(Reason.TOOLBAR_EDIT_MODE, false)
        assertEquals(listOf(false, false), updates)
    }

    @Test
    fun `clearing a reason that was never set changes nothing`() {
        gate.set(Reason.PAGE_GRID, false)
        assertTrue(gate.isInkAllowed)
        assertTrue(gate.isBlockedBy(Reason.PAGE_GRID).not())
    }
}
