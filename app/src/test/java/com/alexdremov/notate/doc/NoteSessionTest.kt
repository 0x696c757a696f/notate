package com.alexdremov.notate.doc

import com.alexdremov.notate.doc.note.NoteSession
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NoteSessionTest {
    private var saves = 0
    private var failNext = false
    private var undone = false

    private fun session() =
        NoteSession(
            path = "/notes/a.notate",
            title = "a",
            canvas = mockk(relaxed = true),
            save = {
                if (failNext) {
                    failNext = false
                    throw java.io.IOException("disk full")
                }
                saves++
            },
            undoOp = { true.also { undone = true } },
            redoOp = { false },
            canUndoNow = { true },
            canRedoNow = { false },
        )

    @Test
    fun `a fresh session is clean and flushing it writes nothing`() =
        runTest {
            val s = session()
            assertEquals(SaveState.Clean, s.saveState.value)
            s.flush()
            assertEquals(0, saves)
        }

    @Test
    fun `an edit makes it dirty and flush saves once and goes clean`() =
        runTest {
            val s = session()
            s.onEdited()
            assertEquals(SaveState.Dirty, s.saveState.value)
            s.flush()
            s.flush()
            assertEquals(1, saves)
            assertEquals(SaveState.Clean, s.saveState.value)
        }

    @Test
    fun `a failed save reports the error and the next flush retries`() =
        runTest {
            val s = session()
            s.onEdited()
            failNext = true
            try {
                s.flush()
                fail("expected the save error to surface")
            } catch (e: java.io.IOException) {
                // expected
            }
            assertTrue(s.saveState.value is SaveState.Error)
            s.flush()
            assertEquals(1, saves)
            assertEquals(SaveState.Clean, s.saveState.value)
        }

    @Test
    fun `undo counts as an edit`() =
        runTest {
            val s = session()
            assertTrue(s.undo.undo())
            assertTrue(undone)
            assertEquals(SaveState.Dirty, s.saveState.value)
            assertEquals(1, s.undo.changes.value)
        }

    @Test
    fun `notes have every capability`() {
        assertEquals(Capability.entries.toSet(), session().capabilities)
    }
}
