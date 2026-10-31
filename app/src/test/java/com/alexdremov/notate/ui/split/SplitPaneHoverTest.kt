package com.alexdremov.notate.ui.split

import android.content.Context
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SplitPaneHoverTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val layout =
        SplitPaneLayout(context).apply {
            setPanes(View(context), View(context))
            measure(
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, 1000, 600)
        }
    private val focused = ArrayList<Int>()

    init {
        layout.onPaneTouched = { focused += it }
    }

    private fun hover(
        action: Int,
        x: Float,
        time: Long,
    ): Boolean {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; y = 300f })
        val ev = MotionEvent.obtain(0L, time, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        return try {
            layout.dispatchGenericMotionEvent(ev)
        } finally {
            ev.recycle()
        }
    }

    @Test
    fun `pen hovering over the other pane moves focus after 80 ms`() {
        hover(MotionEvent.ACTION_HOVER_ENTER, 800f, 1_000L)
        hover(MotionEvent.ACTION_HOVER_MOVE, 800f, 1_079L)
        assertEquals(emptyList<Int>(), focused)
        hover(MotionEvent.ACTION_HOVER_MOVE, 800f, 1_081L)
        assertEquals(listOf(1), focused)
    }

    @Test
    fun `hovering over the focused pane changes nothing`() {
        hover(MotionEvent.ACTION_HOVER_ENTER, 100f, 1_000L)
        hover(MotionEvent.ACTION_HOVER_MOVE, 100f, 1_500L)
        assertEquals(emptyList<Int>(), focused)
    }

    @Test
    fun `leaving hover range forgets the pending switch`() {
        hover(MotionEvent.ACTION_HOVER_ENTER, 800f, 1_000L)
        hover(MotionEvent.ACTION_HOVER_EXIT, 800f, 1_040L)
        hover(MotionEvent.ACTION_HOVER_ENTER, 800f, 1_060L)
        hover(MotionEvent.ACTION_HOVER_MOVE, 800f, 1_100L)
        assertEquals(emptyList<Int>(), focused)
    }
}
