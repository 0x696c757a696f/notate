package app.notate.web

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import app.notate.core.doc.settings.Settings
import app.notate.core.model.Background
import app.notate.core.model.Clock
import app.notate.core.model.DocId
import app.notate.core.model.DocKind
import app.notate.core.model.DocumentMeta
import app.notate.core.model.PageId
import app.notate.core.model.PageInfo
import app.notate.core.model.WebDocSettings
import app.notate.core.model.log.Logger
import app.notate.core.store.AssetKind
import app.notate.core.store.DocStoreFactory
import app.notate.core.store.OutlineEntry
import app.notate.core.store.library.DocQuery
import app.notate.core.store.library.DocumentEntry
import app.notate.core.store.library.LibraryStore
import app.notate.core.store.library.Scope
import app.notate.core.web.ImportBudget
import app.notate.core.web.ImportLimits
import app.notate.core.web.TocEntry
import app.notate.core.web.WebImportException
import app.notate.core.web.WebImportException.Reason
import app.notate.core.web.WebImportPlan
import app.notate.core.web.WebImportPlanner
import app.notate.core.web.WebLayoutMath
import app.notate.core.web.WebSourceKind
import app.notate.core.web.ZipSafety
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.IOException
import java.util.Random
import java.util.zip.ZipFile

/**
 * Imports an EPUB, HTML file, ZIP bundle or HTML folder as a [DocKind.WEB] document (R4-1).
 * Every call runs its file work on [io]; safe to call from any thread. The same source imported twice
 * returns the existing document.
 */
class WebImporter(
    private val context: Context,
    private val stagingRoot: File,
    private val docDir: (DocId) -> File,
    private val docs: DocStoreFactory,
    libraryProvider: () -> LibraryStore,
    private val settings: Settings,
    private val clock: Clock,
    private val io: CoroutineDispatcher,
    private val logger: Logger,
    /** Width of the pane the book will first open in, in dp; sets the initial layout width. */
    private val paneWidthDp: () -> Float,
    private val newXmlParser: () -> XmlPullParser = { XmlPullParserFactory.newInstance().newPullParser() },
    private val random: Random = Random(),
    private val limits: ImportLimits = ImportLimits(),
) {
    private val planner = WebImportPlanner(newXmlParser)

    /** Opened on first use, which happens on [io] (the library database must not be opened on the main thread). */
    private val library: LibraryStore by lazy(libraryProvider)

    /** Imports the file at [uri] (`.epub`, `.html`/`.htm`/`.xhtml`, `.zip`). Throws [WebImportException]. */
    suspend fun importUri(uri: Uri): DocId = withContext(io) {
        val name = displayName(uri) ?: "Book"
        stage { staging ->
            val source = File(staging, "source")
            try {
                context.contentResolver.openInputStream(uri)?.use { ZipSafety.copyLimited(it, source, ImportBudget(limits)) }
                    ?: throw WebImportException(Reason.NOT_SUPPORTED, "Cannot open $uri")
            } catch (e: WebImportException) {
                throw e
            } catch (e: IOException) {
                throw WebImportException(Reason.NOT_SUPPORTED, "Cannot read $uri", e)
            }
            val hash = ZipSafety.sha256OfFile(source)
            existing(hash)?.let { return@stage it }
            val content = File(staging, "content")
            val kind = if (isZip(source)) {
                ZipSafety.extract(source, content, limits)
                if (name.endsWith(".epub", ignoreCase = true)) WebSourceKind.EPUB else WebSourceKind.ZIP
            } else {
                if (name.substringAfterLast('.', "").lowercase() !in HTML_EXTENSIONS && !looksLikeHtml(source)) {
                    throw WebImportException(Reason.NOT_SUPPORTED, "Not a book or web page")
                }
                content.mkdirs()
                source.copyTo(File(content, safeFileName(name)))
                WebSourceKind.HTML_FILE
            }
            finish(content, kind, name, hash)
        }
    }

    /** Imports the folder behind [treeUri] (from `OpenDocumentTree`). Throws [WebImportException]. */
    suspend fun importTree(treeUri: Uri): DocId = withContext(io) {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw WebImportException(Reason.NOT_SUPPORTED, "Cannot open folder")
        stage { staging ->
            val content = File(staging, "content").apply { mkdirs() }
            try {
                copyTree(root, content, ImportBudget(limits), context.contentResolver)
            } catch (e: IOException) {
                throw WebImportException(Reason.NOT_SUPPORTED, "Cannot read folder", e)
            }
            val hash = ZipSafety.sha256OfTree(content)
            existing(hash)?.let { return@stage it }
            finish(content, WebSourceKind.FOLDER, root.name ?: "Web page", hash)
        }
    }

    /** Removes the duplicate-detection key of a deleted web document; call when its library row is purged. */
    suspend fun forgetSource(sourceHash: String?) {
        if (sourceHash != null) library.putKv(KV_PREFIX + sourceHash, null)
    }

    private suspend fun stage(block: suspend (File) -> DocId): DocId {
        val staging = File(stagingRoot, "import-${java.lang.Long.toHexString(random.nextLong())}").apply { mkdirs() }
        try {
            return block(staging)
        } finally {
            staging.deleteRecursively()
        }
    }

    private suspend fun finish(content: File, kind: WebSourceKind, name: String, hash: String): DocId {
        val plan = planner.plan(content, kind, name)
        val id = DocId.new(clock, random)
        val dir = docDir(id)
        try {
            create(id, dir, plan, content, hash)
        } catch (t: Throwable) {
            dir.deleteRecursively()
            throw t
        }
        val now = clock.now()
        library.upsert(
            DocumentEntry(id, plan.title, DocKind.WEB, null, now, now, null, plan.pages.size, coverRev = 1, pinned = false, trashed = null, tagIds = emptySet()),
        )
        library.putKv(KV_PREFIX + hash, id.value)
        logger.i(TAG, "Imported '${plan.title}' as ${id.value} (${plan.pages.size} pages)")
        return id
    }

    private suspend fun create(id: DocId, dir: File, plan: WebImportPlan, content: File, hash: String) {
        val now = clock.now()
        val layoutCss = WebLayoutMath.initialWidth(paneWidthDp())
        val pages = plan.pages.mapIndexed { i, p ->
            PageInfo(
                id = PageId(i + 1L), ord = i + 1.0, width = layoutCss * CSS_PX_TO_PT, height = null,
                background = Background.Web(p.href, layoutCss, layoutGen = 1), inkRev = 0, modifiedAt = now,
            )
        }
        val meta = DocumentMeta(
            id = id, title = plan.title, kind = DocKind.WEB, createdAt = now, modifiedAt = now,
            web = WebDocSettings(
                javascript = settings.webJsDefault.value, network = settings.webNetworkDefault.value, sourceHash = hash,
            ),
        )
        val store = docs.create(dir, meta, pages)
        try {
            store.putAsset(AssetKind.WEB, "application/x-notate-web", content, "")
            store.writeOutline(outline(plan.toc, pages))
        } finally {
            store.close()
        }
        makeCover(plan, content, dir)
    }

    private fun makeCover(plan: WebImportPlan, content: File, dir: File) {
        val out = File(dir, "thumbs/cover.webp")
        try {
            val cover = plan.coverHref?.let { File(content, it) }?.takeIf { it.isFile }
            if (cover != null && CoverMaker.fromImage(cover, out)) return
            CoverMaker.titleCard(plan.title, plan.author, out)
        } catch (t: Throwable) {
            logger.w(TAG, "Cover generation failed", t) // a missing cover only costs a placeholder on the shelf
        }
    }

    /** Flattens [toc] depth-first into outline rows; the `#fragment` goes to [OutlineEntry.anchor]. */
    private fun outline(toc: List<TocEntry>, pages: List<PageInfo>): List<OutlineEntry> {
        val byHref = HashMap<String, PageId>()
        pages.forEach { p -> (p.background as Background.Web).let { byHref.putIfAbsent(it.href, p.id) } }
        val out = ArrayList<OutlineEntry>()
        var nextId = 1L
        fun walk(entries: List<TocEntry>, parent: Long?) {
            entries.forEachIndexed { i, e ->
                val id = nextId++
                val path = e.href?.substringBefore('#')
                val anchor = e.href?.substringAfter('#', "")?.takeIf { it.isNotEmpty() }
                out += OutlineEntry(id, parent, i, e.title, path?.let { byHref[it] }, null, anchor)
                walk(e.children, id)
            }
        }
        walk(toc, null)
        return out
    }

    /** The document already imported from the same source bytes, restoring it from the trash if needed. */
    private suspend fun existing(hash: String): DocId? {
        val viaKv = library.getKv(KV_PREFIX + hash)?.let { DocId(it) }?.takeIf { library.get(it) != null }
        val found = viaKv ?: scanForHash(hash)?.also { library.putKv(KV_PREFIX + hash, it.value) }
        if (found != null && library.get(found)?.trashed != null) library.restore(listOf(found))
        return found
    }

    /** Fallback when the key-value index is missing: compare the stored hash of every web document. */
    private suspend fun scanForHash(hash: String): DocId? {
        val web = library.observeDocuments(DocQuery(scope = Scope.BOOKS)).first()
        for (entry in web) {
            val h = try {
                docs.open(docDir(entry.id), readOnly = true).use { it.readMeta().web?.sourceHash }
            } catch (e: Exception) {
                logger.w(TAG, "Cannot read ${entry.id.value} while checking for duplicates", e)
                null
            }
            if (h == hash) return entry.id
        }
        return null
    }

    private fun copyTree(dir: DocumentFile, target: File, budget: ImportBudget, resolver: ContentResolver) {
        fun walk(node: DocumentFile, out: File) {
            for (child in node.listFiles()) {
                val childName = child.name ?: continue
                if (childName.contains('/') || childName.contains('\u0000') || childName == ".." || childName == ".") continue
                val file = File(out, childName)
                if (child.isDirectory) {
                    file.mkdirs(); walk(child, file)
                } else if (child.isFile) {
                    resolver.openInputStream(child.uri)?.use { ZipSafety.copyLimited(it, file, budget) }
                }
            }
        }
        walk(dir, target)
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

    private fun isZip(file: File): Boolean {
        file.inputStream().use { input ->
            val magic = ByteArray(2)
            if (input.read(magic) < 2 || magic[0] != 'P'.code.toByte() || magic[1] != 'K'.code.toByte()) return false
        }
        return runCatching { ZipFile(file).close() }.isSuccess
    }

    private fun looksLikeHtml(file: File): Boolean {
        val head = file.inputStream().use { input ->
            val buf = ByteArray(2048)
            String(buf, 0, maxOf(0, input.read(buf)), Charsets.UTF_8)
        }
        return head.contains("<html", ignoreCase = true) || head.contains("<!doctype html", ignoreCase = true)
    }

    private fun safeFileName(name: String): String {
        val clean = name.substringAfterLast('/').replace(Regex("[^\\w.\\- ]"), "_").ifBlank { "index.html" }
        return if (clean.substringAfterLast('.', "").lowercase() in HTML_EXTENSIONS) clean else "$clean.html"
    }

    companion object {
        private const val TAG = "WebImporter"
        const val KV_PREFIX = "websrc:"
        private const val CSS_PX_TO_PT = 0.75f
        private val HTML_EXTENSIONS = setOf("html", "htm", "xhtml")

        /** MIME types offered by the "Import EPUB or HTML" picker. */
        val PICKER_MIME_TYPES = arrayOf("application/epub+zip", "text/html", "application/xhtml+xml", "application/zip")
    }
}
