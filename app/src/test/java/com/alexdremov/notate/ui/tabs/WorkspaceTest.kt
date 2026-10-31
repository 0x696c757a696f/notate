package com.alexdremov.notate.ui.tabs

import com.alexdremov.notate.ui.split.SplitDrag
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkspaceTest {
    private fun note(path: String) = DocTab(DocKind.NOTE, path, path.uppercase())

    @Test
    fun `open inserts next to the active tab and activates it`() {
        val ws =
            Workspace()
                .open(note("a"))
                .open(note("b"))
                .activate("a")
                .open(note("c"))
        assertEquals(listOf("a", "c", "b"), ws.tabs.map { it.path })
        assertEquals("c", ws.activePath)
    }

    @Test
    fun `reopening a tab keeps its place and refreshes the title`() {
        val ws = Workspace().open(note("a")).open(note("b")).open(DocTab(DocKind.NOTE, "a", "Renamed"))
        assertEquals(listOf("a", "b"), ws.tabs.map { it.path })
        assertEquals("Renamed", ws.tab("a")?.title)
        assertEquals("a", ws.activePath)
    }

    @Test
    fun `closing the active tab activates its right neighbour, else the left one`() {
        val ws = Workspace().open(note("a")).open(note("b")).open(note("c")).activate("b")
        assertEquals("c", ws.close("b").activePath)
        assertEquals("b", ws.activate("c").close("c").activePath)
        assertNull(Workspace().open(note("a")).close("a").activePath)
    }

    @Test
    fun `closing an inactive tab keeps the active one`() {
        val ws = Workspace().open(note("a")).open(note("b")).close("a")
        assertEquals("b", ws.activePath)
    }

    @Test
    fun `a document is in at most one split and closing it ends the split`() {
        var ws = Workspace().open(note("a")).open(note("b")).open(note("c"))
        ws = ws.split("a", "b", SplitOrientation.SIDE_BY_SIDE)
        assertEquals("b", ws.splitFor("a")?.partnerOf("a"))
        assertEquals("a", ws.splitFor("b")?.partnerOf("b"))

        ws = ws.split("c", "b", SplitOrientation.STACKED)
        assertNull(ws.splitFor("a"))
        assertEquals(SplitOrientation.STACKED, ws.splitFor("b")?.orientation)

        ws = ws.close("c")
        assertNull(ws.splitFor("b"))
    }

    @Test
    fun `cannot split a document with itself`() {
        val ws = Workspace().open(note("a")).split("a", "a", SplitOrientation.SIDE_BY_SIDE)
        assertNull(ws.splitFor("a"))
    }

    @Test
    fun `updateSplit changes only the split holding the path`() {
        val ws =
            Workspace()
                .split("a", "b", SplitOrientation.SIDE_BY_SIDE)
                .split("c", "d", SplitOrientation.SIDE_BY_SIDE)
                .updateSplit("b") { it.copy(ratio = 0.3f) }
        assertEquals(0.3f, ws.splitFor("a")!!.ratio, 0f)
        assertEquals(0.5f, ws.splitFor("c")!!.ratio, 0f)
    }

    @Test
    fun `workspace survives a JSON round trip`() {
        val ws = Workspace().open(note("a")).open(note("b")).split("a", "b", SplitOrientation.STACKED)
        val json = Json { ignoreUnknownKeys = true }
        assertEquals(ws, json.decodeFromString(Workspace.serializer(), json.encodeToString(Workspace.serializer(), ws)))
    }

    @Test
    fun `divider snaps near thirds and halves, closes a pane at the edges`() {
        assertEquals(SplitDrag.Outcome.Resize(0.5f), SplitDrag.resolve(0.52f))
        assertEquals(SplitDrag.Outcome.Resize(1f / 3f), SplitDrag.resolve(0.36f))
        assertEquals(SplitDrag.Outcome.Resize(0.42f), SplitDrag.resolve(0.42f))
        assertEquals(SplitDrag.Outcome.Resize(0.15f), SplitDrag.resolve(0.12f))
        assertEquals(SplitDrag.Outcome.Collapse(keepIndex = 1), SplitDrag.resolve(0.05f))
        assertEquals(SplitDrag.Outcome.Collapse(keepIndex = 0), SplitDrag.resolve(0.95f))
    }
}

class WorkspaceBookSplitTest {
    private fun note(p: String) = DocTab(DocKind.NOTE, p, p)

    private fun book(p: String) = DocTab(DocKind.WEB, p, p)

    @Test
    fun `book and note split survives swap and ratio update`() {
        var ws = Workspace().open(note("n")).open(book("b")).split("b", "n", SplitOrientation.STACKED)
        ws = ws.updateSplit("b") { it.copy(first = it.second, second = it.first, ratio = 1f - it.ratio) }
        val s = ws.splitFor("b")!!
        assertEquals("n", s.first)
        assertEquals("b", s.second)
        assertEquals(SplitOrientation.STACKED, s.orientation)
        ws = ws.updateSplit("n") { it.copy(ratio = 0.3f) }
        assertEquals(0.3f, ws.splitFor("b")!!.ratio, 0f)
    }

    @Test
    fun `closing the partner removes the split`() {
        val ws = Workspace().open(note("n")).open(book("b")).split("b", "n", SplitOrientation.SIDE_BY_SIDE).close("n")
        assertNull(ws.splitFor("b"))
    }

    @Test
    fun `a new split replaces an older split of either path`() {
        val ws =
            Workspace()
                .open(note("n"))
                .open(book("a"))
                .open(book("b"))
                .split("a", "n", SplitOrientation.SIDE_BY_SIDE)
                .split("b", "n", SplitOrientation.SIDE_BY_SIDE)
        assertNull(ws.splitFor("a"))
        assertEquals("n", ws.splitFor("b")?.partnerOf("b"))
    }
}
