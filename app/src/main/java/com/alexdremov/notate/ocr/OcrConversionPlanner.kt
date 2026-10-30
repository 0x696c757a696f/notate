package com.alexdremov.notate.ocr

import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.floor

object OcrConversionPlanner {
    fun orderedText(blocks: List<OcrBlock>): String {
        val lines = mutableListOf<MutableList<OcrBlock>>()
        blocks.sortedBy { it.bounds.top }.forEach { block ->
            val line = lines.lastOrNull()
            val anchor = line?.firstOrNull()
            val tolerance = if (anchor == null) 0f else maxOf(anchor.bounds.height(), block.bounds.height()) * 0.55f
            if (anchor != null && abs(anchor.bounds.centerY() - block.bounds.centerY()) <= tolerance) {
                line += block
            } else {
                lines += mutableListOf(block)
            }
        }
        return lines.flatMap { line -> line.sortedBy { it.bounds.left } }.joinToString("\n") { it.text }.trim()
    }

    fun insertionY(
        selection: RectF,
        text: String,
        fontSize: Float,
        fixedPageHeight: Float?,
        pageSpacing: Float,
        gap: Float = 24f,
    ): Float {
        val estimatedHeight = text.lineSequence().count().coerceAtLeast(1) * fontSize * 1.25f
        val below = selection.bottom + gap
        val pageHeight = fixedPageHeight?.takeIf { it > 0f } ?: return below
        val pageStride = pageHeight + pageSpacing
        val pageTop = floor(selection.centerY() / pageStride) * pageStride
        val pageBottom = pageTop + pageHeight
        return if (below + estimatedHeight <= pageBottom) below else (selection.top - gap - estimatedHeight).coerceAtLeast(pageTop)
    }
}
