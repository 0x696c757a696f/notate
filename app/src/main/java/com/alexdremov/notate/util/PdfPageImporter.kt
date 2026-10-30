package com.alexdremov.notate.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import java.util.UUID
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Imports PDF documents as annotatable pages (GoodNotes/Notability style).
 *
 * Each PDF page is rasterized with the framework [PdfRenderer] and placed on the
 * canvas as a locked [com.alexdremov.notate.model.CanvasImage] underneath the ink.
 * Layout is kept separate from rendering so it can be unit-tested on the JVM.
 */
object PdfPageImporter {
    private const val TAG = "PdfPageImporter"

    /** Size of a PDF page in PDF points (1/72 inch). */
    data class PageSize(
        val width: Float,
        val height: Float,
    )

    /** Placement of a page in world coordinates. */
    data class PageRect(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }

    sealed interface Layout {
        /**
         * Fixed-pages notebooks: page i goes into slot [firstSlot] + i of the notebook grid,
         * scaled to fit the slot and centered inside it.
         */
        data class FixedSlots(
            val pageWidth: Float,
            val pageHeight: Float,
            val spacing: Float,
            val firstSlot: Int,
        ) : Layout

        /** Infinite canvas: pages are stacked vertically with a constant width. */
        data class Stacked(
            val left: Float,
            val top: Float,
            val width: Float,
            val spacing: Float,
        ) : Layout
    }

    fun layoutPages(
        pages: List<PageSize>,
        layout: Layout,
    ): List<PageRect> =
        when (layout) {
            is Layout.FixedSlots -> {
                val slotHeight = layout.pageHeight + layout.spacing
                pages.mapIndexed { i, page ->
                    val slotTop = (layout.firstSlot + i) * slotHeight
                    val scale = fitScale(page, layout.pageWidth, layout.pageHeight)
                    val w = page.width * scale
                    val h = page.height * scale
                    val left = (layout.pageWidth - w) / 2f
                    val top = slotTop + (layout.pageHeight - h) / 2f
                    PageRect(left, top, left + w, top + h)
                }
            }

            is Layout.Stacked -> {
                var y = layout.top
                pages.map { page ->
                    val h = if (page.width > 0f) layout.width * page.height / page.width else layout.width
                    val rect = PageRect(layout.left, y, layout.left + layout.width, y + h)
                    y += h + layout.spacing
                    rect
                }
            }
        }

    /**
     * Picks a notebook page size for a new note created from a PDF: the width is fixed to
     * [targetWidth] and the height follows the first page's aspect ratio.
     */
    fun notebookPageSize(
        firstPage: PageSize,
        targetWidth: Float,
    ): Pair<Float, Float> {
        if (firstPage.width <= 0f || firstPage.height <= 0f) return targetWidth to targetWidth * 1.4142f
        return targetWidth to targetWidth * firstPage.height / firstPage.width
    }

    /** Bitmap dimensions used to rasterize a page so its width is [targetWidthPx]. */
    fun renderSize(
        page: PageSize,
        targetWidthPx: Int,
        maxPixels: Int,
    ): Pair<Int, Int> {
        var w = targetWidthPx.toFloat()
        var h = if (page.width > 0f) w * page.height / page.width else w
        val pixels = w * h
        if (pixels > maxPixels) {
            val s = kotlin.math.sqrt(maxPixels / pixels)
            w *= s
            h *= s
        }
        return w.roundToInt().coerceAtLeast(1) to h.roundToInt().coerceAtLeast(1)
    }

    private fun fitScale(
        page: PageSize,
        boxWidth: Float,
        boxHeight: Float,
    ): Float {
        if (page.width <= 0f || page.height <= 0f) return 1f
        return min(boxWidth / page.width, boxHeight / page.height)
    }

    /**
     * An opened PDF. Copies the source to a private temp file first, because
     * [PdfRenderer] needs a seekable descriptor which many content providers don't offer.
     */
    class Document private constructor(
        private val tempFile: File,
        private val descriptor: ParcelFileDescriptor,
        private val renderer: PdfRenderer,
    ) : Closeable {
        val pageCount: Int get() = renderer.pageCount

        fun pageSizes(): List<PageSize> =
            (0 until renderer.pageCount).map { i ->
                renderer.openPage(i).use { PageSize(it.width.toFloat(), it.height.toFloat()) }
            }

        /**
         * Rasterizes page [index] to a PNG in [outDir] and returns the file.
         * The bitmap is filled white first: PdfRenderer leaves unpainted areas transparent.
         */
        fun renderPageToPng(
            index: Int,
            targetWidthPx: Int,
            maxPixels: Int,
            outDir: File,
        ): File {
            renderer.openPage(index).use { page ->
                val (w, h) = renderSize(PageSize(page.width.toFloat(), page.height.toFloat()), targetWidthPx, maxPixels)
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    outDir.mkdirs()
                    val out = File(outDir, "pdf_page_${UUID.randomUUID()}.png")
                    out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    return out
                } finally {
                    bitmap.recycle()
                }
            }
        }

        override fun close() {
            try {
                renderer.close()
            } catch (e: Exception) {
                Logger.w(TAG, "Failed to close renderer: ${e.message}")
            }
            try {
                descriptor.close()
            } catch (e: Exception) {
                Logger.w(TAG, "Failed to close descriptor: ${e.message}")
            }
            tempFile.delete()
        }

        companion object {
            /**
             * Opens [uri]. Throws on unreadable, corrupt or password-protected documents
             * (PdfRenderer reports the latter as [SecurityException]).
             */
            fun open(
                context: Context,
                uri: Uri,
            ): Document {
                val tempFile = File(context.cacheDir, "pdf_import_${UUID.randomUUID()}.pdf")
                val copied =
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tempFile.outputStream().use { output -> input.copyTo(output) }
                        true
                    } ?: false
                if (!copied) {
                    tempFile.delete()
                    throw java.io.IOException("Cannot read $uri")
                }
                var descriptor: ParcelFileDescriptor? = null
                try {
                    descriptor = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
                    return Document(tempFile, descriptor, PdfRenderer(descriptor))
                } catch (e: Exception) {
                    descriptor?.close()
                    tempFile.delete()
                    throw e
                }
            }
        }
    }
}
