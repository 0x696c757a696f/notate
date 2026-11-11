package app.notate.core.doc.export

import app.notate.core.doc.export.PageRangeParser.Result
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PageRangeParserTest {
    private fun ok(spec: String, n: Int = 10) = (PageRangeParser.parse(spec, n) as Result.Ok).indexes

    @Test fun singleRangesAndLists() {
        assertThat(ok("1-3, 5, 8-")).containsExactly(0, 1, 2, 4, 7, 8, 9).inOrder()
        assertThat(ok("2")).containsExactly(1)
        assertThat(ok("-2")).containsExactly(0, 1).inOrder()
    }

    @Test fun duplicatesAreMergedAndOrderIsAscending() {
        assertThat(ok("5, 1-3, 2-4")).containsExactly(0, 1, 2, 3, 4).inOrder()
    }

    @Test fun numbersBeyondTheEndAreClipped() {
        assertThat(ok("8-20")).containsExactly(7, 8, 9).inOrder()
        assertThat(ok("3, 99")).containsExactly(2)
    }

    @Test fun invalidInputGivesTheMessage() {
        for (bad in listOf("", "  ", "abc", "0", "3-1", "-", "1--3", "1-2-3", "99", "1.5", "-0")) {
            val r = PageRangeParser.parse(bad, 10)
            assertThat(r).isEqualTo(Result.Error("Use page numbers like 1-3, 5"))
        }
        assertThat(PageRangeParser.parse("1", 0)).isInstanceOf(Result.Error::class.java)
    }
}
