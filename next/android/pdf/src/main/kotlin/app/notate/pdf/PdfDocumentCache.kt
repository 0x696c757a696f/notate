package app.notate.pdf

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Open `PdfRenderer`s, least recently used evicted beyond [capacity]. **Only touch it on the PDF thread**: a renderer allows one
 * open page at a time and is not thread-safe. Keyed by file path (asset ids are only unique inside one document).
 */
class PdfDocumentCache(private val capacity: Int = 2) {
    private class Entry(val pfd: ParcelFileDescriptor, val renderer: PdfRenderer)

    private val entries = LinkedHashMap<String, Entry>(4, 0.75f, true)

    /** The renderer for [file], opened if needed. Throws if the file cannot be opened. */
    fun get(file: File): PdfRenderer {
        entries[file.path]?.let { return it.renderer }
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = try { PdfRenderer(pfd) } catch (t: Throwable) { runCatching { pfd.close() }; throw t }
        entries[file.path] = Entry(pfd, renderer)
        while (entries.size > capacity) {
            val oldest = entries.entries.first()
            entries.remove(oldest.key)
            close(oldest.value)
        }
        return renderer
    }

    /** Closes every renderer. */
    fun clear() {
        entries.values.forEach(::close)
        entries.clear()
    }

    val size: Int get() = entries.size

    private fun close(e: Entry) {
        runCatching { e.renderer.close() }
        runCatching { e.pfd.close() }
    }
}
