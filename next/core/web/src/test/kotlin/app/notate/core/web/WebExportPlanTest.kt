package app.notate.core.web

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WebExportPlanTest {
    @Test fun veryShortPageIsOneSlice() {
        val s = WebExportPlan.slices(contentHeightPx = 300, viewportHeightPx = 1000)
        assertThat(s).containsExactly(WebExportPlan.Slice(0, 0, 1000))
    }

    @Test fun exactMultipleHasNoExtraSlice() {
        val s = WebExportPlan.slices(2000, 1000)
        assertThat(s.map { it.scrollY }).containsExactly(0, 1000).inOrder()
        assertThat(s.sumOf { it.height }).isEqualTo(2000)
    }

    @Test fun lastSliceDoesNotRepeatContent() {
        val s = WebExportPlan.slices(2500, 1000)
        assertThat(s).hasSize(3)
        val last = s.last()
        assertThat(last.scrollY).isEqualTo(1500)      // cannot scroll further than content - viewport
        assertThat(last.srcTop).isEqualTo(500)        // skip the 500 rows slice 2 already covered
        assertThat(last.height).isEqualTo(500)
        // Every content row is covered exactly once.
        val rows = s.flatMap { (it.scrollY + it.srcTop) until (it.scrollY + it.srcTop + it.height) }
        assertThat(rows).containsExactlyElementsIn(0 until 2500).inOrder()
    }

    @Test fun respectsMaxSlicesAndBadViewport() {
        assertThat(WebExportPlan.slices(1_000_000, 100, maxSlices = 5)).hasSize(5)
        assertThat(WebExportPlan.slices(1000, 0)).isEmpty()
    }

    @Test fun heightStable() {
        assertThat(WebExportPlan.heightStable(1200, 1200)).isTrue()
        assertThat(WebExportPlan.heightStable(1200, 1300)).isFalse()
        assertThat(WebExportPlan.heightStable(0, 0)).isFalse()
    }
}
