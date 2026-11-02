package app.notate.core.doc.links

import app.notate.core.doc.editor.DocumentEditor
import app.notate.core.geometry.Rect
import app.notate.core.model.DocId
import app.notate.core.model.ItemBounds
import app.notate.core.model.LinkItem
import app.notate.core.model.LinkTarget
import app.notate.core.model.PageId
import app.notate.core.model.edit.Edit

/** Where the UI should go after a link was activated. */
sealed interface LinkAction {
    /** Same document: scroll to [page]. */
    data class GoToPage(val page: PageId) : LinkAction
    /** Another document (a new tab after R5-1): open [doc] at [page]. */
    data class OpenDocument(val doc: DocId, val page: PageId) : LinkAction
    /** Web address; the UI asks "Open link in browser?" before it opens [url]. */
    data class OpenUrl(val url: String) : LinkAction
}

/** Maps a link target to the action the UI performs, and creates link items. */
object LinkResolver {
    /** [current] is the document the link was tapped in. */
    fun resolve(target: LinkTarget, current: DocId): LinkAction = when (target) {
        is LinkTarget.Page -> if (target.doc == current) LinkAction.GoToPage(target.page) else LinkAction.OpenDocument(target.doc, target.page)
        is LinkTarget.Url -> LinkAction.OpenUrl(target.url)
    }

    /** The link at a page point (topmost of the links whose frame contains it), or null. */
    fun hit(editor: DocumentEditor, page: PageId, x: Float, y: Float): LinkItem? =
        editor.content(page)?.query(Rect(x - 1f, y - 1f, x + 1f, y + 1f))?.filterIsInstance<LinkItem>()
            ?.filter { it.frame.contains(x, y) }?.maxByOrNull { it.order }

    /** Adds a link with [frame] (the selection's box) pointing at [target]. Only http(s) and mailto URLs are accepted. */
    fun create(editor: DocumentEditor, page: PageId, frame: Rect, target: LinkTarget): LinkItem? {
        if (target is LinkTarget.Url && !isSafeUrl(target.url)) return null
        if (frame.isEmpty) return null
        val link = LinkItem(editor.newItemId(), editor.nextOrder(page), frame, "", target, ItemBounds.frameBounds(frame, 0f))
        editor.apply(Edit.Items(page, emptyList(), listOf(link)))
        return link
    }

    fun isSafeUrl(url: String): Boolean {
        val u = url.trim().lowercase()
        return u.startsWith("http://") || u.startsWith("https://")
    }
}
