package app.notate.export.doc

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import app.notate.core.geometry.Rect
import app.notate.core.ink.outline.InkGeometry
import app.notate.core.ink.outline.Outlines
import app.notate.core.ink.shape.ShapeOutline
import app.notate.core.model.AssetId
import app.notate.core.model.ImageItem
import app.notate.core.model.Item
import app.notate.core.model.PenType
import app.notate.core.model.Shape
import app.notate.core.model.ShapeKind
import app.notate.core.model.Stroke
import app.notate.core.model.TextAlign
import app.notate.core.model.TextBox
import app.notate.render.image.TextLayouts
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Writes page items into a PDF content stream whose user space is the page (pt, origin top left, y down): strokes and shapes as
 * vector paths built from the same outlines that are drawn on screen, images as XObjects, text as text (Latin-1) or, for other
 * scripts, as a 300 dpi picture. Not thread-safe; one instance per export.
 */
internal class InkPdfWriter(
    private val doc: PDDocument,
    private val assetFile: (AssetId) -> File?,
    private val styles: PdfStyles,
) {
    private val images = HashMap<AssetId, PDImageXObject?>()

    fun draw(cs: PDPageContentStream, item: Item) {
        when (item) {
            is Stroke -> stroke(cs, item)
            is Shape -> shape(cs, item)
            is ImageItem -> image(cs, item)
            is TextBox -> text(cs, item)
            else -> Unit // links are annotations, not page content
        }
    }

    private fun stroke(cs: PDPageContentStream, s: Stroke) {
        val pen = s.style.pen
        val highlighter = pen == PenType.HIGHLIGHTER
        val color = s.style.color
        cs.saveGraphicsState()
        styles.transparency(cs, color, multiply = highlighter)
        when (val g = Outlines.forPen(pen).outline(s.points, s.style)) {
            is InkGeometry.Fill -> {
                styles.fill(cs, color)
                PdfStyles.path(cs, g.polygon, close = true)
                cs.fill()
            }
            is InkGeometry.Polyline -> polyline(
                cs, g, color,
                cap = when (pen) { PenType.HIGHLIGHTER -> CAP_SQUARE; PenType.DASHED -> CAP_BUTT; else -> CAP_ROUND },
                join = if (highlighter) JOIN_MITER else JOIN_ROUND,
            )
            is InkGeometry.Dots -> {
                styles.fill(cs, color)
                for (i in g.radii.indices) PdfStyles.circle(cs, g.centers[2 * i], g.centers[2 * i + 1], g.radii[i])
                cs.fill()
            }
        }
        cs.restoreGraphicsState()
    }

    private fun shape(cs: PDPageContentStream, s: Shape) {
        val lines = ShapeOutline.of(s.kind, s.params, s.style.widthPt)
        if (lines.isEmpty()) return
        val highlighter = s.style.pen == PenType.HIGHLIGHTER
        cs.saveGraphicsState()
        styles.transparency(cs, s.style.color, multiply = highlighter)
        val closed = s.kind == ShapeKind.RECT || s.kind == ShapeKind.TRIANGLE || s.kind == ShapeKind.POLYGON || s.kind == ShapeKind.ELLIPSE
        val fill = s.fill
        if (fill != null && closed) {
            cs.saveGraphicsState()
            styles.transparency(cs, fill)
            styles.fill(cs, fill)
            PdfStyles.path(cs, lines[0].points, close = true)
            cs.fill()
            cs.restoreGraphicsState()
        }
        for (line in lines) polyline(cs, line, s.style.color, CAP_ROUND, JOIN_ROUND)
        cs.restoreGraphicsState()
    }

    private fun polyline(cs: PDPageContentStream, g: InkGeometry.Polyline, color: Int, cap: Int, join: Int) {
        styles.stroke(cs, color)
        cs.setLineWidth(g.width)
        cs.setLineCapStyle(cap)
        cs.setLineJoinStyle(join)
        val dash = g.dash
        if (dash != null && dash.size >= 2) cs.setLineDashPattern(dash, 0f)
        PdfStyles.path(cs, g.points, close = false)
        cs.stroke()
    }

    // --- Images ---------------------------------------------------------------------------------------------

    private fun image(cs: PDPageContentStream, img: ImageItem) {
        val x = images.getOrPut(img.asset) { load(img.asset) } ?: return
        val f = img.frame
        cs.saveGraphicsState()
        styles.opacity(cs, img.opacity)
        rotateAround(cs, img.rotationDeg, f)
        cs.drawImage(x, Matrix(f.width, 0f, 0f, -f.height, f.l, f.t + f.height)) // the page space is y-down: flip the picture upright
        cs.restoreGraphicsState()
    }

    private fun load(asset: AssetId): PDImageXObject? {
        val file = assetFile(asset) ?: return null
        return try {
            if (isJpeg(file)) {
                file.inputStream().use { JPEGFactory.createFromStream(doc, it) }
            } else {
                val bmp = decodeBounded(file) ?: return null
                try { LosslessFactory.createFromImage(doc, bmp) } finally { bmp.recycle() }
            }
        } catch (e: Exception) {
            null // an unreadable picture is left out; the rest of the page still exports
        }
    }

    private fun isJpeg(file: File): Boolean = file.inputStream().use { val a = it.read(); val b = it.read(); a == 0xFF && b == 0xD8 }

    private fun decodeBounded(file: File): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var sample = 1
        while (max(o.outWidth, o.outHeight) / (sample * 2) >= MAX_IMAGE_SIDE) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    // --- Text -----------------------------------------------------------------------------------------------

    private fun text(cs: PDPageContentStream, t: TextBox) {
        if (t.text.isEmpty()) return
        val layout = TextLayouts.build(t.text, t.fontSizePt, t.bold, t.frame.width, t.align, t.color)
        val font = if (t.bold) PDType1Font.HELVETICA_BOLD else PDType1Font.HELVETICA
        val lines = if (isLatin(t.text)) (0 until layout.lineCount).map { i ->
            layout.text.subSequence(layout.getLineStart(i), layout.getLineEnd(i)).toString().trimEnd('\n', '\r')
        } else null
        if (lines == null || lines.any { runCatching { font.encode(it) }.isFailure }) return textAsPicture(cs, t, layout)

        cs.saveGraphicsState()
        styles.fill(cs, t.color)
        styles.transparency(cs, t.color)
        rotateAround(cs, t.rotationDeg, t.frame)
        cs.beginText()
        cs.setFont(font, t.fontSizePt)
        for ((i, line) in lines.withIndex()) {
            if (line.isEmpty()) continue
            val w = font.getStringWidth(line) / 1000f * t.fontSizePt
            val dx = when (t.align) { TextAlign.LEFT -> 0f; TextAlign.CENTER -> (t.frame.width - w) / 2f; TextAlign.RIGHT -> t.frame.width - w }
            // The page space is y-down; the text matrix flips the glyphs upright again.
            cs.setTextMatrix(Matrix(1f, 0f, 0f, -1f, t.frame.l + dx, t.frame.t + layout.getLineBaseline(i)))
            cs.showText(line)
        }
        cs.endText()
        cs.restoreGraphicsState()
    }

    /** Text in other scripts has no embedded font; it is drawn at 300 dpi and placed as a transparent picture. */
    private fun textAsPicture(cs: PDPageContentStream, t: TextBox, layout: android.text.StaticLayout) {
        val h = layout.height.toFloat()
        if (h <= 0f || t.frame.width <= 0f) return
        val scale = min(PICTURE_DPI / 72f, MAX_IMAGE_SIDE / max(t.frame.width, h))
        val bmp = Bitmap.createBitmap(max(1, ceil(t.frame.width * scale).toInt()), max(1, ceil(h * scale).toInt()), Bitmap.Config.ARGB_8888)
        try {
            val c = Canvas(bmp)
            c.scale(scale, scale)
            layout.draw(c)
            val x = LosslessFactory.createFromImage(doc, bmp)
            cs.saveGraphicsState()
            rotateAround(cs, t.rotationDeg, t.frame)
            cs.drawImage(x, Matrix(t.frame.width, 0f, 0f, -h, t.frame.l, t.frame.t + h))
            cs.restoreGraphicsState()
        } finally {
            bmp.recycle()
        }
    }

    private fun rotateAround(cs: PDPageContentStream, deg: Float, f: Rect) {
        if (deg == 0f) return
        val c = f.center
        val rad = Math.toRadians(deg.toDouble())
        cs.transform(Matrix.getTranslateInstance(c.x, c.y))
        cs.transform(Matrix(Math.cos(rad).toFloat(), Math.sin(rad).toFloat(), -Math.sin(rad).toFloat(), Math.cos(rad).toFloat(), 0f, 0f))
        cs.transform(Matrix.getTranslateInstance(-c.x, -c.y))
    }

    companion object {
        const val CAP_BUTT = 0
        const val CAP_ROUND = 1
        const val CAP_SQUARE = 2
        const val JOIN_MITER = 0
        const val JOIN_ROUND = 1
        private const val PICTURE_DPI = 300f
        private const val MAX_IMAGE_SIDE = 4096f

        /** True when every character has a glyph in the standard Helvetica (Latin-1 without control codes). */
        fun isLatin(s: String): Boolean = s.all { it == '\n' || it in ' '..'~' || it in ' '..'ÿ' }
    }
}
