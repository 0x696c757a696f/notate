package app.notate.editor.pages

import android.content.Context
import android.widget.Toast
import app.notate.core.doc.editor.DocumentEditor
import app.notate.core.doc.pages.PageOps
import app.notate.core.model.Background
import app.notate.core.model.PageId
import app.notate.core.model.log.Logger
import app.notate.editor.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * What the page grid, the More menu and the pane's "Add page" button do to the page list: builds the edit with [PageOps],
 * loads pages when an edit needs their content, applies it and tells the user when it is refused. Main thread only.
 */
class PageActions(
    private val context: Context,
    private val editor: DocumentEditor,
    private val scope: CoroutineScope,
    private val logger: Logger,
) {
    /** Adds a page with the document's new-page template after [after] (null = at the start); [onAdded] gets its id. */
    fun addAfter(after: PageId?, onAdded: (PageId) -> Unit = {}) {
        applyAndReport({ PageOps.insertAfter(editor, after, editor.meta.value.newPageTemplate) }, onAdded)
    }

    fun insertBefore(page: PageId, onAdded: (PageId) -> Unit = {}) {
        applyAndReport({ PageOps.insertBefore(editor, page, editor.meta.value.newPageTemplate) }, onAdded)
    }

    fun duplicate(page: PageId, onAdded: (PageId) -> Unit = {}) {
        scope.launch {
            try {
                editor.ensureLoaded(page)
                applyAndReport({ PageOps.duplicate(editor, page) }, onAdded)
            } catch (e: Exception) {
                failed("Duplicating page $page failed", e)
            }
        }
    }

    /** Deletes [page] (Undo brings it back); the last page is refused with a message. */
    fun delete(page: PageId) {
        scope.launch {
            try {
                editor.ensureLoaded(page)
                val edit = PageOps.delete(editor, page)
                if (edit == null) toast(R.string.page_last_one) else editor.apply(edit)
            } catch (e: Exception) {
                failed("Deleting page $page failed", e)
            }
        }
    }

    fun move(page: PageId, toIndex: Int) {
        try {
            PageOps.move(editor, page, toIndex)?.let { editor.apply(it) }
        } catch (e: Exception) {
            failed("Moving page $page failed", e)
        }
    }

    /** Toggles the bookmark of [page]; not part of the undo history. */
    fun toggleBookmark(page: PageId) {
        val on = editor.pages.value.firstOrNull { it.id == page }?.bookmarked ?: return
        editor.apply(PageOps.setBookmark(editor, page, !on), undoable = false)
    }

    /** Gives [page] (or every page when [all]) the paper [bg]; PDF and web pages are left alone. */
    fun setTemplate(page: PageId, bg: Background.Paper, all: Boolean) {
        if (all) {
            PageOps.setTemplateAll(editor, bg)?.let { editor.apply(it) }
            return
        }
        val info = editor.pages.value.firstOrNull { it.id == page } ?: return
        if (info.background is Background.Pdf || info.background is Background.Web) {
            toast(R.string.template_paper_only)
            return
        }
        editor.apply(PageOps.setTemplate(editor, page, bg))
    }

    private fun applyAndReport(build: () -> app.notate.core.model.edit.Edit, onAdded: (PageId) -> Unit) {
        try {
            val before = editor.pages.value.map { it.id }.toSet()
            editor.apply(build())
            editor.pages.value.firstOrNull { it.id !in before }?.let { onAdded(it.id) }
        } catch (e: Exception) {
            failed("Adding a page failed", e)
        }
    }

    private fun failed(what: String, e: Exception) {
        logger.e(TAG, what, e)
        toast(R.string.page_failed)
    }

    private fun toast(res: Int) = Toast.makeText(context, res, Toast.LENGTH_SHORT).show()

    private companion object { const val TAG = "PageActions" }
}
