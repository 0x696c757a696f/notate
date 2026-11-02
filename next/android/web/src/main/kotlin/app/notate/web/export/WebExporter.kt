package app.notate.web.export

import android.graphics.Bitmap
import app.notate.core.model.PageInfo
import app.notate.core.web.WebExportPlan
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Writes chapters of a web document as a PDF "as seen on screen" (R4-3): every chapter is loaded, scrolled slice by slice, each
 * slice is captured with the handwriting on top and becomes one page. The pane does the loading and capturing ([Host]); this
 * class only plans the slices and writes the file. Cancelling the calling coroutine stops the export and deletes the partial file.
 */
class WebExporter(private val host: Host, private val io: kotlinx.coroutines.CoroutineDispatcher) {
    /** Size of a loaded chapter, device px. [pxPerPt] converts the capture to PDF points. */
    class Geometry(val contentHeightPx: Int, val viewportWidthPx: Int, val viewportHeightPx: Int, val pxPerPt: Float)

    interface Host {
        /** Loads [page] and waits until its height has settled; null when it could not be shown. Main thread. */
        suspend fun load(page: PageInfo): Geometry?

        /**
         * The visible area after scrolling to [scrollYPx], web content plus ink, plus the scroll that was actually reached
         * (the page cannot always scroll as far as asked). The caller recycles the bitmap. Main thread.
         */
        suspend fun capture(page: PageInfo, scrollYPx: Int): Capture?
    }

    class Capture(val bitmap: Bitmap, val actualScrollYPx: Int)

    /** [progress] gets (chapters done, chapters total). Returns the number of PDF pages written. */
    suspend fun exportPdf(chapters: List<PageInfo>, title: String, out: File, progress: (Int, Int) -> Unit): Int {
        val doc = PDDocument()
        var pages = 0
        var ok = false
        try {
            chapters.forEachIndexed { index, chapter ->
                coroutineContext.ensureActive()
                progress(index, chapters.size)
                val g = host.load(chapter) ?: return@forEachIndexed
                if (g.pxPerPt <= 0f) return@forEachIndexed
                for (slice in WebExportPlan.slices(g.contentHeightPx, g.viewportHeightPx)) {
                    coroutineContext.ensureActive()
                    val cap = host.capture(chapter, slice.scrollY) ?: continue
                    try {
                        pages += withContext(io) { addSlice(doc, cap, slice, g) }
                    } finally {
                        cap.bitmap.recycle()
                    }
                }
            }
            progress(chapters.size, chapters.size)
            doc.documentInformation.title = title
            doc.documentInformation.creator = "Notate"
            out.parentFile?.mkdirs()
            withContext(io) { doc.save(out) }
            ok = true
        } finally {
            doc.close()
            if (!ok) out.delete()
        }
        return pages
    }

    private fun addSlice(doc: PDDocument, cap: Capture, slice: WebExportPlan.Slice, g: Geometry): Int {
        val bmp = cap.bitmap
        // The capture starts at actualScrollYPx; the slice wants content rows [scrollY + srcTop, scrollY + srcTop + height).
        val wanted = slice.scrollY + slice.srcTop
        val top = (wanted - cap.actualScrollYPx).coerceIn(0, bmp.height - 1)
        val height = minOf(slice.height, bmp.height - top)
        if (height <= 0) return 0
        val cropped = if (top == 0 && height == bmp.height) bmp else Bitmap.createBitmap(bmp, 0, top, bmp.width, height)
        try {
            val image = JPEGFactory.createFromImage(doc, cropped, JPEG_QUALITY)
            val w = cropped.width / g.pxPerPt
            val h = cropped.height / g.pxPerPt
            val page = PDPage(PDRectangle(w, h))
            doc.addPage(page)
            PDPageContentStream(doc, page).use { it.drawImage(image, 0f, 0f, w, h) }
        } finally {
            if (cropped !== bmp) cropped.recycle()
        }
        return 1
    }

    companion object {
        const val JPEG_QUALITY = 0.85f

        /** Characters that would break a file name; letters of any script are kept. */
        fun safeFileName(title: String, fallback: String = "Notate"): String {
            val cleaned = title.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").trim().trim('.').take(60)
            return cleaned.ifEmpty { fallback }
        }
    }
}
