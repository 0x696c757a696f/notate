package app.notate.editor

import android.content.Context
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import app.notate.core.doc.edit.IdAllocator
import app.notate.core.doc.editor.DocumentEditor
import app.notate.core.doc.save.SaveQueue
import app.notate.core.input.EpdScheduler
import app.notate.core.input.InkArbiter
import app.notate.core.model.PageId
import app.notate.core.model.log.Logger
import app.notate.core.store.MaxIds
import app.notate.core.testing.FakeClock
import app.notate.core.testing.FakeDocStore
import app.notate.core.testing.meta
import app.notate.core.testing.page
import app.notate.editor.pages.PageActions
import app.notate.editor.pages.PageGridHost
import app.notate.editor.pages.PageGridOverlay
import app.notate.editor.pages.PageGridView
import app.notate.editor.pages.ThumbnailStore
import app.notate.editor.popup.PopupHost
import app.notate.platform.epd.NoEpdPort
import app.notate.render.ink.GeometryCache
import app.notate.render.ink.InkRenderer
import app.notate.render.paper.BackgroundRendererSet
import app.notate.render.thumb.ThumbnailRenderer
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class PageGridOverlayTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var save: SaveQueue
    private lateinit var editor: DocumentEditor
    private lateinit var overlay: PageGridOverlay
    private val jumps = ArrayList<PageId>()
    private val templates = ArrayList<PageId>()
    private var closed = 0

    @Before fun setUp() {
        val pages = (1L..4L).map { page(it) }
        val store = FakeDocStore(meta(), pages)
        save = SaveQueue(store, Dispatchers.Unconfined, FakeClock())
        editor = DocumentEditor(meta(), pages, store, save, IdAllocator(MaxIds(4, 0, emptyMap())), FakeClock(), Dispatchers.Unconfined, Dispatchers.Unconfined)
        val thumbs = ThumbnailStore(
            editor, ThumbnailRenderer(BackgroundRendererSet(), InkRenderer(GeometryCache()), Dispatchers.Unconfined), null, scope,
            Dispatchers.Unconfined, Logger.NONE,
        )
        val popups = PopupHost(FrameLayout(context), InkArbiter(), EpdScheduler(NoEpdPort))
        val host = object : PageGridHost {
            override fun onJump(page: PageId) { jumps.add(page) }
            override fun onClose() { closed++ }
            override fun onChangeTemplate(page: PageId) { templates.add(page) }
        }
        overlay = PageGridOverlay(context, editor, thumbs, PageActions(context, editor, scope, Logger.NONE), popups, host, scope, startPage = PageId(1))
        overlay.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1872, View.MeasureSpec.EXACTLY))
        overlay.layout(0, 0, W, 1872)
    }

    @After fun tearDown() {
        overlay.release()
        save.close()
    }

    private fun ids(): List<Long> = editor.pages.value.map { it.id.value }

    /** Centre of cell [i] in grid coordinates (mirrors PageGridView's layout constants at density 1). */
    private fun center(i: Int, dx: Float = 0f): Pair<Float, Float> {
        val g: PageGridView = overlay.grid
        val cols = g.columns
        val pad = 16f
        val gap = 12f
        val cellW = (W - 2 * pad - (cols - 1) * gap) / cols
        val thumbH = cellW * 4f / 3f
        val cellH = thumbH + 30f + gap
        return (pad + (i % cols) * (cellW + gap) + cellW / 2f + dx) to (pad + (i / cols) * cellH + thumbH / 2f)
    }

    private fun touch(action: Int, p: Pair<Float, Float>, t: Long) {
        val ev = MotionEvent.obtain(0L, t, action, p.first, p.second, 0)
        overlay.grid.dispatchTouchEvent(ev)
        ev.recycle()
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test fun tapOnACellJumpsToThatPage() {
        val c = center(2)
        touch(MotionEvent.ACTION_DOWN, c, 0)
        touch(MotionEvent.ACTION_UP, c, 50)
        assertThat(jumps).containsExactly(PageId(3))
    }

    @Test fun longPressAndDragReordersWithExactlyOneMove() {
        val from = center(3)
        touch(MotionEvent.ACTION_DOWN, from, 0)
        idle(PageGridView.LONG_PRESS_MS + 100)
        // Drag onto the left half of cell 1: page 4 ends up at index 1.
        val to = center(1, dx = -20f)
        touch(MotionEvent.ACTION_MOVE, (from.first + 30f) to from.second, 700)
        touch(MotionEvent.ACTION_MOVE, to, 800)
        touch(MotionEvent.ACTION_UP, to, 900)
        assertThat(ids()).containsExactly(1L, 4L, 2L, 3L).inOrder()
        assertThat(jumps).isEmpty()
        assertThat(editor.history.canUndo.value).isTrue()
        editor.undo()
        assertThat(ids()).containsExactly(1L, 2L, 3L, 4L).inOrder()
    }

    @Test fun dragPastTheLastCellMovesToTheEnd() {
        val from = center(0)
        touch(MotionEvent.ACTION_DOWN, from, 0)
        idle(PageGridView.LONG_PRESS_MS + 100)
        val end = center(3, dx = 60f)
        touch(MotionEvent.ACTION_MOVE, (from.first + 30f) to from.second, 700)
        touch(MotionEvent.ACTION_MOVE, end, 800)
        touch(MotionEvent.ACTION_UP, end, 900)
        assertThat(ids()).containsExactly(2L, 3L, 4L, 1L).inOrder()
    }

    @Test fun reorderIsOffWhileFilteringBookmarks() {
        overlay.grid.bookmarksOnly = true
        overlay.grid.pages = editor.pages.value.map { if (it.id.value == 1L || it.id.value == 2L) it.copy(bookmarked = true) else it }
        overlay.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1872, View.MeasureSpec.EXACTLY))
        overlay.layout(0, 0, W, 1872)
        val from = center(0)
        touch(MotionEvent.ACTION_DOWN, from, 0)
        idle(PageGridView.LONG_PRESS_MS + 100)
        val to = center(1, dx = 60f)
        touch(MotionEvent.ACTION_MOVE, to, 800)
        touch(MotionEvent.ACTION_UP, to, 900)
        assertThat(ids()).containsExactly(1L, 2L, 3L, 4L).inOrder()
    }

    @Test fun plusAddsAPageAtTheEndAndDoneCloses() {
        val bar = (overlay.getChildAt(0) as android.view.ViewGroup).getChildAt(0) as android.view.ViewGroup
        // bar children: title, spacer, All, Bookmarks, spacer, +, Done
        bar.getChildAt(5).performClick()
        assertThat(ids()).hasSize(5)
        assertThat(ids().last()).isEqualTo(5L)
        bar.getChildAt(6).performClick()
        assertThat(closed).isEqualTo(1)
    }

    private companion object {
        const val W = 1404
    }
}
