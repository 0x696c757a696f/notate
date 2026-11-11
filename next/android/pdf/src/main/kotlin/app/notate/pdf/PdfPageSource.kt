package app.notate.pdf

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.IOException

/** Why a file could not be imported as a PDF. */
class PdfOpenException(val reason: Reason, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Reason { PASSWORD_PROTECTED, NOT_A_PDF }
}

/** Page count and sizes of a PDF; the seam [PdfImporter] is tested through (Robolectric's `PdfRenderer` is a thin shadow). */
interface PdfPageSource : AutoCloseable {
    val pageCount: Int

    /** Page [index] size in pt, rotation already applied. Opens and closes the page. */
    fun sizeOf(index: Int): Pair<Float, Float>

    /** Renders page [index] into [out] scaled to fill it; used for the shelf cover. */
    fun renderCover(index: Int, out: android.graphics.Bitmap)
}

/** Opens a PDF file as a [PdfPageSource]. */
fun interface PdfPageSourceFactory {
    @Throws(PdfOpenException::class)
    fun open(file: File): PdfPageSource

    companion object {
        /** The platform `PdfRenderer`: a `SecurityException` means a password, anything else an unreadable file. */
        val SYSTEM = PdfPageSourceFactory { file ->
            try {
                RendererSource(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
            } catch (e: SecurityException) {
                throw PdfOpenException(PdfOpenException.Reason.PASSWORD_PROTECTED, "PDF is password-protected", e)
            } catch (e: IOException) {
                throw PdfOpenException(PdfOpenException.Reason.NOT_A_PDF, "Not a PDF", e)
            } catch (e: RuntimeException) {
                throw PdfOpenException(PdfOpenException.Reason.NOT_A_PDF, "Not a PDF", e)
            }
        }
    }
}

private class RendererSource private constructor(
    private val pfd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
) : PdfPageSource {
    constructor(pfd: ParcelFileDescriptor) : this(pfd, openOrClose(pfd))

    override val pageCount: Int get() = renderer.pageCount

    override fun sizeOf(index: Int): Pair<Float, Float> = renderer.openPage(index).use { it.width.toFloat() to it.height.toFloat() }

    override fun renderCover(index: Int, out: android.graphics.Bitmap) {
        out.eraseColor(android.graphics.Color.WHITE)
        renderer.openPage(index).use { page ->
            val m = android.graphics.Matrix()
            m.setScale(out.width / page.width.toFloat(), out.height / page.height.toFloat())
            page.render(out, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
    }

    override fun close() {
        runCatching { renderer.close() }
        runCatching { pfd.close() }
    }

    private companion object {
        fun openOrClose(pfd: ParcelFileDescriptor): PdfRenderer = try {
            PdfRenderer(pfd)
        } catch (t: Throwable) {
            runCatching { pfd.close() }
            throw t
        }
    }
}
