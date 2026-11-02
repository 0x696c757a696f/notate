package app.notate.core.doc.editor

import app.notate.core.doc.edit.IdAllocator
import app.notate.core.doc.save.SaveQueue
import app.notate.core.model.ItemId
import app.notate.core.model.PageId
import app.notate.core.model.edit.Edit
import app.notate.core.store.MaxIds
import app.notate.core.testing.FakeClock
import app.notate.core.testing.FakeDocStore
import app.notate.core.testing.meta
import app.notate.core.testing.page
import app.notate.core.testing.rect
import app.notate.core.testing.stroke
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DocumentEditorTest {
    private val p1 = PageId(1)
    private class Rig(val store: FakeDocStore, val save: SaveQueue, val editor: DocumentEditor)

    private fun TestScope.rig(pages: List<app.notate.core.model.PageInfo> = listOf(page(1), page(2)), seed: Boolean = false): Rig {
        val d = StandardTestDispatcher(testScheduler)
        val store = FakeDocStore(meta(), pages)
        val save = SaveQueue(store, d, FakeClock())
        val editor = DocumentEditor(meta(), pages, store, save, IdAllocator(MaxIds(2, 0, emptyMap())), FakeClock(), d, d)
        return Rig(store, save, editor)
    }

    private suspend fun Rig.load(page: PageId = p1) = editor.ensureLoaded(page)

    @Test fun applyUpdatesContentChangesHistoryAndSave() = runTest {
        val r = rig(); val changes = ArrayList<ContentChange>()
        backgroundScope.launch { r.editor.changes.collect { changes.add(it) } }
        runCurrent()
        val d = async { r.load() }; runCurrent(); d.await()
        val s = stroke(1)
        r.editor.apply(Edit.Items(p1, emptyList(), listOf(s)))
        runCurrent()
        assertThat(r.editor.content(p1)!![ItemId(1)]).isEqualTo(s)
        assertThat(r.editor.content(p1)!!.rev).isEqualTo(1)
        assertThat(changes.single().page).isEqualTo(p1)
        assertThat(changes.single().dirty).isEqualTo(s.bounds)
        assertThat(r.editor.history.canUndo.value).isTrue()
        advanceTimeBy(201); runCurrent()
        assertThat(r.store.writes.single()).hasSize(1)
        assertThat(r.save.persistedRev.value).isEqualTo(1)
        r.save.close()
    }

    @Test fun undoRestoresContentAndSavesInverse() = runTest {
        val r = rig()
        val d = async { r.load() }; runCurrent(); d.await()
        val s = stroke(1)
        val add = Edit.Items(p1, emptyList(), listOf(s))
        r.editor.apply(add)
        assertThat(r.editor.undo()).isTrue()
        assertThat(r.editor.content(p1)!!.all().toList()).isEmpty()
        advanceTimeBy(201); runCurrent()
        assertThat(r.store.writes.single()).containsExactly(add, add.inverse()).inOrder()
        assertThat(r.editor.redo()).isTrue()
        assertThat(r.editor.content(p1)!![ItemId(1)]).isEqualTo(s)
        assertThat(r.editor.undo()).isTrue()
        assertThat(r.editor.undo()).isFalse()
        r.save.close()
    }

    @Test fun applyToUnloadedPageThrows() = runTest {
        val r = rig()
        assertThrows(IllegalStateException::class.java) {
            r.editor.apply(Edit.Items(p1, emptyList(), listOf(stroke(1))))
        }
        assertThat(r.editor.history.canUndo.value).isFalse()
        r.save.close()
    }

    @Test fun ensureLoadedMergesWithMemoryWinning() = runTest {
        val r = rig()
        val stored = stroke(1, points = floatArrayOf(5f, 5f, 15f, 15f))
        r.store.write(listOf(Edit.Items(p1, emptyList(), listOf(stored))))
        r.store.writes.clear()
        val a = async { r.editor.ensureLoaded(p1) }
        val b = async { r.editor.ensureLoaded(p1) }
        runCurrent()
        assertThat(a.await()).isSameInstanceAs(b.await())
        assertThat(r.editor.content(p1)!![ItemId(1)]).isEqualTo(stored)
    }

    @Test fun editDuringLoadBeatsStoredCopy() = runTest {
        val r = rig()
        val stored = stroke(1, points = floatArrayOf(5f, 5f, 15f, 15f))
        r.store.write(listOf(Edit.Items(p1, emptyList(), listOf(stored))))
        val first = async { r.editor.ensureLoaded(p1) }; runCurrent(); first.await()
        // Whiteboard-style situation: erase locally (not yet persisted), then a second load of the same page must not resurrect it.
        r.editor.apply(Edit.Items(p1, listOf(stored), emptyList()))
        val again = async { r.editor.ensureLoaded(p1) }; runCurrent(); again.await()
        assertThat(r.editor.content(p1)!!.all().toList()).isEmpty()
        r.save.close()
    }

    @Test fun whiteboardRegionLoadsExtendExactly() = runTest {
        val wb = listOf(page(1, height = null))
        val r = rig(wb)
        val near = stroke(1, points = floatArrayOf(10f, 10f, 20f, 20f))
        val far = stroke(2, points = floatArrayOf(3000f, 10f, 3010f, 20f))
        r.store.write(listOf(Edit.Items(p1, emptyList(), listOf(near, far))))
        val a = async { r.editor.ensureLoaded(p1, rect(0f, 0f, 512f, 512f)) }; runCurrent()
        assertThat(a.await().all().toList()).containsExactly(near)
        val b = async { r.editor.ensureLoaded(p1, rect(2900f, 0f, 3100f, 512f)) }; runCurrent()
        assertThat(b.await().all().toList()).containsExactly(near, far)
        assertThat(r.editor.content(p1)!!.loadedRegion).isEqualTo(rect(0f, 0f, 3100f, 512f))
        r.save.close()
    }

    @Test fun evictOnlyWhenPersisted() = runTest {
        val r = rig()
        val d = async { r.load() }; runCurrent(); d.await()
        r.editor.apply(Edit.Items(p1, emptyList(), listOf(stroke(1))))
        r.editor.evict(p1)
        assertThat(r.editor.content(p1)).isNotNull()
        advanceTimeBy(201); runCurrent()
        r.editor.evict(p1)
        assertThat(r.editor.content(p1)).isNull()
        r.save.close()
    }

    @Test fun deletePageAndUndoRestoresPageAndItems() = runTest {
        val r = rig()
        val d = async { r.load() }; runCurrent(); d.await()
        val s = stroke(1)
        r.editor.apply(Edit.Items(p1, emptyList(), listOf(s)))
        r.editor.apply(Edit.DeletePage(r.editor.pages.value.first { it.id == p1 }, listOf(s)))
        assertThat(r.editor.pages.value.map { it.id.value }).containsExactly(2L)
        assertThat(r.editor.content(p1)).isNull()
        r.editor.undo()
        assertThat(r.editor.pages.value.map { it.id.value }).containsExactly(1L, 2L).inOrder()
        assertThat(r.editor.content(p1)!![ItemId(1)]).isEqualTo(s)
        r.save.close()
    }

    @Test fun batchAppliesAsOneUndoStep() = runTest {
        val r = rig()
        val d = async { r.load() }; runCurrent(); d.await()
        val e1 = Edit.Items(p1, emptyList(), listOf(stroke(1)))
        val e2 = Edit.Items(p1, emptyList(), listOf(stroke(2)))
        r.editor.apply(Edit.Batch(listOf(e1, e2)))
        assertThat(r.editor.content(p1)!!.size).isEqualTo(2)
        r.editor.undo()
        assertThat(r.editor.content(p1)!!.size).isEqualTo(0)
        assertThat(r.editor.history.canUndo.value).isFalse()
        r.save.close()
    }

    @Test fun movePageAndMetaEdits() = runTest {
        val r = rig()
        r.editor.apply(Edit.MovePage(p1, 1.0, 5.0))
        assertThat(r.editor.pages.value.map { it.id.value }).containsExactly(2L, 1L).inOrder()
        val m = r.editor.meta.value
        r.editor.apply(Edit.SetMeta(m, m.copy(title = "New")), undoable = false)
        assertThat(r.editor.meta.value.title).isEqualTo("New")
        assertThat(r.editor.history.canUndo.value).isTrue()  // only the MovePage
        r.editor.undo()
        assertThat(r.editor.history.canUndo.value).isFalse()
        r.save.close()
    }
}
