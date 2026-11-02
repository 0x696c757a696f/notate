package app.notate.render

import android.graphics.Bitmap
import app.notate.core.model.AssetId
import app.notate.render.image.AssetFiles
import app.notate.render.image.ImageCache
import app.notate.render.image.ImageDecoder
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ImageCacheTest {
    private class Fake(val w: Int = 2000, val h: Int = 1000) : ImageDecoder {
        val decoded = ArrayList<Int>()
        override fun bounds(file: File) = intArrayOf(w, h)
        override fun decode(file: File, sampleSize: Int): Bitmap {
            decoded.add(sampleSize)
            return Bitmap.createBitmap(w / sampleSize, h / sampleSize, Bitmap.Config.ARGB_8888)
        }
    }

    private val queue = ArrayList<Runnable>()
    private val manual = Executor { queue.add(it) }
    private fun drain() { while (queue.isNotEmpty()) queue.removeAt(0).run() }
    private val a = AssetId("A")
    private val files = AssetFiles { File("/x/$it") }

    @Test fun sampleSizeStaysAtOrAboveTarget() {
        assertThat(ImageCache.sampleFor(4000, 1000f)).isEqualTo(4)
        assertThat(ImageCache.sampleFor(4000, 1100f)).isEqualTo(2)
        assertThat(ImageCache.sampleFor(500, 1000f)).isEqualTo(1)
        assertThat(ImageCache.sampleFor(4000, 0f)).isEqualTo(1)
    }

    @Test fun firstGetReturnsNullThenLoadsAndNotifies() {
        val loaded = ArrayList<AssetId>()
        val c = ImageCache(files, decoder = Fake(), io = manual, onLoaded = { loaded.add(it) })
        assertThat(c.get(a, 500f)).isNull()
        drain()
        assertThat(loaded).containsExactly(a)
        assertThat(c.get(a, 500f)).isNotNull()
        assertThat(c.pixelSize(a)).asList().containsExactly(2000, 1000).inOrder()
    }

    @Test fun biggerTargetDecodesAgainAndServesCoarseOneMeanwhile() {
        val d = Fake()
        val c = ImageCache(files, decoder = d, io = manual)
        c.get(a, 600f); drain()
        val coarse = c.get(a, 600f)
        val fallback = c.get(a, 2000f)                 // needs sample 1; asks for it
        assertThat(fallback).isSameInstanceAs(coarse)
        drain()
        assertThat(c.get(a, 2000f)!!.width).isEqualTo(2000)
    }

    @Test fun duplicateRequestsDecodeOnce() {
        val d = Fake()
        val c = ImageCache(files, decoder = d, io = manual)
        c.get(a, 600f); c.get(a, 600f); c.get(a, 600f)
        drain()
        assertThat(d.decoded).hasSize(1)
    }

    @Test fun evictsLeastRecentlyUsedBeyondBudget() {
        val d = Fake(1000, 1000)                       // 4 MB each at sample 1
        val c = ImageCache(files, maxBytes = 9L shl 20, decoder = d, io = manual)
        val ids = listOf("A", "B", "C").map { AssetId(it) }
        for (id in ids) { c.get(id, 1000f); drain(); c.get(id, 1000f); drain() }
        assertThat(c.cachedBytes()).isAtMost(9L shl 20)
    }

    @Test fun unreadableFileKeepsPlaceholder() {
        val bad = object : ImageDecoder {
            override fun bounds(file: File): IntArray? = null
            override fun decode(file: File, sampleSize: Int): Bitmap? = null
        }
        val c = ImageCache(files, decoder = bad, io = manual)
        assertThat(c.get(a, 100f)).isNull()
        drain()
        assertThat(c.get(a, 100f)).isNull()
        assertThat(queue).isEmpty()
    }
}
