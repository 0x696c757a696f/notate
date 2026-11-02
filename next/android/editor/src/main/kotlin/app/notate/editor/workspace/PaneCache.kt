package app.notate.editor.workspace

import app.notate.core.model.DocId

/**
 * Least-recently-used set of at most [max] live panes (02 §7). Looking an entry up or putting it in makes it the most recent.
 * When a [put] pushes the cache over its size, the oldest entries that are not in `keep` go to [onEvict], which must release
 * them. Main thread only.
 */
class PaneCache<T : Any>(private val max: Int = DEFAULT_MAX, private val onEvict: (DocId, T) -> Unit) {
    private val map = LinkedHashMap<DocId, T>(8, 0.75f, true)

    val size: Int get() = map.size

    /** The cached pane of [doc], now the most recently used; null on a miss. */
    operator fun get(doc: DocId): T? = map[doc]

    /** Looks [doc] up without changing the order. */
    fun peek(doc: DocId): T? = map.entries.firstOrNull { it.key == doc }?.value

    /** All cached panes, oldest first. */
    fun values(): List<T> = map.values.toList()

    fun put(doc: DocId, pane: T, keep: Set<DocId> = emptySet()) {
        map[doc] = pane
        val victims = map.keys.filter { it != doc && it !in keep }
        var over = map.size - max
        for (k in victims) {
            if (over <= 0) break
            val v = map.remove(k) ?: continue
            over--
            onEvict(k, v)
        }
    }

    /** Takes [doc] out without calling [onEvict]; the caller releases the pane. */
    fun remove(doc: DocId): T? = map.remove(doc)

    /** Takes every pane out without calling [onEvict]. */
    fun drain(): List<T> = map.values.toList().also { map.clear() }

    companion object {
        const val DEFAULT_MAX = 3
    }
}
