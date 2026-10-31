package com.alexdremov.notate.webdoc

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Each book pane of a split has its own Write / Interact; only the focused one can write. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class BookPanePenModeTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun meta(id: String) = WebDocMeta(id, id, WebDocKind.HTML, listOf(WebDocPage("index.html", id)))

    private fun lib(): WebDocLibrary = WebDocLibrary(tmp.newFolder("lib"))

    @Test
    fun `unfocused pane never writes`() {
        val pane = BookPane(context, meta("a"), lib())
        assertEquals(PenMode.WRITE, pane.penMode)
        assertFalse(pane.writeEnabled)
        pane.release()
    }

    @Test
    fun `focus plus write enables, interact disables`() {
        val pane = BookPane(context, meta("a"), lib())
        pane.setPaneActive(true)
        assertTrue(pane.writeEnabled)
        pane.setPenMode(PenMode.INTERACT)
        assertFalse(pane.writeEnabled)
        pane.togglePenMode()
        assertTrue(pane.writeEnabled)
        pane.release()
    }

    @Test
    fun `focus changes keep each pane's own mode`() {
        val library = lib()
        val left = BookPane(context, meta("a"), library)
        val right = BookPane(context, meta("b"), library)
        left.setPaneActive(true)
        right.setPenMode(PenMode.INTERACT)
        left.setPaneActive(false)
        right.setPaneActive(true)
        assertEquals(PenMode.WRITE, left.penMode)
        assertEquals(PenMode.INTERACT, right.penMode)
        assertFalse(right.writeEnabled)
        left.setPaneActive(true)
        right.setPaneActive(false)
        assertTrue(left.writeEnabled)
        assertEquals(PenMode.INTERACT, right.penMode)
        left.release()
        right.release()
    }

    @Test
    fun `mode survives rebuilding the pane`() {
        val library = lib()
        val first = BookPane(context, meta("a"), library)
        first.setPenMode(PenMode.INTERACT)
        first.release()
        val second = BookPane(context, meta("a"), library)
        assertEquals(PenMode.INTERACT, second.penMode)
        second.setPaneActive(true)
        assertFalse(second.writeEnabled)
        second.release()
    }

    @Test
    fun `pen mode round-trips through the store`() {
        val library = lib()
        val store = WebAnnotationStore.acquire(library, "doc")
        store.penMode = PenMode.INTERACT
        WebAnnotationStore.release(store)
        assertEquals(PenMode.INTERACT.name, library.loadAnnotations("doc").penMode)
        assertEquals(PenMode.WRITE, PenMode.parse("garbage"))
    }
}
