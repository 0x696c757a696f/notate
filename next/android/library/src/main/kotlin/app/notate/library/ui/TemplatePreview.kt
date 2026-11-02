package app.notate.library.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import app.notate.core.model.Background
import app.notate.core.model.PaperPattern
import app.notate.core.model.TemplateOption
import app.notate.core.model.Units.mm
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Page-sized reference the preview scales from (A4 in pt), so spacing looks right relative to the thumbnail. */
private const val REF_W = 595.28f
private const val REF_H = 841.89f

/** Small drawing of a paper template for the New notebook dialog; same pattern parameters as the real renderer. */
@Composable
fun TemplatePreview(option: TemplateOption, modifier: Modifier = Modifier) {
    Canvas(modifier.aspectRatio(3f / 4f).clipToBounds()) { drawPaper(option.background) }
}

private fun DrawScope.drawPaper(bg: Background.Paper) {
    val k = size.width / REF_W
    val line = Color(0xFF808080)
    val thin = 1f
    drawRect(Color.White, size = size)
    val s = bg.spacingPt.coerceAtLeast(1f)
    // Never draw more than ~60 lines per direction in a thumbnail; thicker spacing keeps it readable.
    val step = maxOf(s * k, size.width / 60f)
    fun h(y: Float, w: Float = thin, c: Color = line) = drawLine(c, Offset(0f, y), Offset(size.width, y), w)
    fun v(x: Float, w: Float = thin, c: Color = line) = drawLine(c, Offset(x, 0f), Offset(x, size.height), w)
    when (bg.pattern) {
        PaperPattern.BLANK -> Unit
        PaperPattern.LINED -> lines(step, size.height) { h(it) }
        PaperPattern.GRID -> { lines(step, size.height) { h(it) }; lines(step, size.width) { v(it) } }
        PaperPattern.DOTS -> {
            var y = step
            while (y < size.height) { var x = step; while (x < size.width) { drawCircle(line, 1.2f, Offset(x, y)); x += step }; y += step }
        }
        PaperPattern.ENGINEERING -> {
            val fine = step / 5f
            if (fine >= 4f) { lines(fine, size.height) { h(it, thin, Color(0xFFC8C8C8)) }; lines(fine, size.width) { v(it, thin, Color(0xFFC8C8C8)) } }
            lines(step, size.height) { h(it, 1.5f) }; lines(step, size.width) { v(it, 1.5f) }
        }
        PaperPattern.CORNELL -> {
            val headerY = mm(25f) * k
            val colX = (bg.marginLeftPt ?: mm(60f)) * k
            val bottomY = size.height - mm(50f) * k
            h(headerY); v(colX); h(bottomY)
            drawLine(Color.White, Offset(colX, 0f), Offset(colX, headerY), 2f)
            drawLine(Color.White, Offset(colX, bottomY), Offset(colX, size.height), 2f)
        }
        PaperPattern.ISOMETRIC -> isometric(step, line)
    }
    drawRect(Color(0xFF9E9E9E), size = Size(size.width, size.height), style = Stroke(2f))
}

private inline fun lines(step: Float, extent: Float, draw: (Float) -> Unit) {
    var p = step
    while (p < extent) { draw(p); p += step }
}

private fun DrawScope.isometric(s: Float, color: Color) {
    val d = s * (sqrt(3f) / 2f)
    val reach = hypot(size.width, size.height)
    val cx = size.width / 2f
    val cy = size.height / 2f
    for (deg in intArrayOf(0, 60, 120)) {
        val rad = Math.toRadians(deg.toDouble())
        val ux = cos(rad).toFloat(); val uy = sin(rad).toFloat()
        val nx = -uy; val ny = ux
        val n = ceil(reach / d).toInt()
        for (k in -n..n) {
            val px = cx + k * d * nx; val py = cy + k * d * ny
            drawLine(color, Offset(px - ux * reach, py - uy * reach), Offset(px + ux * reach, py + uy * reach), 1f)
        }
    }
}
