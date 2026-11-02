package app.notate.core.doc.select

import app.notate.core.doc.edit.IdAllocator
import app.notate.core.doc.editor.DocumentEditor
import app.notate.core.doc.save.SaveQueue
import app.notate.core.doc.tools.RecordingPreview
import app.notate.core.doc.tools.ToolContext
import app.notate.core.doc.tools.ToolSettings
import app.notate.core.geometry.Vec2
import app.notate.core.model.Item
import app.notate.core.model.PageId
import app.notate.core.model.PageInfo
import app.notate.core.model.edit.Edit
import app.notate.core.store.MaxIds
import app.notate.core.testing.FakeClock
import app.notate.core.testing.FakeDocStore
import app.notate.core.testing.meta
import app.notate.core.testing.page
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent

/** Two stacked pages (height 800, gap 20) so page 2 starts at y = 820 on the document plane. */
@OptIn(ExperimentalCoroutinesApi::class)
class SelectRig(scope: TestScope, seed: List<Item> = emptyList(), docId: String = "01TESTDOC000000000000000A", loadSecond: Boolean = true) {
    val p1 = PageId(1)
    val p2 = PageId(2)
    val clock = FakeClock()
    val preview = RecordingPreview()
    val store: FakeDocStore
    val editor: DocumentEditor
    val geometry = object : PageGeometry {
        override fun origin(page: PageId) = if (page == p2) Vec2(0f, 820f) else Vec2(0f, 0f)
        override fun pageAt(docX: Float, docY: Float): PageId? = when {
            docY in 0f..800f -> p1
            docY in 820f..1620f -> p2
            else -> null
        }
    }
    val controller: SelectionController

    init {
        val d = StandardTestDispatcher(scope.testScheduler)
        val pages: List<PageInfo> = listOf(page(1, height = 800f), page(2, height = 800f))
        store = FakeDocStore(meta(docId), pages)
        val save = SaveQueue(store, d, clock)
        editor = DocumentEditor(meta(docId), pages, store, save, IdAllocator(MaxIds(2, 0, emptyMap())), clock, d, d)
        scope.testScheduler.runCurrent()
        for (p in listOf(p1, p2).take(if (loadSecond) 2 else 1)) {
            val job = scope.async { editor.ensureLoaded(p) }
            scope.testScheduler.runCurrent()
            job.getCompleted()
        }
        if (seed.isNotEmpty()) editor.apply(Edit.Items(p1, emptyList(), seed), undoable = false)
        controller = SelectionController(editor, geometry, preview)
    }

    fun ctx(page: PageId = p1, selection: Boolean = true) = ToolContext(
        editor, page, null, 1f, preview, ToolSettings(null, 5f, handleHitPx = 10f, rotateOffsetPx = 24f), clock,
        selection = if (selection) controller else null,
    )

    fun items(page: PageId = p1) = editor.content(page)!!.all().toList()
}

