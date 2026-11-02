package app.notate.render

import app.notate.core.geometry.Rect
import app.notate.core.model.InkStyle
import app.notate.core.model.ItemId
import app.notate.core.model.PenType
import app.notate.core.model.Stroke
import app.notate.core.model.StrokePoints
import app.notate.render.ink.GeometryCache
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class GeometryCacheTest {
    private fun stroke(id: Long, pen: PenType = PenType.FINELINER) = Stroke(
        ItemId(id), id, InkStyle(pen, 0xFF000000.toInt(), 1f),
        StrokePoints(floatArrayOf(0f, 0f, 10f, 10f, 20f, 5f), null, null), Rect(0f, 0f, 20f, 10f),
    )

    @Test fun sameObjectHits() {
        val c = GeometryCache()
        val s = stroke(1)
        assertThat(c.pathFor(s)).isSameInstanceAs(c.pathFor(s))
        assertThat(c.size).isEqualTo(1)
    }

    @Test fun newObjectWithSameIdMisses() {
        val c = GeometryCache()
        val a = stroke(1)
        val b = stroke(1)                       // equal content, different object
        assertThat(c.pathFor(a)).isNotSameInstanceAs(c.pathFor(b))
        assertThat(c.size).isEqualTo(2)
    }

    @Test fun evictsLeastRecentlyUsed() {
        val c = GeometryCache(maxEntries = 2)
        val a = stroke(1); val b = stroke(2); val d = stroke(3)
        val pa = c.pathFor(a)
        c.pathFor(b)
        c.pathFor(a)                            // a is now newest
        c.pathFor(d)                            // evicts b
        assertThat(c.size).isEqualTo(2)
        assertThat(c.pathFor(a)).isSameInstanceAs(pa)
        val sizeBefore = c.size
        c.pathFor(b)                            // rebuilt (miss) → evicts d or a, size stays at max
        assertThat(c.size).isEqualTo(sizeBefore)
    }

    @Test fun buildsEveryPenType() {
        val c = GeometryCache()
        for (pen in PenType.entries) {
            val g = c.pathFor(stroke(pen.code.toLong() + 10, pen))
            assertThat(g.fill != null || g.strokes.isNotEmpty() || g.dots != null).isTrue()
        }
    }
}
