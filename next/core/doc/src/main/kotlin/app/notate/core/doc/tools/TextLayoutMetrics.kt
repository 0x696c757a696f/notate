package app.notate.core.doc.tools

/** Measures wrapped text so a text box can grow with its content. Implemented on Android with `StaticLayout`. */
interface TextLayoutMetrics {
    /** Height in pt of [text] wrapped to [widthPt] at [fontSizePt]; an empty text measures one line. Any thread. */
    fun measure(text: String, fontSizePt: Float, bold: Boolean, widthPt: Float): Float

    /** Rough fallback (average glyph width 0.5 em, line height 1.2 em); also the test double. */
    object Estimate : TextLayoutMetrics {
        override fun measure(text: String, fontSizePt: Float, bold: Boolean, widthPt: Float): Float {
            val perLine = (widthPt / (fontSizePt * (if (bold) 0.55f else 0.5f))).toInt().coerceAtLeast(1)
            var lines = 0
            for (line in text.split('\n')) lines += ((line.length + perLine - 1) / perLine).coerceAtLeast(1)
            return lines.coerceAtLeast(1) * fontSizePt * LINE_HEIGHT
        }
    }

    companion object { const val LINE_HEIGHT = 1.2f }
}
