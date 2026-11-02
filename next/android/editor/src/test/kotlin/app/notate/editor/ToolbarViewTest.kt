package app.notate.editor

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import app.notate.core.doc.settings.Settings
import app.notate.core.doc.tools.ToolController
import app.notate.core.input.EpdScheduler
import app.notate.core.input.InkArbiter
import app.notate.core.model.Capabilities
import app.notate.core.model.DocKind
import app.notate.core.model.EraserMode
import app.notate.core.model.ToolKind
import app.notate.core.testing.InMemoryKeyValueStore
import app.notate.editor.popup.PopupHost
import app.notate.editor.toolbar.ToolButton
import app.notate.editor.toolbar.ToolbarActions
import app.notate.editor.toolbar.ToolbarView
import app.notate.platform.epd.NoEpdPort
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ToolbarViewTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val controller = ToolController(Settings(InMemoryKeyValueStore()))
    private val arbiter = InkArbiter()
    private val popups = PopupHost(FrameLayout(context), arbiter, EpdScheduler(NoEpdPort))
    private val actions = object : ToolbarActions {
        override fun onUndo() = Unit
        override fun onRedo() = Unit
        override fun onPages() = Unit
        override fun onSplit() = Unit
        override fun onMore(anchor: View) = Unit
    }
    private val toolbar = ToolbarView(context, controller, popups, 227f, actions)

    private fun descriptions(v: View = toolbar, out: MutableList<String> = ArrayList()): List<String> {
        v.contentDescription?.let { out.add(it.toString()) }
        if (v is ViewGroup) for (i in 0 until v.childCount) descriptions(v.getChildAt(i), out)
        return out
    }

    private fun button(desc: String): ToolButton = find(toolbar, desc) as ToolButton

    private fun find(v: View, desc: String): View? {
        if (v.contentDescription?.toString() == desc) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), desc)?.let { return it }
        return null
    }

    private fun select(kind: ToolKind) {
        controller.select(kind)
        toolbar.render(controller.baseState())
    }

    @Test fun optionsFollowTheActiveTool() {
        assertThat(descriptions()).containsAtLeast("Pen settings", "Color 1", "Thickness 3")
        select(ToolKind.ERASER)
        val eraser = descriptions()
        assertThat(eraser).containsAtLeast("Partial eraser", "Stroke eraser", "Lasso eraser", "Eraser size 3", "Highlighter only")
        assertThat(eraser).doesNotContain("Color 1")
        assertThat(button("Eraser").isSelected).isTrue()
        assertThat(button("Pen").isSelected).isFalse()
        select(ToolKind.HAND)
        assertThat(descriptions()).doesNotContain("Eraser size 3")
        assertThat(button("Hand").isSelected).isTrue()
    }

    @Test fun inlineOptionsEditTheSharedState() {
        select(ToolKind.ERASER)
        button("Stroke eraser").performClick()
        assertThat(controller.state.value.eraserMode).isEqualTo(EraserMode.STROKE)
        find(toolbar, "Eraser size 1")!!.performClick()
        assertThat(controller.state.value.eraserSize).isEqualTo(0)
        select(ToolKind.PEN)
        find(toolbar, "Color 3")!!.performClick()
        assertThat(controller.state.value.pen.color).isEqualTo(2)
    }

    @Test fun unsupportedToolsAreGreyedWithAReason() {
        toolbar.bind(Capabilities.forKind(DocKind.WEB), "Not available on books")
        assertThat(button("Lasso").reason).isEqualTo("Not available on books")
        assertThat(button("Text").available).isFalse()
        assertThat(button("Pen").available).isTrue()
        assertThat(button("Hand").available).isTrue()
        assertThat(button("Pages").available).isFalse()
        toolbar.bind(Capabilities.forKind(DocKind.NOTEBOOK))
        assertThat(button("Lasso").available).isTrue()
    }

    @Test fun activeToolThatBecomesUnavailableFallsBackToPen() {
        select(ToolKind.LASSO)
        toolbar.bind(Capabilities.forKind(DocKind.WEB), "Not available on books")
        assertThat(controller.baseState().active).isEqualTo(ToolKind.PEN)
    }

    @Test fun undoRedoStateFollowsTheHistory() {
        toolbar.setUndoRedo(canUndo = false, canRedo = true)
        assertThat(button("Undo").available).isFalse()
        assertThat(button("Redo").available).isTrue()
        toolbar.setUndoRedo(canUndo = true, canRedo = false)
        assertThat(button("Undo").available).isTrue()
        assertThat(button("Redo").available).isFalse()
    }
}
