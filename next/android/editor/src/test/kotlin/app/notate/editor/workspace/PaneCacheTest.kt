package app.notate.editor.workspace

import app.notate.core.model.DocId
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PaneCacheTest {
    private class Fake(val name: String) { var saved = false }

    private val evicted = ArrayList<String>()
    private fun cache(max: Int = 3) = PaneCache<Fake>(max) { _, p -> p.saved = true; evicted += p.name }
    private fun id(n: String) = DocId(n)

    @Test fun aHitReturnsTheSamePaneWithoutBuildingANewOne() {
        val c = cache()
        val a = Fake("a")
        c.put(id("a"), a)
        assertThat(c[id("a")]).isSameInstanceAs(a)
        assertThat(c[id("a")]).isSameInstanceAs(a)
        assertThat(c.size).isEqualTo(1)
        assertThat(evicted).isEmpty()
    }

    @Test fun aFourthPaneReleasesTheLeastRecentlyUsedOne() {
        val c = cache()
        val panes = listOf("a", "b", "c").map { Fake(it).also { p -> c.put(id(it), p) } }
        c[id("a")]                                  // a is fresh again, b is now the oldest
        c.put(id("d"), Fake("d"))
        assertThat(evicted).containsExactly("b")
        assertThat(panes[1].saved).isTrue()
        assertThat(c.peek(id("b"))).isNull()
        assertThat(c.size).isEqualTo(3)
    }

    @Test fun panesInKeepAreNeverReleased() {
        val c = cache(2)
        c.put(id("a"), Fake("a"))
        c.put(id("b"), Fake("b"))
        c.put(id("c"), Fake("c"), keep = setOf(id("a")))
        assertThat(evicted).containsExactly("b")
        assertThat(c.peek(id("a"))).isNotNull()
    }

    @Test fun removeDoesNotCallTheEvictCallback() {
        val c = cache()
        val a = Fake("a")
        c.put(id("a"), a)
        assertThat(c.remove(id("a"))).isSameInstanceAs(a)
        assertThat(evicted).isEmpty()
        assertThat(c[id("a")]).isNull()
    }

    @Test fun peekDoesNotChangeTheOrder() {
        val c = cache(2)
        c.put(id("a"), Fake("a"))
        c.put(id("b"), Fake("b"))
        c.peek(id("a"))
        c.put(id("c"), Fake("c"))
        assertThat(evicted).containsExactly("a")
    }
}
