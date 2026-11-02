package app.notate.editor.zoom

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.notate.core.doc.registry.DocumentOpener
import app.notate.core.doc.registry.DocumentRegistry
import app.notate.core.doc.registry.Opened
import app.notate.core.doc.settings.Settings
import app.notate.core.doc.tools.ToolController
import app.notate.core.doc.zoom.ZoomWindowState
import app.notate.core.input.EpdScheduler
import app.notate.core.input.FingerGesture
import app.notate.core.input.InkArbiter
import app.notate.core.input.PenEvent
import app.notate.core.model.DocId
import app.notate.core.model.PageId
import app.notate.core.model.SystemClock
import app.notate.core.model.log.Logger
import app.notate.core.store.doc.SqliteDocStoreFactory
import app.notate.core.testing.FakeClock
import app.notate.core.testing.InMemoryKeyValueStore
import app.notate.core.testing.meta
import app.notate.core.testing.page
import app.notate.editor.EditorDeps
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

/** The zoom writing window inside a real pane: toolbar-less wiring of controls, pen mapping and automatic advance. */
@RunWith(RobolectricTestRunner::class)
class ZoomWindowPaneTest {
    private val drivers = AndroidSqlDriverFactory()
    private val dir = Files.createTempDirectory("zoompane").toFile()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var openedStore: app.notate.core.store.DocStore? = null
    private val changes = ArrayList<Boolean>()

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

    /** Runs [block] with a laid-out pane (1404 × 1872) on a one-page document whose first page is loaded. */
    private fun withPane(block: suspend (NotebookPane, app.notate.core.doc.registry.DocumentHandle, PageId) -> Unit) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val factory = SqliteDocStoreFactory(drivers, { Dispatchers.IO }, SystemClock)
        val meta = meta()
        factory.create(dir, meta, listOf(page(1))).close()
        val registry = registry(factory)
        val handle = registry.acquire(meta.id)
        val settings = Settings(InMemoryKeyValueStore()).also { it.holdShapeMs.set(0) }
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
            override fun onZoomWindowChanged(open: Boolean) { changes.add(open) }
        }
        val pane = NotebookPane(context, handle, deps, host, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        pane.view.layout(0, 0, 1404, 1872)
        val pageId = handle.editor.pages.value.first().id
        withTimeout(10_000) { handle.editor.ensureLoaded(pageId) }
        try {
            block(pane, handle, pageId)
        } finally {
            pane.release()
            handle.save.flush()
            handle.save.close()
            openedStore?.close()
        }
    }

    private fun tap(pane: NotebookPane, c: ZoomWindowView.Control) {
        val r = pane.zoomWindow.rectOf(c)!!
        pane.onFingerGesture(FingerGesture.Tap(1, r.centerX(), r.centerY()))
    }

    private fun write(pane: NotebookPane, x0: Float, y: Float, x1: Float) {
        pane.onPen(PenEvent.Down(x0, y, 0.5f, 0f, 0f, 0L, eraser = false))
        for (i in 1..10) pane.onPen(PenEvent.Move(x0 + (x1 - x0) * i / 10f, y + i, 0.5f, 0f, 0f, i * 8L))
        pane.onPen(PenEvent.Up(x1, y + 11f, 100L))
    }

    @Test fun openingAndClosingFollowsTheRequestAndTellsTheHost() = withPane { pane, _, _ ->
        assertThat(pane.zoomWindowOpen).isFalse()
        assertThat(pane.setZoomWindowOpen(true)).isTrue()
        assertThat(pane.zoomWindowOpen).isTrue()
        assertThat(pane.zoomWindow.state!!.magnification).isEqualTo(ZoomWindowState.DEFAULT_MAGNIFICATION)
        pane.setZoomWindowOpen(false)
        assertThat(pane.zoomWindowOpen).isFalse()
        assertThat(changes).containsExactly(true, false).inOrder()
    }

    @Test fun controlButtonsChangeTheState() = withPane { pane, _, _ ->
        pane.setZoomWindowOpen(true)
        val st = pane.zoomWindow.state!!
        val w0 = st.box.width
        tap(pane, ZoomWindowView.Control.LARGER)
        assertThat(st.magnification).isEqualTo(3.5f)
        assertThat(st.box.width).isLessThan(w0)
        tap(pane, ZoomWindowView.Control.SMALLER)
        tap(pane, ZoomWindowView.Control.SMALLER)
        assertThat(st.magnification).isEqualTo(2.5f)

        val l0 = st.box.l
        tap(pane, ZoomWindowView.Control.FORWARD)
        assertThat(st.box.l).isGreaterThan(l0)
        tap(pane, ZoomWindowView.Control.BACK)
        assertThat(st.box.l).isWithin(0.01f).of(l0)

        val t0 = st.box.t
        tap(pane, ZoomWindowView.Control.FORWARD)
        tap(pane, ZoomWindowView.Control.NEW_LINE)
        assertThat(st.box.l).isWithin(0.01f).of(st.marginPt)
        assertThat(st.box.t).isWithin(0.01f).of(t0 + 20f)            // the paper's ruling

        tap(pane, ZoomWindowView.Control.CLOSE)
        assertThat(pane.zoomWindowOpen).isFalse()
    }

    @Test fun penStrokesLandInsideTheBoxOnThePage() = withPane { pane, handle, page ->
        pane.setZoomWindowOpen(true)
        val box = pane.zoomWindow.state!!.box
        val w = pane.zoomWindow
        val y = w.writing.centerY()
        write(pane, w.writing.left + 100f, y, w.writing.left + 500f)

        val strokes = handle.editor.content(page)!!.all().toList()
        assertThat(strokes).hasSize(1)
        val b = strokes[0].bounds
        assertThat(b.l).isAtLeast(box.l)
        assertThat(b.r).isAtMost(box.r)
        assertThat(b.t).isAtLeast(box.t - 1f)
        assertThat(b.b).isAtMost(box.b + 1f)
        // 100 px from the left of a 1348 px wide writing area is 100/1348 of the box.
        val expectedL = box.l + box.width * 100f / w.writing.width()
        assertThat(b.l).isWithin(3f).of(expectedL)
    }

    @Test fun aStrokeEndingNearTheRightEdgeMovesTheBoxOn() = withPane { pane, _, _ ->
        pane.setZoomWindowOpen(true)
        val w = pane.zoomWindow
        val st = w.state!!
        val l0 = st.box.l
        val width = st.box.width
        write(pane, w.writing.left + 600f, w.writing.centerY(), w.writing.left + 0.95f * w.writing.width())
        assertThat(st.box.l).isWithin(0.5f).of(l0 + 0.6f * width)
    }

    @Test fun aShortStrokeLeavesTheBoxAlone() = withPane { pane, _, _ ->
        pane.setZoomWindowOpen(true)
        val st = pane.zoomWindow.state!!
        val before = st.box
        val w = pane.zoomWindow
        write(pane, w.writing.left + 50f, w.writing.centerY(), w.writing.left + 300f)
        assertThat(st.box).isEqualTo(before)
    }

    @Test fun penOutsideTheWritingAreaDrawsNothingWhileTheWindowIsOpen() = withPane { pane, handle, page ->
        pane.setZoomWindowOpen(true)
        write(pane, 300f, 300f, 700f)
        assertThat(handle.editor.content(page)!!.all().toList()).isEmpty()
        pane.setZoomWindowOpen(false)
        write(pane, 300f, 300f, 700f)
        assertThat(handle.editor.content(page)!!.all().toList()).hasSize(1)
    }

    @Test fun fingerTapsInTheWindowAreNotPassedToThePage() = withPane { pane, _, _ ->
        pane.setZoomWindowOpen(true)
        val st = pane.zoomWindow.state!!
        val before = st.box
        val w = pane.zoomWindow.writing
        pane.onFingerGesture(FingerGesture.Tap(1, w.centerX(), w.centerY()))
        assertThat(st.box).isEqualTo(before)
    }

    @Test fun draggingTheBoxOnThePageMovesIt() = withPane { pane, _, _ ->
        pane.setZoomWindowOpen(true)
        val st = pane.zoomWindow.state!!
        val b = pane.zoomWindow.boxOnScreen()!!
        val l0 = st.box.l
        val scale = pane.viewport.scale
        pane.onFingerGesture(FingerGesture.PanStart(b.centerX(), b.centerY()))
        pane.onFingerGesture(FingerGesture.Pan(60f, 0f))
        pane.onFingerGesture(FingerGesture.PanEnd(0f, 0f))
        assertThat(st.box.l).isWithin(0.01f).of(l0 + 60f / scale)
    }
}
