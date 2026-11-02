package app.notate.editor

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
import app.notate.core.model.SystemClock
import app.notate.core.model.log.Logger
import app.notate.core.store.doc.SqliteDocStoreFactory
import app.notate.core.testing.FakeClock
import app.notate.core.testing.InMemoryKeyValueStore
import app.notate.core.testing.meta
import app.notate.core.testing.page
import app.notate.editor.pane.NotebookPane
import app.notate.editor.pane.PaneHost
import app.notate.platform.device.DeviceInfo
import app.notate.platform.epd.NoEpdPort
import app.notate.platform.sql.AndroidSqlDriverFactory
import app.notate.platform.thread.DispatcherSet
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.file.Files

/** Draws a stroke with synthetic pen events on a real SQLite document, "closes" it and reads it back through a fresh registry. */
@RunWith(RobolectricTestRunner::class)
class EditorFlowTest {
    private val drivers = AndroidSqlDriverFactory()
    private val dir = Files.createTempDirectory("editorflow").toFile()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var openedStore: app.notate.core.store.DocStore? = null

    private fun registry(factory: SqliteDocStoreFactory) = DocumentRegistry(
        opener = object : DocumentOpener {
            override suspend fun open(id: DocId): Opened {
                val store = factory.open(dir)
                openedStore = store
                return Opened(store, store.readMeta(), store.readPages(), store.maxIds())
            }
        },
        scope = appScope, clock = SystemClock, main = Dispatchers.Unconfined, io = Dispatchers.IO,
    )

    @Test fun strokeDrawnWithThePenIsStillThereAfterReopening() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val factory = SqliteDocStoreFactory(drivers, { Dispatchers.IO }, SystemClock)
        val meta = meta()
        factory.create(dir, meta, listOf(page(1))).close()

        val registry = registry(factory)
        val handle = registry.acquire(meta.id)
        val settings = Settings(InMemoryKeyValueStore()).also { it.holdShapeMs.set(0) }   // no timing-dependent shape recognition
        val deps = EditorDeps(
            registry = registry, settings = settings, toolController = ToolController(settings),
            dispatchers = DispatcherSet(Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.IO),
            device = DeviceInfo(isOnyx = false, model = "test", xdpi = 227f, maxPressure = 4096f, hasRawDrawing = false),
            epd = EpdScheduler(NoEpdPort), clock = FakeClock(), appScope = appScope, logger = Logger.NONE,
        )
        val host = object : PaneHost {
            override val arbiter = InkArbiter()
            override fun excludeRects() = emptyList<android.graphics.Rect>()
            override fun onDriverStroke(begin: Boolean, timeMs: Long) = Unit
        }
        val pane = NotebookPane(context, handle, deps, host, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        pane.view.layout(0, 0, 1404, 1872)
        val pageId = handle.editor.pages.value.first().id
        withTimeout(10_000) { handle.editor.ensureLoaded(pageId) }

        pane.onPen(PenEvent.Down(300f, 400f, 0.5f, 0f, 0f, 0L, eraser = false))
        for (i in 1..10) pane.onPen(PenEvent.Move(300f + i * 20f, 400f + i * 5f, 0.5f, 0f, 0f, i * 8L))
        pane.onPen(PenEvent.Up(520f, 460f, 100L))

        assertThat(handle.editor.content(pageId)!!.all().toList()).hasSize(1)
        assertThat(handle.editor.history.canUndo.value).isTrue()

        pane.release()
        handle.save.flush()
        // Close the first connection before the second one opens: two live connections to one file are not what
        // this test is about and make the Robolectric SQLite shadow racy.
        handle.save.close()
        openedStore!!.close()

        val reopened = factory.open(dir)
        try {
            assertThat(withTimeout(10_000) { reopened.readItems(pageId) }).hasSize(1)
        } finally {
            reopened.close()
        }
    }

    @Test fun undoRemovesTheStroke() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val factory = SqliteDocStoreFactory(drivers, { Dispatchers.IO }, SystemClock)
        val meta = meta()
        factory.create(dir, meta, listOf(page(1))).close()
        val registry = registry(factory)
        val handle = registry.acquire(meta.id)
        val settings = Settings(InMemoryKeyValueStore()).also { it.holdShapeMs.set(0) }   // no timing-dependent shape recognition
        val deps = EditorDeps(
            registry, settings, ToolController(settings),
            DispatcherSet(Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.IO),
            DeviceInfo(false, "test", 227f, 4096f, false), EpdScheduler(NoEpdPort), FakeClock(), appScope, Logger.NONE,
        )
        val host = object : PaneHost {
            override val arbiter = InkArbiter()
            override fun excludeRects() = emptyList<android.graphics.Rect>()
            override fun onDriverStroke(begin: Boolean, timeMs: Long) = Unit
        }
        val pane = NotebookPane(context, handle, deps, host, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        pane.view.layout(0, 0, 1404, 1872)
        val pageId = handle.editor.pages.value.first().id
        withTimeout(10_000) { handle.editor.ensureLoaded(pageId) }
        pane.onPen(PenEvent.Down(300f, 400f, 0.5f, 0f, 0f, 0L, eraser = false))
        pane.onPen(PenEvent.Move(400f, 420f, 0.5f, 0f, 0f, 8L))
        pane.onPen(PenEvent.Up(450f, 430f, 16L))
        assertThat(handle.editor.content(pageId)!!.all().toList()).hasSize(1)
        assertThat(handle.editor.undo()).isTrue()
        assertThat(handle.editor.content(pageId)!!.all().toList()).isEmpty()
        pane.release()
    }
}
