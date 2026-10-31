package com.alexdremov.notate.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class InkArbiterTest {
    private val calls = ArrayList<String>()

    private inner class FakeSurface(
        val name: String,
        override val closesWhenNotOwner: Boolean,
    ) : InkSurface {
        var suspended = false
        var deactivated = 0

        override val driver =
            object : DriverPort {
                override fun open() {
                    calls += "$name.open"
                }

                override fun close() {
                    calls += "$name.close"
                }

                override fun setActive(on: Boolean) {
                    calls += "$name.${if (on) "on" else "off"}"
                }
            }

        override fun onInkSuspended(suspended: Boolean) {
            this.suspended = suspended
        }

        override fun onDriverDeactivated() {
            deactivated++
        }
    }

    private val arbiter = InkArbiter()
    private val note = FakeSurface("note", closesWhenNotOwner = true)
    private val book = FakeSurface("book", closesWhenNotOwner = false)

    @Test
    fun `the first surface owns the pen and its driver turns on`() {
        arbiter.attach(note)
        assertSame(note, arbiter.owner)
        assertEquals(listOf("note.open", "note.on"), calls)
    }

    @Test
    fun `switching owner closes the old session before opening the new one`() {
        arbiter.attach(note)
        arbiter.attach(book, alreadyOpen = true)
        calls.clear()
        arbiter.setOwner(book)
        assertEquals(listOf("note.off", "note.close", "book.on"), calls)
        calls.clear()
        arbiter.setOwner(note)
        assertEquals(listOf("book.off", "note.open", "note.on"), calls)
    }

    @Test
    fun `only the owner is ever active`() {
        arbiter.attach(note)
        arbiter.attach(book, alreadyOpen = true)
        arbiter.setOwner(book)
        assertTrue(arbiter.isDriverActive(book))
        assertFalse(arbiter.isDriverActive(note))
    }

    @Test
    fun `any reason keeps the driver off and the last one lifted turns it on again`() {
        arbiter.attach(note)
        calls.clear()
        arbiter.block(BlockReason.TOOL_POPUP, true)
        arbiter.block(BlockReason.SIDEBAR, true)
        assertEquals(listOf("note.off"), calls)
        arbiter.block(BlockReason.TOOL_POPUP, false)
        assertFalse(arbiter.isDriverActive(note))
        arbiter.block(BlockReason.SIDEBAR, false)
        assertEquals(listOf("note.off", "note.on"), calls)
    }

    @Test
    fun `per surface reasons only affect that surface`() {
        arbiter.attach(note)
        arbiter.attach(book, alreadyOpen = true)
        arbiter.block(book, BlockReason.FINGER_GESTURE, true)
        assertTrue(arbiter.isDriverActive(note))
        arbiter.setOwner(book)
        assertFalse(arbiter.isDriverActive(book))
        arbiter.block(book, BlockReason.FINGER_GESTURE, false)
        assertTrue(arbiter.isDriverActive(book))
    }

    @Test
    fun `hard reasons suspend ink, soft ones do not`() {
        arbiter.attach(book, alreadyOpen = true)
        arbiter.block(book, BlockReason.FINGER_GESTURE, true)
        assertFalse(book.suspended)
        arbiter.block(BlockReason.TOOL_POPUP, true)
        assertTrue(book.suspended)
        assertFalse(arbiter.isWindowInkAllowed)
        arbiter.block(BlockReason.TOOL_POPUP, false)
        assertFalse(book.suspended)
        assertTrue(arbiter.isWindowInkAllowed)
    }

    @Test
    fun `turning the driver off while it inks reports a cut stroke`() {
        arbiter.attach(note)
        arbiter.block(BlockReason.SIDEBAR, true)
        assertEquals(1, note.deactivated)
        arbiter.block(BlockReason.SIDEBAR, true) // no change
        assertEquals(1, note.deactivated)
    }

    @Test
    fun `a refresh runs with the driver off and puts it back`() {
        arbiter.attach(note)
        calls.clear()
        arbiter.pausedDuring(note) { calls += "invalidate" }
        assertEquals(listOf("note.off", "invalidate", "note.on"), calls)
    }

    @Test
    fun `a refresh while blocked leaves the driver off`() {
        arbiter.attach(note)
        arbiter.block(BlockReason.TOOL_POPUP, true)
        calls.clear()
        arbiter.pausedDuring(note) { calls += "invalidate" }
        assertEquals(listOf("invalidate"), calls)
        assertFalse(arbiter.isDriverActive(note))
    }

    @Test
    fun `a recreated surface reopens the owner's session and writes its state again`() {
        arbiter.attach(note)
        arbiter.block(BlockReason.SIDEBAR, true)
        calls.clear()
        arbiter.closeSession(note)
        assertEquals(listOf("note.close"), calls)
        calls.clear()
        arbiter.reopen(note)
        assertEquals(listOf("note.open", "note.off"), calls)
    }

    @Test
    fun `a non owner does not reopen`() {
        arbiter.attach(note)
        arbiter.attach(book, alreadyOpen = true)
        calls.clear()
        arbiter.reopen(book)
        assertEquals(listOf<String>(), calls.filter { it.startsWith("book.open") })
    }

    @Test
    fun `restart toggles an active driver only`() {
        arbiter.attach(note)
        calls.clear()
        arbiter.restart(note)
        assertEquals(listOf("note.off", "note.on"), calls)
        arbiter.block(BlockReason.SIDEBAR, true)
        calls.clear()
        arbiter.restart(note)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `detaching the owner shuts its driver down and leaves nobody owning`() {
        arbiter.attach(book, alreadyOpen = true)
        calls.clear()
        arbiter.detach(book)
        assertEquals(listOf("book.off", "book.close"), calls)
        assertNull(arbiter.owner)
    }

    @Test
    fun `a blocked owner is not inking after it changes`() {
        arbiter.attach(note)
        arbiter.attach(book, alreadyOpen = true)
        arbiter.block(BlockReason.WINDOW_UNFOCUSED, true)
        calls.clear()
        arbiter.setOwner(book)
        assertFalse(arbiter.isDriverActive(book))
        assertFalse(calls.contains("book.on"))
    }

    @Test
    fun `window ink listener fires only when hard state flips`() {
        arbiter.attach(note)
        val seen = ArrayList<Boolean>()
        arbiter.onWindowInkAllowedChanged = { seen += it }
        arbiter.block(BlockReason.SIDEBAR, true)
        arbiter.block(BlockReason.TOOL_POPUP, true)
        arbiter.block(BlockReason.TOOL_POPUP, false)
        arbiter.block(BlockReason.SIDEBAR, false)
        assertEquals(listOf(false, true), seen)
    }
}
