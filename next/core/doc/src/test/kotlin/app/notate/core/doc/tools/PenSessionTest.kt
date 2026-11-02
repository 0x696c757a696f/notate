package app.notate.core.doc.tools

import app.notate.core.model.PenType
import app.notate.core.model.Layer
import app.notate.core.model.Stroke
import app.notate.core.model.ToolKind
import app.notate.core.model.ToolPreset
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PenSessionTest {
    private val pen = ToolPreset(ToolKind.PEN, pen = PenType.BALLPOINT, color = 0xFF000000.toInt(), widthPt = 1f)

    @Test fun commitsOneSimplifiedStroke() = runTest {
        val r = ToolRig(this)
        val s = PenSession(pen, r.ctx)
        s.begin(ip(10f, 10f, 0))
        // 40 points on a straight line: simplification must collapse the interior.
        s.move((1..40).map { ip(10f + it * 2f, 10f, it * 5L) })
        s.end(ip(92f, 10f, 205))
        val stroke = r.items().single() as Stroke
        assertThat(stroke.points.size).isAtMost(5)
        assertThat(stroke.points.size).isAtLeast(2)
        assertThat(r.editor.history.canUndo.value).isTrue()
        assertThat(r.preview.shown.first()).isInstanceOf(Preview.LiveStroke::class.java)
        assertThat(r.preview.cleared).isEqualTo(1)
    }

    @Test fun cancelCommitsNothing() = runTest {
        val r = ToolRig(this)
        val s = PenSession(pen, r.ctx)
        s.begin(ip(1f, 1f)); s.move(listOf(ip(20f, 20f, 10))); s.cancel()
        assertThat(r.items()).isEmpty()
        assertThat(r.preview.cleared).isEqualTo(1)
    }

    @Test fun singleTapIsADot() = runTest {
        val r = ToolRig(this)
        val s = PenSession(pen, r.ctx)
        s.begin(ip(5f, 5f)); s.end(ip(5f, 5f, 10))
        assertThat((r.items().single() as Stroke).points.size).isEqualTo(1)
    }

    @Test fun highlighterGoesToHighlightLayer() = runTest {
        val r = ToolRig(this)
        val s = PenSession(ToolPreset(ToolKind.HIGHLIGHTER, color = 0x80FFE14D.toInt(), widthPt = 8f), r.ctx)
        s.begin(ip(1f, 1f)); s.move(listOf(ip(60f, 3f, 10))); s.end(ip(80f, 3f, 20))
        val st = r.items().single() as Stroke
        assertThat(st.layer).isEqualTo(Layer.HIGHLIGHT)
        assertThat(st.style.pen).isEqualTo(PenType.HIGHLIGHTER)
    }
}
