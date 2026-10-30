package com.alexdremov.notate.ocr

import android.graphics.RectF
import com.alexdremov.notate.ocr.index.OcrBlockDeduplicator
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OcrGeometryTest {
    private fun block(text: String, confidence: Float, bounds: RectF) =
        OcrBlock(text, confidence, floatArrayOf(bounds.left, bounds.top, bounds.right, bounds.top, bounds.right, bounds.bottom, bounds.left, bounds.bottom), bounds)

    @Test
    fun tileMappingReturnsCanvasCoordinates() {
        val raster = OcrRaster(android.graphics.Bitmap.createBitmap(100, 100, android.graphics.Bitmap.Config.ARGB_8888), RectF(100f, 200f, 300f, 400f), 0.5f)
        val mapped = raster.toWorld(block("text", 0.8f, RectF(10f, 20f, 50f, 40f)))
        assertThat(mapped.bounds).isEqualTo(RectF(120f, 240f, 200f, 280f))
        raster.bitmap.recycle()
    }

    @Test
    fun overlappingTileResultsKeepHigherConfidence() {
        val result = OcrBlockDeduplicator.deduplicate(
            listOf(
                block("笔记", 0.7f, RectF(10f, 10f, 100f, 40f)),
                block("笔记", 0.9f, RectF(15f, 10f, 105f, 40f)),
            ),
        )
        assertThat(result).hasSize(1)
        assertThat(result.single().confidence).isEqualTo(0.9f)
    }

    @Test
    fun lineOrderingIsTopThenLeft() {
        val text = OcrConversionPlanner.orderedText(
            listOf(
                block("right", 1f, RectF(100f, 0f, 150f, 20f)),
                block("next", 1f, RectF(0f, 40f, 50f, 60f)),
                block("left", 1f, RectF(0f, 2f, 50f, 22f)),
            ),
        )
        assertThat(text).isEqualTo("left\nright\nnext")
    }

    @Test
    fun fixedPagePlacementFallsBackAboveSelection() {
        val y = OcrConversionPlanner.insertionY(RectF(40f, 900f, 400f, 940f), "one\ntwo", 24f, 1000f, 32f)
        assertThat(y).isLessThan(900f)
        assertThat(y).isAtLeast(0f)
    }
}
