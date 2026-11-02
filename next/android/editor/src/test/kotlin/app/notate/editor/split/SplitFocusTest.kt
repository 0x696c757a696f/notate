package app.notate.editor.split

import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import app.notate.core.doc.registry.DocumentHandle
import app.notate.core.doc.registry.DocumentOpener
import app.notate.core.doc.registry.DocumentRegistry
import app.notate.core.doc.registry.Opened
import app.notate.core.doc.settings.Settings
import app.notate.core.doc.tools.ToolController
import app.notate.core.input.DriverPort
import app.notate.core.input.EpdScheduler
import app.notate.core.input.FingerGesture
import app.notate.core.input.GestureClassifier
import app.notate.core.input.HoverFocusDebouncer
import app.notate.core.input.InkArbiter
import app.notate.core.input.InputDispatcher
import app.notate.core.input.InputPenMode
import app.notate.core.input.PaneId
import app.notate.core.input.PalmRejector
import app.notate.core.input.PenEvent
import app.notate.core.input.PointerAction
import app.notate.core.input.PointerKind
import app.notate.core.input.PointerSample
import app.notate.core.model.Capability
import app.notate.core.model.DocId
import app.notate.core.model.DocKind
import app.notate.core.model.ToolPreset
import app.notate.core.model.ViewState
import app.notate.core.store.MaxIds
import app.notate.core.testing.FakeClock
import app.notate.core.testing.FakeDocStore
import app.notate.core.testing.InMemoryKeyValueStore
import app.notate.core.testing.meta
import app.notate.core.testing.page
import app.notate.core.workspace.Orientation
import app.notate.core.workspace.PaneSlot
import app.notate.core.workspace.SplitSpec
import app.notate.core.workspace.Tab
import app.notate.core.workspace.Workspace
import app.notate.editor.EditorDeps
import app.notate.editor.PaneFactory
import app.notate.editor.pane.DocumentPane
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
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Focus between the two panes of a split: the pen driver is handed over in order and a stroke on the other pane is not lost. */
@RunWith(RobolectricTestRunner::class)
class SplitFocusTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val log = ArrayList<String>()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val idA = DocId("01TESTDOC000000000000000A")
    private val idB = DocId("01TESTDOC000000000000000B")

    /** A pane without a real view: records what it is told and has a driver that logs. The left half of the window is x < 500. */
    private class FakePane(
        context: Context,
        override val handle: DocumentHandle,
        override val id: PaneId,
        private val log: MutableList<String>,
    ) : DocumentPane {
        val pens = ArrayList<PenEvent>()
        val focus = ArrayList<Boolean>()
        override val driver = object : DriverPort {
            override fun open() { log += "${id.value}.open" }
            override fun close() { log += "${id.value}.close" }
            override fun setActive(on: Boolean) { log += "${id.value}.active=$on" }
        }
        override val closesWhenNotOwner = true
        override val view = View(context)
        override val capabilities: Set<Capability> = emptySet()
        override val penMode = InputPenMode.WRITE
        var released = false
        var x0 = 0f
        var x1 = 1000f
        override fun hitTest(x: Float, y: Float) = x in x0..x1
        override fun onFingerGesture(g: FingerGesture) = Unit
        override fun onPen(ev: PenEvent) { pens += ev }
        override fun onFocusChanged(focused: Boolean) { focus += focused }
        override fun onToolChanged(tool: ToolPreset) = Unit
        override fun saveViewState(): ViewState? = null
        override fun restoreViewState(s: ViewState) = Unit
        override fun release() { released = true }
    }

    private class Parts(val controller: WorkspaceController, val host: PaneHost, val dispatcher: InputDispatcher)

    private fun fake(s: PaneSession?) = s!!.pane as FakePane

    private fun setup(focusRight: Boolean = false): Parts {
        val stores = HashMap<DocId, FakeDocStore>()
        val registry = DocumentRegistry(
            object : DocumentOpener {
                override suspend fun open(id: DocId): Opened {
                    val s = stores.getOrPut(id) { FakeDocStore(meta(id.value), listOf(page(1))) }
                    return Opened(s, s.readMeta(), s.readPages(), MaxIds(1, 0, emptyMap()))
                }
            },
            appScope, FakeClock(), Dispatchers.Unconfined, Dispatchers.Unconfined,
        )
        val settings = Settings(InMemoryKeyValueStore())
        val factory = PaneFactory { ctx, handle, _, host, _, id ->
            FakePane(ctx, handle, id, log).also {
                it.view.layout(0, 0, 100, 100)
                host.arbiter.attach(it)      // a real pane does this when it gets its size
                host.claimPen(it)
            }
        }
        val deps = EditorDeps(
            registry = registry, settings = settings, toolController = ToolController(settings),
            dispatchers = DispatcherSet(Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined),
            device = DeviceInfo(isOnyx = false, model = "test", xdpi = 227f, maxPressure = 4096f, hasRawDrawing = false),
            epd = EpdScheduler(NoEpdPort), clock = FakeClock(), appScope = appScope, logger = app.notate.core.model.log.Logger.NONE,
            paneFactories = mapOf(DocKind.NOTEBOOK to factory),
        )
        val host = object : PaneHost {
            override val arbiter = InkArbiter()
            override fun excludeRects() = emptyList<android.graphics.Rect>()
            override fun onDriverStroke(begin: Boolean, timeMs: Long) = Unit
        }
        lateinit var controller: WorkspaceController
        controller = WorkspaceController(
            context, deps, appScope, host, { emptyList() },
            object : WorkspaceController.Listener {
                override fun onPanesChanged(left: PaneSession?, right: PaneSession?, split: SplitSpec?) {
                    // Window coordinates: the left pane is the left half.
                    left?.let { fake(it).apply { x0 = 0f; x1 = if (right != null) 499f else 1000f } }
                    right?.let { fake(it).apply { x0 = 500f; x1 = 1000f } }
                }
                override fun onFocusChanged(focused: PaneSession?) = Unit
                override fun onMessage(text: String) = Unit
                override fun onEmpty() = Unit
            },
        )
        val dispatcher = InputDispatcher(
            PalmRejector(palmMinSizePx = 200f, pxPerCm = 100f),
            GestureClassifier(touchSlopPx = 10f, multiTapSlopPx = 20f),
            HoverFocusDebouncer(80L),
        ) { id -> controller.focus(id) }
        runBlocking {
            val tabs = listOf(Tab(idA, DocKind.NOTEBOOK, "A"), Tab(idB, DocKind.NOTEBOOK, "B"))
            controller.restore(Workspace(tabs, PaneSlot(0), PaneSlot(1), focusRight, SplitSpec(Orientation.HORIZONTAL, 0.5f)))
        }
        dispatcher.focusedPane = controller.current?.pane?.id
        return Parts(controller, host, dispatcher)
    }

    private fun pen(action: PointerAction, x: Float, t: Long) =
        PointerSample(100, PointerKind.STYLUS, action, x, 10f, pressure = 0.5f, timeMs = t)

    @Test fun bothPanesAreShownAndTheLeftOneHasThePen() {
        val p = setup()
        val c = p.controller
        assertThat(c.displayed).hasSize(2)
        assertThat(c.left!!.doc).isEqualTo(idA)
        assertThat(c.right!!.doc).isEqualTo(idB)
        assertThat(p.host.arbiter.owner).isSameInstanceAs(c.left!!.pane)
        assertThat(fake(c.left).focus.last()).isTrue()
        assertThat(fake(c.right).focus.last()).isFalse()
    }

    @Test fun switchingTheFocusClosesTheOldDriverBeforeOpeningTheNewOne() {
        val p = setup()
        val c = p.controller
        val left = fake(c.left).id.value
        val right = fake(c.right).id.value
        log.clear()
        c.focus(fake(c.right).id)
        val close = log.indexOf("$left.close")
        val open = log.indexOf("$right.open")
        assertThat(close).isAtLeast(0)
        assertThat(open).isAtLeast(0)
        assertThat(close).isLessThan(open)
        assertThat(log.indexOf("$left.active=false")).isLessThan(open)
        assertThat(p.host.arbiter.owner).isSameInstanceAs(c.right!!.pane)
        assertThat(c.workspace.value.focusRight).isTrue()
        assertThat(fake(c.left).focus.last()).isFalse()
        assertThat(fake(c.right).focus.last()).isTrue()
    }

    @Test fun aPenThatLandsOnTheOtherPaneGetsTheFocusAndItsFirstPointsAsTouchEvents() {
        val p = setup()
        val c = p.controller
        val panes = { c.displayed.map { it.pane } }
        assertThat(p.dispatcher.onSample(pen(PointerAction.DOWN, 700f, 100), panes())).isTrue()
        p.dispatcher.onSample(pen(PointerAction.MOVE, 710f, 110), panes())
        p.dispatcher.onSample(pen(PointerAction.UP, 710f, 120), panes())
        val right = fake(c.right)
        assertThat(c.current).isSameInstanceAs(c.right)
        assertThat(p.host.arbiter.owner).isSameInstanceAs(c.right!!.pane)
        assertThat(right.pens).hasSize(3)
        val down = right.pens[0] as PenEvent.Down
        assertThat(down.focusSwitched).isTrue()
        assertThat(fake(c.left).pens).isEmpty()
    }

    @Test fun hoveringOverTheOtherPaneForEightyMillisecondsSwitchesTheFocus() {
        val p = setup()
        val c = p.controller
        val panes = { c.displayed.map { it.pane } }
        p.dispatcher.onSample(pen(PointerAction.HOVER_ENTER, 700f, 1000), panes())
        p.dispatcher.onSample(pen(PointerAction.HOVER_MOVE, 700f, 1079), panes())
        assertThat(c.current).isSameInstanceAs(c.left)
        p.dispatcher.onSample(pen(PointerAction.HOVER_MOVE, 700f, 1080), panes())
        assertThat(c.current).isSameInstanceAs(c.right)
        // The pen is then already on the right pane: its stroke is an ordinary one, not a focus switch.
        p.dispatcher.onSample(pen(PointerAction.DOWN, 700f, 1100), panes())
        assertThat((fake(c.right).pens.filterIsInstance<PenEvent.Down>().single()).focusSwitched).isFalse()
    }

    @Test fun swappingTheSidesKeepsThePanesAndThePen() {
        val p = setup()
        val c = p.controller
        val a = c.left
        val b = c.right
        log.clear()
        c.swapSides()
        assertThat(c.left).isSameInstanceAs(b)
        assertThat(c.right).isSameInstanceAs(a)
        assertThat(c.current).isSameInstanceAs(a)
        assertThat(p.host.arbiter.owner).isSameInstanceAs(a!!.pane)
        assertThat(log.filter { it.endsWith(".close") || it.endsWith(".open") }).isEmpty()
        assertThat(fake(a).released).isFalse()
    }

    @Test fun closingAPaneKeepsItsTabAndLeavesTheOtherWithThePen() {
        val p = setup()
        val c = p.controller
        val leftPane = c.left
        c.closePane(right = false)
        assertThat(c.right).isNull()
        assertThat(c.left!!.doc).isEqualTo(idB)
        assertThat(c.workspace.value.tabs.map { it.doc }).containsExactly(idA, idB)
        assertThat(c.workspace.value.split).isNull()
        assertThat(p.host.arbiter.owner).isSameInstanceAs(c.left!!.pane)
        assertThat(fake(leftPane).released).isFalse()       // kept in the cache while its tab is open
    }
}
