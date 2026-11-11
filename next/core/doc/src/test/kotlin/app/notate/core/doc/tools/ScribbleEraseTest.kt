package app.notate.core.doc.tools

import app.notate.core.model.PenType
import app.notate.core.model.Stroke
import app.notate.core.model.ToolKind
import app.notate.core.model.ToolPreset
import app.notate.core.testing.stroke
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ScribbleEraseTest {
    private val pen = ToolPreset(ToolKind.PEN, pen = PenType.BALLPOINT, color = 0xFF000000.toInt(), widthPt = 1f)
    private val hl = ToolPreset(ToolKind.HIGHLIGHTER, color = 0xFFFFFF00.toInt(), widthPt = 8f)

    // horizontal word-like stroke at y=100, x 0..100
    private fun word(id: Long = 1) = stroke(id, points = floatArrayOf(0f, 100f, 100f, 100f))

    /** Zig-zag over x 10..90 (passes of [h] pt tall centred on [cy]), [passes] passes, 20 ms per ~10 pt. */
    private fun scribble(cy: Float, h: Float, passes: Int, x0: Float = 10f, dx: Float = 8f) = buildList {
        var t = 0L
        var x = x0
        for (k in 0 until passes) {
            val up = k % 2 == 0
            add(x to (if (up) cy - h / 2 else cy + h / 2))
            x += dx
        }
        // dense samples between corners so the 8 pt spacing filter keeps the corners
    }.let { corners ->
        val pts = ArrayList<Triple<Float, Float, Long>>()
        var t = 0L
        for (i in 0 until corners.size - 1) {
            val (ax, ay) = corners[i]; val (bx, by) = corners[i + 1]
            for (s in 0 until 6) { pts.add(Triple(ax + (bx - ax) * s / 6f, ay + (by - ay) * s / 6f, t)); t += 4 }
        }
        pts.add(Triple(corners.last().first, corners.last().second, t))
        pts
    }

    private fun run(r: ToolRig, preset: ToolPreset, pts: List<Triple<Float, Float, Long>>, erase: Boolean = true) {
        val ctx = ToolContext(r.editor, r.p1, null, 1f, r.preview, ToolSettings(600, 5f, scribbleErase = erase), r.clock)
        val s = PenSession(preset, ctx)
        s.begin(ip(pts[0].first, pts[0].second, pts[0].third))
        s.move(pts.drop(1).dropLast(1).map { ip(it.first, it.second, it.third) })
        s.end(pts.last().let { ip(it.first, it.second, it.third) })
    }

    @Test fun scribbleOverInkErasesItInOneUndoableEdit() = runTest {
        val r = ToolRig(this, seed = listOf(word(1), stroke(2, points = floatArrayOf(0f, 300f, 100f, 300f))))
        run(r, pen, scribble(cy = 100f, h = 60f, passes = 9))
        assertThat(r.items().map { it.id.value }).containsExactly(2L)   // the scribble itself is not kept
        r.editor.undo()
        assertThat(r.items().map { it.id.value }).containsExactly(1L, 2L)
    }

    @Test fun scribbleOverNothingIsKeptAsInk() = runTest {
        val r = ToolRig(this, seed = listOf(word(1)))
        run(r, pen, scribble(cy = 400f, h = 60f, passes = 9))
        assertThat(r.items().filterIsInstance<Stroke>()).hasSize(2)
    }

    @Test fun hatchingInsideAnOutlineIsNotAnErase() = runTest {
        // Two long outline strokes at y=70 and y=130; the passes run between them and only meet them at the turns.
        val r = ToolRig(this, seed = listOf(
            stroke(1, points = floatArrayOf(0f, 70f, 100f, 70f)), stroke(2, points = floatArrayOf(0f, 130f, 100f, 130f)),
        ))
        run(r, pen, scribble(cy = 100f, h = 60f, passes = 9))
        assertThat(r.items().filterIsInstance<Stroke>()).hasSize(3)
    }

    @Test fun offByDefaultSettingKeepsTheStroke() = runTest {
        val r = ToolRig(this, seed = listOf(word(1)))
        run(r, pen, scribble(cy = 100f, h = 60f, passes = 9), erase = false)
        assertThat(r.items().filterIsInstance<Stroke>()).hasSize(2)
    }

    @Test fun highlighterNeverScribbleErases() = runTest {
        val r = ToolRig(this, seed = listOf(word(1)))
        run(r, hl, scribble(cy = 100f, h = 60f, passes = 9))
        assertThat(r.items().filterIsInstance<Stroke>()).hasSize(2)
    }

    @Test fun ordinaryHandwritingStaysInk() = runTest {
        val r = ToolRig(this, seed = listOf(word(1)))
        // a gentle wave with round turns, never reversing sharply
        val pts = (0..60).map { Triple(10f + it * 1.5f, 100f + 8f * kotlin.math.sin(it / 6f), it * 8L) }
        run(r, pen, pts)
        assertThat(r.items().filterIsInstance<Stroke>()).hasSize(2)
    }
}
