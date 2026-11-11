package app.notate.export.doc

import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState

/** Colour, transparency and path helpers shared by the template canvas and the ink writer. */
internal class PdfStyles {
    private val states = HashMap<Pair<Int, Boolean>, PDExtendedGraphicsState>()

    fun stroke(cs: PDPageContentStream, argb: Int) = cs.setStrokingColor(r(argb), g(argb), b(argb))
    fun fill(cs: PDPageContentStream, argb: Int) = cs.setNonStrokingColor(r(argb), g(argb), b(argb))

    /** Makes everything drawn until the matching restore as transparent as [argb] says, and multiplied onto the page when [multiply]. */
    fun transparency(cs: PDPageContentStream, argb: Int, multiply: Boolean = false) {
        val a = argb ushr 24
        if (a >= 255 && !multiply) return
        cs.setGraphicsStateParameters(states.getOrPut(a to multiply) {
            PDExtendedGraphicsState().apply {
                nonStrokingAlphaConstant = a / 255f
                strokingAlphaConstant = a / 255f
                if (multiply) setBlendMode(BlendMode.MULTIPLY)
            }
        })
    }

    /** Opacity only (images). */
    fun opacity(cs: PDPageContentStream, opacity: Float) {
        if (opacity >= 0.999f) return
        transparency(cs, ((opacity.coerceIn(0f, 1f)) * 255f + 0.5f).toInt() shl 24)
    }

    companion object {
        fun r(c: Int) = ((c shr 16) and 0xFF) / 255f
        fun g(c: Int) = ((c shr 8) and 0xFF) / 255f
        fun b(c: Int) = (c and 0xFF) / 255f

        /** Appends the polyline [xy] (x0,y0,x1,y1,…) to the current path. */
        fun path(cs: PDPageContentStream, xy: FloatArray, close: Boolean) {
            if (xy.size < 2) return
            cs.moveTo(xy[0], xy[1])
            var i = 2
            while (i + 1 < xy.size) { cs.lineTo(xy[i], xy[i + 1]); i += 2 }
            if (close) cs.closePath()
        }

        /** Appends a circle made of four Bézier arcs. */
        fun circle(cs: PDPageContentStream, cx: Float, cy: Float, r: Float) {
            val k = r * 0.5522848f
            cs.moveTo(cx + r, cy)
            cs.curveTo(cx + r, cy + k, cx + k, cy + r, cx, cy + r)
            cs.curveTo(cx - k, cy + r, cx - r, cy + k, cx - r, cy)
            cs.curveTo(cx - r, cy - k, cx - k, cy - r, cx, cy - r)
            cs.curveTo(cx + k, cy - r, cx + r, cy - k, cx + r, cy)
            cs.closePath()
        }
    }
}
