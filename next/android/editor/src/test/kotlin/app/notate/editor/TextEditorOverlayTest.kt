package app.notate.editor

import android.content.Context
import android.graphics.Color
import android.widget.EditText
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import app.notate.core.doc.tools.TextLayoutMetrics
import app.notate.core.doc.tools.TextStyle
import app.notate.core.geometry.Rect
import app.notate.core.model.ItemBounds
import app.notate.core.model.ItemId
import app.notate.core.model.TextAlign
import app.notate.core.model.TextBox
import app.notate.editor.text.TextEditorOverlay
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TextEditorOverlayTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val parent = FrameLayout(context)
    private val frame = Rect(10f, 20f, 110f, 34f)
    private val box = TextBox(ItemId(1), 1, frame, 0f, "", 10f, Color.BLACK, false, TextAlign.LEFT, ItemBounds.frameBounds(frame, 0f))
    private val results = ArrayList<String>()
    private var scale = 2f

    private fun overlay() = TextEditorOverlay(parent, box, { TextEditorOverlay.Placement(30f, 40f, scale) }, TextLayoutMetrics.Estimate) { results.add(it) }

    @Test fun showAddsAnEditTextAtTheBoxAndFinishReportsTheText() {
        val o = overlay()
        o.show()
        val edit = parent.getChildAt(0) as EditText
        assertThat(edit.x).isEqualTo(30f)
        assertThat(edit.y).isEqualTo(40f)
        assertThat(edit.layoutParams.width).isEqualTo(200)             // 100 pt × 2
        edit.setText("F = ma")
        o.finish()
        assertThat(results).containsExactly("F = ma")
        assertThat(parent.childCount).isEqualTo(0)
    }

    @Test fun finishReportsOnlyOnce() {
        val o = overlay()
        o.show()
        o.finish(); o.finish()
        assertThat(results).hasSize(1)
    }

    @Test fun emptyTextIsReportedAsEmpty() {
        val o = overlay()
        o.show()
        o.finish()
        assertThat(results).containsExactly("")
    }

    @Test fun editorGrowsWithLinesAndFollowsZoom() {
        val o = overlay()
        o.show()
        val edit = parent.getChildAt(0) as EditText
        val one = edit.layoutParams.height
        edit.setText("a\nb\nc")
        assertThat(edit.layoutParams.height).isGreaterThan(one * 2)
        scale = 4f
        o.reposition()
        assertThat(edit.layoutParams.width).isEqualTo(400)
    }

    @Test fun styleChangesApplyToTheEditor() {
        val o = overlay()
        o.show()
        o.applyStyle(TextStyle(20f, true, Color.RED, TextAlign.CENTER))
        val edit = parent.getChildAt(0) as EditText
        assertThat(edit.textSize).isEqualTo(40f)
        assertThat(edit.currentTextColor).isEqualTo(Color.RED)
        assertThat(edit.typeface).isSameInstanceAs(android.graphics.Typeface.DEFAULT_BOLD)
        assertThat(o.currentStyle().align).isEqualTo(TextAlign.CENTER)
    }

    @Test fun discardRemovesWithoutReporting() {
        val o = overlay()
        o.show()
        o.discard()
        assertThat(results).isEmpty()
        assertThat(parent.childCount).isEqualTo(0)
    }
}
