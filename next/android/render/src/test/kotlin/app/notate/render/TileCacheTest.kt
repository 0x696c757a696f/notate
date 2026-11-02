package app.notate.render

import android.graphics.Bitmap
import app.notate.core.geometry.Rect
import app.notate.core.model.PageId
import app.notate.render.tile.TileCache
import app.notate.render.tile.TileGeometry
import app.notate.render.tile.TileKey
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TileCacheTest {
    private val p1 = PageId(1)
    private val p2 = PageId(2)
    private fun key(col: Int, row: Int = 0, page: PageId = p1, level: Int = 0) = TileKey(page, level, col, row)
    private fun bmp() = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)     // 256 bytes
    private val tileBytes = 8L * 8 * 4

    @Test fun evictsLeastRecentlyUsedByBytes() {
        val c = TileCache(maxBytes = tileBytes * 3)
        for (i in 0..2) c.put(key(i), bmp(), 1)
        assertThat(c.sizeBytes).isEqualTo(tileBytes * 3)
        c.get(key(0))                      // touch → key(1) is now the oldest
        c.put(key(3), bmp(), 1)
        assertThat(c.peek(key(1))).isNull()
        assertThat(c.peek(key(0))).isNotNull()
        assertThat(c.peek(key(2))).isNotNull()
        assertThat(c.peek(key(3))).isNotNull()
        assertThat(c.sizeBytes).isEqualTo(tileBytes * 3)
    }

    @Test fun replacingAKeyDoesNotLeakBytes() {
        val c = TileCache(maxBytes = tileBytes * 3)
        c.put(key(0), bmp(), 1)
        c.put(key(0), bmp(), 2)
        assertThat(c.size).isEqualTo(1)
        assertThat(c.sizeBytes).isEqualTo(tileBytes)
        assertThat(c.peek(key(0))!!.rev).isEqualTo(2)
    }

    @Test fun invalidateMarksOnlyIntersectingTilesAndKeepsBitmap() {
        val c = TileCache(maxBytes = tileBytes * 10)
        val size = TileGeometry.sizePt(0)               // 512 pt at level 0
        val old = bmp()
        c.put(key(0, 0), old, 1)
        c.put(key(1, 0), bmp(), 1)
        c.put(key(0, 0, page = p2), bmp(), 1)
        val hit = c.invalidate(p1, Rect(10f, 10f, 20f, 20f))
        assertThat(hit).containsExactly(key(0, 0))
        assertThat(c.peek(key(0, 0))!!.stale).isTrue()
        assertThat(c.peek(key(0, 0))!!.bitmap).isSameInstanceAs(old)    // old image stays until replaced
        assertThat(c.isFresh(key(0, 0))).isFalse()
        assertThat(c.isFresh(key(1, 0))).isTrue()
        assertThat(c.isFresh(key(0, 0, page = p2))).isTrue()
        assertThat(size).isEqualTo(512f)
        // Touching edge is not an intersection.
        assertThat(c.invalidate(p1, Rect(size, 0f, size + 5f, 5f))).containsExactly(key(1, 0))
    }

    @Test fun invalidateNullRectHitsWholePage() {
        val c = TileCache(maxBytes = tileBytes * 10)
        c.put(key(0), bmp(), 1); c.put(key(5, 5), bmp(), 1); c.put(key(0, page = p2), bmp(), 1)
        assertThat(c.invalidate(p1, null)).hasSize(2)
    }

    @Test fun evictPageAndClear() {
        val c = TileCache(maxBytes = tileBytes * 10)
        c.put(key(0), bmp(), 1); c.put(key(0, page = p2), bmp(), 1)
        c.evictPage(p1)
        assertThat(c.size).isEqualTo(1)
        assertThat(c.sizeBytes).isEqualTo(tileBytes)
        c.clear()
        assertThat(c.size).isEqualTo(0)
        assertThat(c.sizeBytes).isEqualTo(0)
    }

    @Test fun defaultBudget() {
        assertThat(TileCache.defaultMaxBytes(512)).isEqualTo(64L * 1024 * 1024)
        assertThat(TileCache.defaultMaxBytes(128)).isEqualTo(32L * 1024 * 1024)
    }

    @Test fun tileGeometryCoversRegion() {
        val keys = TileGeometry.cover(p1, 0, Rect(0f, 0f, 600f, 513f), Rect(0f, 0f, 600f, 800f))
        assertThat(keys.map { it.col to it.row }).containsExactly(0 to 0, 1 to 0, 0 to 1, 1 to 1).inOrder()
        assertThat(TileGeometry.cover(p1, 0, Rect(0f, 0f, 512f, 512f), null)).containsExactly(key(0, 0))
        assertThat(TileGeometry.cover(p1, 0, Rect(-10f, 0f, 10f, 10f), null).map { it.col }).containsExactly(-1, 0)
        assertThat(TileGeometry.cover(p1, 0, Rect(-10f, 0f, 10f, 10f), Rect(0f, 0f, 100f, 100f)).map { it.col }).containsExactly(0)
        val r = TileGeometry.rectOf(key(1, 2, level = 2))          // level 2 = scale 2 → 256 pt tiles
        assertThat(r.l).isWithin(0.01f).of(256f); assertThat(r.t).isWithin(0.01f).of(512f)
        assertThat(r.r).isWithin(0.01f).of(512f); assertThat(r.b).isWithin(0.01f).of(768f)
    }
}
