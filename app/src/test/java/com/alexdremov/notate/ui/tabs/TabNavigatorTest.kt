package com.alexdremov.notate.ui.tabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TabNavigatorTest {
    private fun note(p: String) = DocTab(DocKind.NOTE, p, p)

    private fun book(p: String) = DocTab(DocKind.WEB, p, p)

    private val side = SplitOrientation.SIDE_BY_SIDE

    @Test
    fun `unsplit tab hosts itself`() {
        val ws = Workspace().open(note("n")).open(book("b"))
        assertEquals(book("b"), TabNavigator.hostFor(ws, book("b")))
        assertEquals(note("n"), TabNavigator.hostFor(ws, note("n")))
    }

    @Test
    fun `book split with a note is hosted by the note`() {
        val ws = Workspace().open(note("n")).open(book("b")).split("b", "n", side)
        assertEquals(note("n"), TabNavigator.hostFor(ws, book("b")))
    }

    @Test
    fun `note split with a book stays on the note`() {
        val ws = Workspace().open(note("n")).open(book("b")).split("n", "b", side)
        assertEquals(note("n"), TabNavigator.hostFor(ws, note("n")))
    }

    @Test
    fun `book split with a book is hosted by the opened book`() {
        val ws = Workspace().open(book("a")).open(book("b")).split("a", "b", side)
        assertEquals(book("a"), TabNavigator.hostFor(ws, book("a")))
        assertEquals(book("b"), TabNavigator.hostFor(ws, book("b")))
    }

    @Test
    fun `missing partner tab falls back to the tab itself`() {
        val ws = Workspace().open(book("b")).split("b", "gone", side)
        assertEquals(book("b"), TabNavigator.hostFor(ws, book("b")))
    }

    @Test
    fun `re-splitting keeps each document in at most one split`() {
        val ws =
            Workspace()
                .open(note("n"))
                .open(book("a"))
                .open(book("b"))
                .split("a", "n", side)
                .split("a", "b", SplitOrientation.STACKED)
        assertNull(ws.splitFor("n"))
        assertEquals("b", ws.splitFor("a")?.partnerOf("a"))
        assertEquals(1, ws.splits.size)
        // After re-splitting, the book no longer redirects to the note.
        assertEquals(book("a"), TabNavigator.hostFor(ws, book("a")))
    }
}
