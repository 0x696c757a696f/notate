package app.notate.editor.split

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.notate.core.doc.registry.DocumentOpener
import app.notate.core.doc.registry.DocumentRegistry
import app.notate.core.doc.registry.Opened
import app.notate.core.doc.settings.Settings
import app.notate.core.doc.tools.ToolController
import app.notate.core.input.EpdScheduler
import app.notate.core.input.InkArbiter
import app.notate.core.input.PenEvent
import app.notate.core.model.DocId
import app.notate.core.model.DocKind
import app.notate.core.store.MaxIds
import app.notate.core.testing.FakeClock
import app.notate.core.testing.FakeDocStore
import app.notate.core.testing.InMemoryKeyValueStore
import app.notate.core.testing.meta
import app.notate.core.testing.page
import app.notate.core.workspace.PaneSlot
import app.notate.core.workspace.SplitSpec
import app.notate.core.workspace.Tab
import app.notate.core.workspace.Workspace
import app.notate.editor.EditorDeps
import app.notate.editor.pane.NotebookPane
import app.notate.editor.pane.PaneHost
import app.notate.editor.workspace.PaneSession
import app.notate.editor.workspace.WorkspaceController
import app.notate.platform.device.DeviceInfo
import app.notate.platform.epd.NoEpdPort
import app.notate.platform.thread.DispatcherSet
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** One document in both panes of a split: one handle, two independent panes, and a stroke in one reaches the other. */
@RunWith(RobolectricTestRunner::class)
class SameDocBothPanesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val id = DocId("01TESTDOC000000000000000A")

    @Test fun aStrokeInTheLeftPaneReachesTheRightPaneThroughTheSharedDocument() = runBlocking {
        val store = FakeDocStore(meta(id.value), listOf(page(1)))
        val registry = DocumentRegistry(
            object : DocumentOpener {
                override suspend fun open(id: DocId) = Opened(store, store.readMeta(), store.readPages(), MaxIds(1, 0, emptyMap()))
            },
            appScope, FakeClock(), Dispatchers.Unconfined, Dispatchers.Unconfined,
        )
        val settings = Settings(InMemoryKeyValueStore()).also { it.holdShapeMs.set(0) }
        val deps = EditorDeps(
            registry = registry, settings = settings, toolController = ToolController(settings),
            dispatchers = DispatcherSet(Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined),
            device = DeviceInfo(isOnyx = false, model = "test", xdpi = 227f, maxPressure = 4096f, hasRawDrawing = false),
            epd = EpdScheduler(NoEpdPort), clock = FakeClock(), appScope = appScope, logger = app.notate.core.model.log.Logger.NONE,
        )
        val host = object : PaneHost {
            override val arbiter = InkArbiter()
            override fun excludeRects() = emptyList<android.graphics.Rect>()
            override fun onDriverStroke(begin: Boolean, timeMs: Long) = Unit
        }
        val controller = WorkspaceController(
            context, deps, appScope, host, { emptyList() },
            object : WorkspaceController.Listener {
                override fun onPanesChanged(left: PaneSession?, right: PaneSession?, split: SplitSpec?) = Unit
                override fun onFocusChanged(focused: PaneSession?) = Unit
                override fun onMessage(text: String) = Unit
                override fun onEmpty() = Unit
            },
        )
        controller.restore(Workspace(listOf(Tab(id, DocKind.NOTEBOOK, "A")), PaneSlot(0)))
        controller.openBeside(id)

        val left = controller.left!!
        val right = controller.right!!
        assertThat(controller.workspace.value.tabs).hasSize(1)
        assertThat(left.handle).isSameInstanceAs(right.handle)
        assertThat(left.pane).isNotSameInstanceAs(right.pane)
        assertThat(left.pane.id).isNotEqualTo(right.pane.id)
        assertThat(left.persistsMeta).isTrue()
        assertThat(right.persistsMeta).isFalse()

        left.pane.view.layout(0, 0, 700, 1872)
        right.pane.view.layout(0, 0, 700, 1872)
        val editor = left.handle.editor
        val pageId = editor.pages.value.first().id
        withTimeout(10_000) { editor.ensureLoaded(pageId) }
        var changes = 0
        val job = appScope.launch { editor.changes.collect { changes++ } }

        val pane = left.pane as NotebookPane
        pane.onPen(PenEvent.Down(300f, 400f, 0.5f, 0f, 0f, 0L, eraser = false))
        for (i in 1..10) pane.onPen(PenEvent.Move(300f + i * 20f, 400f + i * 5f, 0.5f, 0f, 0f, i * 8L))
        pane.onPen(PenEvent.Up(520f, 460f, 100L))

        assertThat(changes).isAtLeast(1)
        assertThat(right.handle.editor.content(pageId)!!.all().toList()).hasSize(1)
        // One undo history serves both panes.
        assertThat(right.handle.editor.history.canUndo.value).isTrue()
        job.cancel()

        controller.closePane(right = true)
        assertThat(controller.right).isNull()
        assertThat(controller.left).isSameInstanceAs(left)
        assertThat(controller.workspace.value.tabs).hasSize(1)
        assertThat(left.handle.editor.content(pageId)!!.all().toList()).hasSize(1)
        controller.shutdown()
    }
}
