package app.notate.pdf

import android.content.Context
import app.notate.core.model.log.Logger
import app.notate.core.store.outline.RawOutlineNode
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import app.notate.core.store.outline.OutlineMapper
import java.io.File

/** One-time pdfbox setup; call from `Application.onCreate`. */
object PdfSupport {
    fun init(context: Context) = PDFBoxResourceLoader.init(context.applicationContext)
}

/** Reads a PDF's bookmarks (table of contents). */
fun interface PdfOutlineReader {
    /** Top-level outline nodes of [file]; empty when there is none. Throws on unreadable files (the importer treats that as "no outline"). */
    fun read(file: File): List<RawOutlineNode>

    companion object {
        /** pdfbox-android. Destinations that cannot be resolved give nodes with a null page, which the mapper skips. */
        fun pdfbox(logger: Logger): PdfOutlineReader = PdfOutlineReader { file ->
            PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                val root = doc.documentCatalog.documentOutline ?: return@use emptyList()
                OutlineWalker(doc, logger).children(root, 0)
            }
        }
    }
}

private class OutlineWalker(private val doc: PDDocument, private val logger: Logger) {
    private var budget = OutlineMapper.MAX_ENTRIES

    fun children(parent: PDOutlineNode, depth: Int): List<RawOutlineNode> {
        if (depth >= OutlineMapper.MAX_DEPTH) return emptyList()
        val out = ArrayList<RawOutlineNode>()
        var item: PDOutlineItem? = parent.firstChild
        while (item != null && budget > 0) {
            budget--
            out += node(item, depth)
            item = item.nextSibling
        }
        return out
    }

    private fun node(item: PDOutlineItem, depth: Int): RawOutlineNode {
        var pageIndex: Int? = null
        var y: Float? = null
        try {
            val dest = item.destination ?: (item.action as? PDActionGoTo)?.destination
            val pd: PDPageDestination? = when (dest) {
                is PDPageDestination -> dest
                is PDNamedDestination -> doc.documentCatalog.findNamedDestinationPage(dest)
                else -> null
            }
            val idx = pd?.retrievePageNumber() ?: -1
            if (idx >= 0) {
                pageIndex = idx
                val top = (pd as? PDPageXYZDestination)?.top ?: -1
                val page = doc.getPage(idx)
                if (top >= 0 && page.rotation % 360 == 0) y = (page.cropBox.height - top).coerceAtLeast(0f)
            }
        } catch (e: Exception) {
            logger.w("PdfOutlineReader", "Outline item '${item.title}' has an unusable destination", e)
        }
        return RawOutlineNode(item.title ?: "", pageIndex, y, children(item, depth + 1))
    }
}
