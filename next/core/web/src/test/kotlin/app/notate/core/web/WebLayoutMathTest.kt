package app.notate.core.web

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WebLayoutMathTest {
    private val viewport = 1000f * 1500f
    private fun c(id: Int, scrollH: Float, clientH: Float, overflow: String = "visible", area: Float = 0f, root: Boolean = false) =
        ScrollCandidate(id, scrollH, clientH, overflow, area, root)

    @Test fun `root wins when the window scrolls`() {
        assertThat(ScrollCandidate.pick(listOf(c(0, 5000f, 1500f, root = true), c(1, 4000f, 1500f, "auto", viewport)), viewport)).isEqualTo(0)
    }

    @Test fun `scrolling body is picked when the window does not`() {
        assertThat(ScrollCandidate.pick(listOf(c(0, 1500f, 1500f, root = true), c(1, 6000f, 1500f, "auto", viewport)), viewport)).isEqualTo(1)
    }

    @Test fun `largest scrollable wins and tiny ones are ignored`() {
        val root = c(0, 1500f, 1500f, root = true)
        val small = c(1, 900f, 100f, "auto", 100f * 200f)
        val main = c(2, 4000f, 1400f, "scroll", 900f * 1400f)
        val bigger = c(3, 4000f, 1500f, "auto", 1000f * 1500f)
        assertThat(ScrollCandidate.pick(listOf(root, small, main, bigger), viewport)).isEqualTo(3)
        assertThat(ScrollCandidate.pick(listOf(root, small, main), viewport)).isEqualTo(2)
    }

    @Test fun `non overflowing or hidden elements are not scrollers`() {
        val root = c(0, 1500f, 1500f, root = true)
        assertThat(ScrollCandidate.pick(listOf(root, c(1, 1500f, 1500f, "auto", viewport), c(2, 5000f, 1500f, "hidden", viewport)), viewport)).isEqualTo(0)
        assertThat(ScrollCandidate.pick(emptyList(), viewport)).isNull()
    }

    @Test fun `initial width is the pane width capped at 900 and floored at 320`() {
        assertThat(WebLayoutMath.initialWidth(600f)).isEqualTo(600f)
        assertThat(WebLayoutMath.initialWidth(1600f)).isEqualTo(900f)
        assertThat(WebLayoutMath.initialWidth(100f)).isEqualTo(320f)
    }

    @Test fun `reflow clamps and drift threshold is five percent`() {
        assertThat(WebLayoutMath.reflowWidth(50f)).isEqualTo(320f)
        assertThat(WebLayoutMath.reflowWidth(2000f)).isEqualTo(900f)
        assertThat(WebLayoutMath.differs(500f, 505f)).isFalse()
        assertThat(WebLayoutMath.differs(500f, 530f)).isTrue()
        assertThat(WebLayoutMath.differs(0f, 530f)).isFalse()
    }
}
