package app.notate.core.doc.zoom

import app.notate.core.geometry.Rect
import app.notate.core.model.PageId
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ZoomWindowStateTest {
    private val frame = Rect(0f, 0f, 600f, 800f)
    private val margin = 30f

    /** Writing area 900×300 px at 1.5 px/pt → 600 pt at 1×, 200 pt at 3×; box 200 × 66.7 pt. */
    private fun state(spacing: Float? = null, mag: Float = 3f) =
        ZoomWindowState(PageId(1), frame, baseWidthPt = 600f, aspect = 3f, lineSpacingPt = spacing, marginPt = margin, magnification = mag)

    private fun assertRect(actual: Rect, l: Float, t: Float, r: Float, b: Float) {
        assertThat(actual.l).isWithin(0.01f).of(l)
        assertThat(actual.t).isWithin(0.01f).of(t)
        assertThat(actual.r).isWithin(0.01f).of(r)
        assertThat(actual.b).isWithin(0.01f).of(b)
    }

    @Test fun startsAtTheTopLeftMargin() {
        val s = state()
        assertRect(s.box, 30f, 30f, 230f, 30f + 200f / 3f)
    }

    @Test fun writingCornersMapToBoxCorners() {
        val s = state()
        s.moveTo(100f, 200f)
        val m = s.writingToPage(900f, 300f)
        val tl = m.map(0f, 0f)
        val br = m.map(900f, 300f)
        val tr = m.map(900f, 0f)
        val bl = m.map(0f, 300f)
        assertThat(tl.x).isWithin(0.01f).of(s.box.l); assertThat(tl.y).isWithin(0.01f).of(s.box.t)
        assertThat(br.x).isWithin(0.01f).of(s.box.r); assertThat(br.y).isWithin(0.01f).of(s.box.b)
        assertThat(tr.x).isWithin(0.01f).of(s.box.r); assertThat(tr.y).isWithin(0.01f).of(s.box.t)
        assertThat(bl.x).isWithin(0.01f).of(s.box.l); assertThat(bl.y).isWithin(0.01f).of(s.box.b)
        // The middle of the writing area is the middle of the box.
        val c = m.map(450f, 150f)
        assertThat(c.x).isWithin(0.01f).of(s.box.center.x)
        assertThat(c.y).isWithin(0.01f).of(s.box.center.y)
    }

    @Test fun magnificationChangesTheBoxWidthAndKeepsTheCorner() {
        val s = state()
        s.moveTo(50f, 60f)
        s.setMagnification(4f)
        assertThat(s.box.width).isWithin(0.01f).of(150f)
        assertThat(s.box.height).isWithin(0.01f).of(50f)
        assertThat(s.box.l).isEqualTo(50f)
        assertThat(s.box.t).isEqualTo(60f)
        s.setMagnification(2f)
        assertThat(s.box.width).isWithin(0.01f).of(300f)
    }

    @Test fun magnificationIsClamped() {
        val s = state()
        s.setMagnification(9f)
        assertThat(s.magnification).isEqualTo(5f)
        s.setMagnification(0.5f)
        assertThat(s.magnification).isEqualTo(2f)
    }

    @Test fun stepMovesByAFractionOfTheBox() {
        val s = state()
        s.moveTo(100f, 100f)
        s.step(0.5f)
        assertThat(s.box.l).isWithin(0.01f).of(200f)
        s.step(-1f)
        assertThat(s.box.l).isWithin(0.01f).of(0f)
        assertThat(s.box.t).isEqualTo(100f)
    }

    @Test fun theBoxStaysOnThePage() {
        val s = state()
        s.moveTo(-50f, -50f)
        assertRect(s.box, 0f, 0f, 200f, 200f / 3f)
        s.moveTo(5000f, 5000f)
        assertThat(s.box.r).isEqualTo(600f)
        assertThat(s.box.b).isWithin(0.01f).of(800f)
    }

    @Test fun aStrokeInTheLeftPartDoesNotMoveTheBox() {
        val s = state()
        val before = s.box
        assertThat(s.advanceAfterStroke(Rect(40f, 40f, 150f, 60f))).isFalse()
        assertThat(s.box).isEqualTo(before)
    }

    @Test fun aStrokeEndingPast85PercentMovesTheBoxBy60Percent() {
        val s = state()
        val l0 = s.box.l
        val w = s.box.width
        // 85 % of the box is at l0 + 170.
        assertThat(s.advanceAfterStroke(Rect(l0 + 100f, 40f, l0 + 0.9f * w, 60f))).isTrue()
        assertThat(s.box.l).isWithin(0.01f).of(l0 + 0.6f * w)
        assertThat(s.box.t).isEqualTo(30f)
    }

    @Test fun exactlyAtTheThresholdMoves() {
        val s = state()
        assertThat(s.advanceAfterStroke(Rect(100f, 40f, s.box.l + 0.85f * s.box.width, 60f))).isTrue()
    }

    @Test fun reachingTheRightMarginStartsANewLine() {
        val s = state()
        s.moveTo(400f, 100f)                    // box 400..600, the page's right edge
        val moved = s.advanceAfterStroke(Rect(500f, 110f, 590f, 130f))
        assertThat(moved).isTrue()
        assertThat(s.box.l).isEqualTo(margin)
        assertThat(s.box.t).isWithin(0.01f).of(100f + s.lineHeightPt)
    }

    @Test fun aStrokeAtTheMarginStartsANewLineEvenIfTheBoxCouldStillMove() {
        val s = state()
        s.moveTo(300f, 100f)                    // box 300..500
        assertThat(s.advanceAfterStroke(Rect(400f, 110f, 575f, 130f))).isTrue()
        assertThat(s.box.l).isEqualTo(margin)
        assertThat(s.box.t).isGreaterThan(100f)
    }

    @Test fun lineHeightIsTheRulingWhenThePaperHasOne() {
        val s = state(spacing = 20f)
        s.moveTo(200f, 100f)
        s.newLine()
        assertThat(s.box.l).isEqualTo(margin)
        assertThat(s.box.t).isWithin(0.01f).of(120f)
    }

    @Test fun lineHeightWithoutRulingIs80PercentOfTheBox() {
        val s = state()
        s.moveTo(200f, 100f)
        val h = s.box.height
        s.newLine()
        assertThat(s.box.t).isWithin(0.01f).of(100f + 0.8f * h)
    }

    @Test fun newLineAtTheBottomStaysOnThePage() {
        val s = state(spacing = 20f)
        s.moveTo(200f, 5000f)
        s.newLine()
        assertThat(s.box.b).isWithin(0.01f).of(800f)
        assertThat(s.box.l).isEqualTo(margin)
    }

    @Test fun resizeKeepsTheCornerAndChangesTheSize() {
        val s = state()
        s.moveTo(100f, 100f)
        s.resize(baseWidthPt = 300f, aspect = 2f)       // box 100 × 50
        assertRect(s.box, 100f, 100f, 200f, 150f)
    }

    @Test fun switchingPageStartsAtTheMargin() {
        val s = state()
        s.moveTo(300f, 300f)
        s.switchPage(PageId(2), Rect(0f, 0f, 500f, 700f), 18f)
        assertThat(s.page).isEqualTo(PageId(2))
        assertRect(s.box, 30f, 30f, 230f, 30f + 200f / 3f)
        assertThat(s.lineHeightPt).isEqualTo(18f)
    }

    @Test fun anEmptyStrokeDoesNothing() {
        val s = state()
        assertThat(s.advanceAfterStroke(Rect.EMPTY)).isFalse()
    }
}
