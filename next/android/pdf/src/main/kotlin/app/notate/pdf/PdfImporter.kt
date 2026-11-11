package app.notate.pdf

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import app.notate.core.doc.pages.TemplateCatalog
import app.notate.core.model.Background
import app.notate.core.model.Clock
import app.notate.core.model.DocId
import app.notate.core.model.DocKind
import app.notate.core.model.DocumentMeta
import app.notate.core.model.PageId
import app.notate.core.model.PageInfo
import app.notate.core.model.PageSize
import app.notate.core.model.PageTemplate
import app.notate.core.model.log.Logger
import app.notate.core.model.edit.Edit
import app.notate.core.store.AssetKind
import app.notate.core.store.DocStoreFactory
import app.notate.core.store.library.DocumentEntry
import app.notate.core.store.library.LibraryStore
import app.notate.core.store.outline.OutlineMapper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Random

/** Why [PdfImporter.importUri] failed; the screen turns it into a message. */
class PdfImportException(val reason: Reason, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Reason { PASSWORD_PROTECTED, UNREADABLE }
}

/**
 * Imports a PDF as a [DocKind.PDF] document (R3-1): one page per PDF page with a [Background.Pdf], the original file as
 * an asset, the outline, and a cover. Runs on [io]; safe to call from any thread. Cancelling the calling coroutine deletes
 * the half-made document.
 */
class PdfImporter(
    private val context: Context,
    private val stagingRoot: File,
    private val docDir: (DocId) -> File,
    private val docs: DocStoreFactory,
    libraryProvider: () -> LibraryStore,
    private val clock: Clock,
    private val io: CoroutineDispatcher,
    private val logger: Logger,
    private val sources: PdfPageSourceFactory = PdfPageSourceFactory.SYSTEM,
    private val outlines: PdfOutlineReader = PdfOutlineReader.pdfbox(logger),
    private val random: Random = Random(),
) {
    /** Opened on first use, which happens on [io]. */
    private val library: LibraryStore by lazy(libraryProvider)

    /** Imports the file at [uri]. [onProgress] gets (pages read, total) after each page, from [io]. Throws [PdfImportException]. */
    suspend fun importUri(uri: Uri, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): DocId = withContext(io) {
        val name = displayName(uri) ?: "PDF"
        val staging = File(stagingRoot, "pdf-${java.lang.Long.toHexString(random.nextLong())}").apply { mkdirs() }
        try {
            val file = File(staging, "source.pdf")
            try {
                context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
                    ?: throw PdfImportException(PdfImportException.Reason.UNREADABLE, "Cannot open $uri")
            } catch (e: IOException) {
                throw PdfImportException(PdfImportException.Reason.UNREADABLE, "Cannot read $uri", e)
            }
            importFile(file, titleOf(name), onProgress)
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Imports [file] (a local copy the caller owns). */
    suspend fun importFile(file: File, title: String, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): DocId = withContext(io) {
        val source = try {
            sources.open(file)
        } catch (e: PdfOpenException) {
            throw PdfImportException(
                if (e.reason == PdfOpenException.Reason.PASSWORD_PROTECTED) PdfImportException.Reason.PASSWORD_PROTECTED else PdfImportException.Reason.UNREADABLE,
                e.message ?: "Cannot open PDF", e,
            )
        }
        val id = DocId.new(clock, random)
        val dir = docDir(id)
        var count = 0
        try {
            source.use { src ->
                count = src.pageCount
                if (count <= 0) throw PdfImportException(PdfImportException.Reason.UNREADABLE, "PDF has no pages")
                val sizes = ArrayList<Pair<Float, Float>>(count)
                for (i in 0 until count) {
                    currentCoroutineContext().ensureActive()
                    sizes += try {
                        src.sizeOf(i)
                    } catch (e: Exception) {
                        throw PdfImportException(PdfImportException.Reason.UNREADABLE, "Cannot read page ${i + 1}", e)
                    }
                    onProgress(i + 1, count)
                }
                currentCoroutineContext().ensureActive()
                create(id, dir, file, title, sizes)
                makeCover(src, dir, sizes.first())
            }
            val now = clock.now()
            library.upsert(
                DocumentEntry(
                    id, title, DocKind.PDF, null, now, now, null, pageCount = count,
                    coverRev = 1, pinned = false, trashed = null, tagIds = emptySet(),
                ),
            )
            logger.i(TAG, "Imported PDF '$title' as ${id.value}")
            id
        } catch (t: Throwable) {
            dir.deleteRecursively()   // cancelled or failed: leave nothing behind
            throw t
        }
    }

    private suspend fun create(id: DocId, dir: File, file: File, title: String, sizes: List<Pair<Float, Float>>) {
        val now = clock.now()
        val (w0, h0) = sizes.first()
        val template = PageTemplate(PageSize.A4, landscape = w0 > h0, background = TemplateCatalog.entries.first { it.id == "blank" }.background)
        val meta = DocumentMeta(id, title, DocKind.PDF, createdAt = now, modifiedAt = now, newPageTemplate = template)
        // The asset id is only known once the file is stored, so the pages are created with a placeholder and fixed in one transaction.
        val placeholder = Background.Pdf(app.notate.core.model.AssetId(""), 0)
        val pages = sizes.mapIndexed { i, (w, h) ->
            PageInfo(PageId(i + 1L), i + 1.0, w, h, placeholder.copy(pageIndex = i), inkRev = 0, modifiedAt = now)
        }
        val store = docs.create(dir, meta, pages)
        try {
            val asset = store.putAsset(AssetKind.PDF, "application/pdf", file, "pdf")
            val fixed = pages.map { p -> p.copy(background = (p.background as Background.Pdf).copy(asset = asset)) }
            store.write(listOf(Edit.Batch(pages.zip(fixed) { before, after -> Edit.SetPage(before, after) })))
            val outline = try {
                OutlineMapper.map(outlines.read(file), fixed.map { it.id })
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                logger.w(TAG, "No outline for '$title'", e)   // an unreadable outline only costs the table of contents
                emptyList()
            }
            if (outline.isNotEmpty()) store.writeOutline(outline)
        } finally {
            store.close()
        }
    }

    private fun makeCover(src: PdfPageSource, dir: File, first: Pair<Float, Float>) {
        val width = COVER_WIDTH
        val height = (width * first.second / first.first).toInt().coerceIn(1, COVER_MAX_HEIGHT)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            src.renderCover(0, bitmap)
            val out = File(dir, "thumbs/cover.webp").also { it.parentFile?.mkdirs() }
            out.outputStream().use { s ->
                @Suppress("DEPRECATION")
                val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
                bitmap.compress(format, 80, s)
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            logger.w(TAG, "Cover generation failed", t)   // a missing cover only costs a placeholder on the shelf
        } finally {
            bitmap.recycle()
        }
    }

    private fun displayName(uri: Uri): String? {
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0)?.let { return it }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private companion object {
        const val TAG = "PdfImporter"
        const val COVER_WIDTH = 600
        const val COVER_MAX_HEIGHT = 1200

        fun titleOf(fileName: String): String =
            fileName.substringBeforeLast('.', fileName).ifBlank { "PDF" }
    }
}
