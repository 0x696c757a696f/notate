package app.notate.core.doc.tools

import app.notate.core.doc.edit.IdAllocator
import app.notate.core.doc.editor.DocumentEditor
import app.notate.core.doc.save.SaveQueue
import app.notate.core.model.InkPoint
import app.notate.core.model.Item
import app.notate.core.model.PageId
import app.notate.core.model.PageInfo
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

class RecordingPreview : PreviewSink {
    val shown = ArrayList<Preview>()
    var cleared = 0
    override fun show(p: Preview) { shown.add(p) }
    override fun clear() { cleared++ }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ToolRig(scope: TestScope, seed: List<Item> = emptyList(), pxPerPt: Float = 1f, val holdMs: Long? = 600, radiusPx: Float = 5f) {
    val p1 = PageId(1)
    val clock = FakeClock()
    val preview = RecordingPreview()
    val editor: DocumentEditor
    val ctx: ToolContext

    init {
        val d = StandardTestDispatcher(scope.testScheduler)
        val pages: List<PageInfo> = listOf(page(1))
        val store = FakeDocStore(meta(), pages)
        val save = SaveQueue(store, d, clock)
        editor = DocumentEditor(meta(), pages, store, save, IdAllocator(MaxIds(1, 0, emptyMap())), clock, d, d)
        scope.testScheduler.runCurrent()
        val job = scope.async { editor.ensureLoaded(p1) }
        scope.testScheduler.runCurrent()
        job.getCompleted()
        if (seed.isNotEmpty()) editor.apply(app.notate.core.model.edit.Edit.Items(p1, emptyList(), seed), undoable = false)
        ctx = ToolContext(editor, p1, null, pxPerPt, preview, ToolSettings(holdMs, radiusPx), clock)
    }

    fun items() = editor.content(p1)!!.all().toList()
}

fun ip(x: Float, y: Float, t: Long = 0, p: Float = 0.5f) = InkPoint(x, y, p, 0f, 0f, t)
