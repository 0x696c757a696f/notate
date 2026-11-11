package app.notate.export.doc

import app.notate.core.doc.registry.DocumentHandle
import app.notate.core.geometry.Rect
import app.notate.core.model.AssetId
import app.notate.core.model.Background
import app.notate.core.model.Item
import app.notate.core.model.LinkItem
import app.notate.core.model.LinkTarget
import app.notate.core.model.PageId
import app.notate.core.model.PageInfo
import app.notate.core.store.OutlineEntry
import app.notate.render.paper.PaperRenderer
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Writes pages of a document as a vector PDF that looks like the screen (R3-2): PDF source pages are imported untouched (text
 * stays selectable) with the handwriting appended on top, paper templates and ink are PDF paths, links and the outline
 * become PDF links and bookmarks. Cancelling the calling coroutine stops the export and removes the partial file.
 */
class PdfExporter(private val io: CoroutineDispatcher) {
    /**
     * Maps a point of the exported area (page coordinates, y down) to PDF user space: `u = a·x + c·y + e`, `v = b·x + d·y + f`
     * where x,y are relative to the area's top-left corner.
     */
    internal class PageMap(val a: Float, val b: Float, val c: Float, val d: Float, val e: Float, val f: Float, val area: Rect) {
        fun matrix() = Matrix(a, b, c, d, e, f)
        fun u(x: Float, y: Float) = a * (x - area.l) + c * (y - area.t) + e
        fun v(x: Float, y: Float) = b * (x - area.l) + d * (y - area.t) + f
    }

    private class Prepared(val info: PageInfo, val pd: PDPage, val map: PageMap, val items: List<Item>, val drawTemplate: Boolean)

    /** Exports to [out]; [progress] gets (pages done, pages total). Returns the number of PDF pages written. */
    suspend fun export(handle: DocumentHandle, options: ExportOptions, out: File, progress: (Int, Int) -> Unit): Int {
        handle.save.flush()
        val pages = ExportPlan.select(options.range, handle.editor.pages.value)
        val title = handle.editor.meta.value.title
        val outline = if (options.includeLinks) runCatching { handle.readOutline() }.getOrDefault(emptyList()) else emptyList()
        return withContext(io) {
            val sources = HashMap<AssetId, PDDocument?>()
            val doc = PDDocument()
            val tmp = File(out.parentFile, out.name + ".part")
            var ok = false
            try {
                out.parentFile?.mkdirs()
                val styles = PdfStyles()
                val prepared = pages.map { prepare(doc, handle, it, sources) }
                val byId = prepared.associateBy { it.info.id }
                val writer = InkPdfWriter(doc, handle::assetFile, styles)
                progress(0, prepared.size)
                for ((n, p) in prepared.withIndex()) {
                    coroutineContext.ensureActive()
                    drawPage(doc, handle, p, options, writer, styles, byId)
                    progress(n + 1, prepared.size)
                }
                if (options.includeLinks && outline.isNotEmpty()) buildOutline(doc, outline, byId)
                doc.documentInformation = PDDocumentInformation().apply { this.title = title; creator = "Notate" }
                coroutineContext.ensureActive()
                doc.save(tmp)
                if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true) }
                ok = true
                prepared.size
            } finally {
                runCatching { doc.close() }
                sources.values.forEach { runCatching { it?.close() } }
                tmp.delete()
                if (!ok) out.delete()
            }
        }
    }

    /** Creates the PDF page (imported from the source PDF, or blank) and the matrix that places our coordinates on it. */
    private suspend fun prepare(doc: PDDocument, handle: DocumentHandle, info: PageInfo, sources: MutableMap<AssetId, PDDocument?>): Prepared {
        val items = handle.readItems(info.id)
        val area = ExportPlan.area(info, items)
        val bg = info.background
        if (bg is Background.Pdf) {
            val src = sources.getOrPut(bg.asset) {
                handle.assetFile(bg.asset)?.let { f -> runCatching { PDDocument.load(f, MemoryUsageSetting.setupTempFileOnly()) }.getOrNull() }
            }
            if (src != null && bg.pageIndex in 0 until src.numberOfPages) {
                val pd = doc.importPage(src.getPage(bg.pageIndex))
                return Prepared(info, pd, sourcePageMap(pd, area), items, drawTemplate = false)
            }
        }
        val pd = PDPage(PDRectangle(area.width, area.height))
        doc.addPage(pd)
        return Prepared(info, pd, PageMap(1f, 0f, 0f, -1f, 0f, area.height, area), items, drawTemplate = true)
    }

    private fun drawPage(
        doc: PDDocument, handle: DocumentHandle, p: Prepared, options: ExportOptions, writer: InkPdfWriter, styles: PdfStyles,
        byId: Map<PageId, Prepared>,
    ) {
        val area = p.map.area
        PDPageContentStream(doc, p.pd, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            cs.transform(p.map.matrix())
            cs.transform(Matrix.getTranslateInstance(-area.l, -area.t))
            val bg = p.info.background
            if (options.includeTemplate && p.drawTemplate && bg is Background.Paper) {
                val canvas = PdfCanvas(cs, styles)
                try {
                    PaperRenderer().draw(canvas, p.info, bg, area)
                } finally {
                    canvas.finish()
                }
            }
            for (item in p.items.sortedWith(ExportPlan.ITEM_ORDER)) writer.draw(cs, item)
        }
        if (options.includeLinks) addLinks(handle, p, byId)
    }

    private fun addLinks(handle: DocumentHandle, p: Prepared, byId: Map<PageId, Prepared>) {
        val links = p.items.filterIsInstance<LinkItem>()
        if (links.isEmpty()) return
        val annotations = p.pd.annotations
        for (l in links) {
            val action = when (val t = l.target) {
                is LinkTarget.Url -> PDActionURI().also { it.setURI(t.url) }
                is LinkTarget.Page -> {
                    val target = byId[t.page]?.takeIf { t.doc == handle.id } ?: continue
                    PDActionGoTo().apply { destination = topOf(target) }
                }
            }
            val f = l.frame
            val xs = listOf(p.map.u(f.l, f.t), p.map.u(f.r, f.t), p.map.u(f.l, f.b), p.map.u(f.r, f.b))
            val ys = listOf(p.map.v(f.l, f.t), p.map.v(f.r, f.t), p.map.v(f.l, f.b), p.map.v(f.r, f.b))
            annotations.add(PDAnnotationLink().apply {
                rectangle = PDRectangle(xs.min(), ys.min(), xs.max() - xs.min(), ys.max() - ys.min())
                borderStyle = PDBorderStyleDictionary().apply { width = 0f }
                this.action = action
            })
        }
        p.pd.annotations = annotations
    }

    private fun topOf(target: Prepared, y: Float? = null): PDPageXYZDestination = PDPageXYZDestination().apply {
        page = target.pd
        left = 0
        top = target.map.v(target.map.area.l, y?.coerceIn(target.map.area.t, target.map.area.b) ?: target.map.area.t).toInt()
        zoom = 0f
    }

    /** The bookmarks of the document restricted to exported pages; entries of skipped pages hand their children to their parent. */
    private fun buildOutline(doc: PDDocument, entries: List<OutlineEntry>, byId: Map<PageId, Prepared>) {
        val children = entries.groupBy { it.parentId }
        val root = PDDocumentOutline()
        var count = 0
        fun add(parentId: Long?, into: PDOutlineNode) {
            for (e in children[parentId].orEmpty().sortedBy { it.ord }) {
                val target = e.page?.let { byId[it] }
                if (target == null) { add(e.id, into); continue }
                val item = PDOutlineItem().apply { title = e.title; destination = topOf(target, e.yPt) }
                into.addLast(item)
                count++
                add(e.id, item)
            }
        }
        add(null, root)
        if (count > 0) { root.openNode(); doc.documentCatalog.documentOutline = root }
    }

    companion object {
        /**
         * Page→PDF matrix of an imported source page: our coordinates are those of the page as displayed (rotation applied,
         * origin top left, y down); its content stream uses the unrotated crop box (origin bottom left, y up).
         */
        internal fun sourcePageMap(pd: PDPage, area: Rect): PageMap {
            val box = pd.cropBox
            val x0 = box.lowerLeftX; val y0 = box.lowerLeftY; val w0 = box.width; val h0 = box.height
            return when (((pd.rotation % 360) + 360) % 360) {
                90 -> PageMap(0f, 1f, 1f, 0f, x0, y0, area)
                180 -> PageMap(-1f, 0f, 0f, 1f, x0 + w0, y0, area)
                270 -> PageMap(0f, -1f, -1f, 0f, x0 + w0, y0 + h0, area)
                else -> PageMap(1f, 0f, 0f, -1f, x0, y0 + h0, area)
            }
        }
    }
}
