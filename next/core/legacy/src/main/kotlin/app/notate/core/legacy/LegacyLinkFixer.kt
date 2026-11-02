package app.notate.core.legacy

import app.notate.core.model.DocId
import app.notate.core.model.LinkItem
import app.notate.core.model.LinkTarget
import app.notate.core.model.PageId

/**
 * Rewrites internal links of freshly mapped documents from old note UUIDs to new [DocId]s (03-data-model §9).
 * The mapper emits internal links as `Url("notate-legacy://<uuid>")`; after a batch is mapped, [fix] resolves them.
 */
object LegacyLinkFixer {
    const val PENDING_PREFIX = "notate-legacy://"
    /** Target of a link whose destination was not part of the import; the link stays visible but opens nothing. */
    const val INVALID_URL = "notate:invalid-link"

    /**
     * Returns copies of [docs] with internal links resolved. [known] maps old UUIDs of notes imported earlier to their new ids
     * (from `kv["legacy:<uuid>"]`); documents in [docs] take precedence. Unresolved links become [INVALID_URL] with a warning.
     */
    fun fix(docs: List<MappedDocument>, known: Map<String, DocId> = emptyMap()): List<MappedDocument> {
        val targets = HashMap<String, Pair<DocId, PageId>>()
        for ((u, id) in known) targets[u] = id to PageId(1)
        for (d in docs) d.legacyUuid?.let { targets[it] = d.meta.id to (d.pages.firstOrNull()?.id ?: PageId(1)) }
        return docs.map { doc ->
            val warnings = ArrayList(doc.warnings)
            var changed = false
            val items = doc.items.mapValues { (_, list) ->
                list.map { item ->
                    val t = (item as? LinkItem)?.target as? LinkTarget.Url
                    if (t == null || !t.url.startsWith(PENDING_PREFIX)) return@map item
                    changed = true
                    val uuid = t.url.removePrefix(PENDING_PREFIX)
                    val dest = targets[uuid]
                    if (dest != null) item.copy(target = LinkTarget.Page(dest.first, dest.second))
                    else {
                        warnings.add("Link \"${item.label}\" points to a note that was not imported ($uuid)")
                        item.copy(target = LinkTarget.Url(INVALID_URL))
                    }
                }
            }
            if (!changed) doc else MappedDocument(
                doc.meta, doc.pages, items, doc.assets, doc.tags, doc.tagColors, warnings, doc.legacyUuid, doc.webContent,
            )
        }
    }
}
