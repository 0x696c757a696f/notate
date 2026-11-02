package app.notate.editor

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.notate.core.doc.registry.DocumentHandle
import app.notate.core.doc.registry.DocumentOpener
import app.notate.core.doc.registry.DocumentRegistry
import app.notate.core.doc.registry.Opened
import app.notate.core.doc.settings.Settings
import app.notate.core.doc.settings.Tap2Action
import app.notate.core.doc.settings.Tap3Action
import app.notate.core.doc.tools.ToolController
import app.notate.core.input.EpdScheduler
import app.notate.core.input.FingerGesture
import app.notate.core.input.InkArbiter
import app.notate.core.input.PenEvent
import app.notate.core.model.DocId
import app.notate.core.model.SystemClock
import app.notate.core.model.ToolKind
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

/** Settings → Writing & gestures reach the pane: multi-finger taps and the pen button (04 §9). */
@RunWith(RobolectricTestRunner::class)
class InputSettingsWiringTest {
    private val drivers = AndroidSqlDriverFactory()
    private val dir = Files.createTempDirectory("inputwiring").toFile()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var focusRequests = 0

    private class Fixture(val pane: NotebookPane, val handle: DocumentHandle, val settings: Settings, val tools: ToolController)

    private suspend fun fixture(): Fixture {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val factory = SqliteDocStoreFactory(drivers, { Dispatchers.IO }, SystemClock)
        val meta = meta()
        factory.create(dir, meta, listOf(page(1))).close()
        val registry = DocumentRegistry(
            opener = object : DocumentOpener {
                override suspend fun open(id: DocId): Opened {
                    val store = factory.open(dir)
                    return Opened(store, store.readMeta(), store.readPages(), store.maxIds())
                }
            },
            scope = appScope, clock = SystemClock, main = Dispatchers.Unconfined, io = Dispatchers.IO,
        )
        val handle = registry.acquire(meta.id)
        val settings = Settings(InMemoryKeyValueStore()).also { it.holdShapeMs.set(0) }
        val tools = ToolController(settings)
        val deps = EditorDeps(
            registry = registry, settings = settings, toolController = tools,
            dispatchers = DispatcherSet(Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.IO),
            device = DeviceInfo(isOnyx = false, model = "test", xdpi = 227f, maxPressure = 4096f, hasRawDrawing = false),
            epd = EpdScheduler(NoEpdPort), clock = FakeClock(), appScope = appScope, logger = Logger.NONE,
        )
        val host = object : PaneHost {
            override val arbiter = InkArbiter()
            override fun excludeRects() = emptyList<android.graphics.Rect>()
            override fun onDriverStroke(begin: Boolean, timeMs: Long) = Unit
            override fun onFocusModeRequested() { focusRequests++ }
        }
        val pane = NotebookPane(context, handle, deps, host, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        pane.view.layout(0, 0, 1404, 1872)
        withTimeout(10_000) { handle.editor.ensureLoaded(handle.editor.pages.value.first().id) }
        return Fixture(pane, handle, settings, tools)
    }

    private fun stroke(f: Fixture) {
        f.pane.onPen(PenEvent.Down(300f, 400f, 0.5f, 0f, 0f, 0L, eraser = false))
        f.pane.onPen(PenEvent.Move(400f, 420f, 0.5f, 0f, 0f, 8L))
        f.pane.onPen(PenEvent.Up(450f, 430f, 16L))
    }

    @Test fun threeFingerTapTogglesFocusModeWhenSet() = runBlocking {
        val f = fixture()
        f.pane.onFingerGesture(FingerGesture.Tap(3, 100f, 100f))     // default = Redo
        assertThat(focusRequests).isEqualTo(0)
        f.settings.tap3.set(Tap3Action.FOCUS_MODE)
        f.pane.onFingerGesture(FingerGesture.Tap(3, 100f, 100f))
        assertThat(focusRequests).isEqualTo(1)
        f.settings.tap3.set(Tap3Action.OFF)
        f.pane.onFingerGesture(FingerGesture.Tap(3, 100f, 100f))
        assertThat(focusRequests).isEqualTo(1)
        f.pane.release()
    }

    @Test fun twoFingerTapFollowsTheSetting() = runBlocking {
        val f = fixture()
        val page = f.handle.editor.pages.value.first().id
        stroke(f)
        f.settings.tap2.set(Tap2Action.OFF)
        f.pane.onFingerGesture(FingerGesture.Tap(2, 100f, 100f))
        assertThat(f.handle.editor.content(page)!!.all().toList()).hasSize(1)
        f.settings.tap2.set(Tap2Action.UNDO)
        f.pane.onFingerGesture(FingerGesture.Tap(2, 100f, 100f))
        assertThat(f.handle.editor.content(page)!!.all().toList()).isEmpty()
        f.settings.tap2.set(Tap2Action.REDO)
        f.pane.onFingerGesture(FingerGesture.Tap(2, 100f, 100f))
        assertThat(f.handle.editor.content(page)!!.all().toList()).hasSize(1)
        f.pane.release()
    }

    @Test fun penButtonLassoIsTemporaryAndNotSaved() = runBlocking {
        val f = fixture()
        f.pane.onPen(PenEvent.Down(300f, 400f, 0.5f, 0f, 0f, 0L, eraser = false, lasso = true))
        assertThat(f.tools.state.value.active).isEqualTo(ToolKind.LASSO)
        assertThat(f.tools.baseState().active).isNotEqualTo(ToolKind.LASSO)
        f.pane.onPen(PenEvent.Up(300f, 400f, 16L))
        assertThat(f.tools.state.value.active).isEqualTo(f.tools.baseState().active)
        f.pane.release()
    }
}
