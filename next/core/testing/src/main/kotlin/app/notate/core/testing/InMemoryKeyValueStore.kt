package app.notate.core.testing

import app.notate.core.doc.settings.KeyValueStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/** [KeyValueStore] backed by a map, for tests. */
class InMemoryKeyValueStore : KeyValueStore {
    val map = HashMap<String, String>()
    private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override fun getString(k: String): String? = map[k]
    override fun putString(k: String, v: String?) {
        if (v == null) map.remove(k) else map[k] = v
        _changes.tryEmit(k)
    }
    override val changes: Flow<String> get() = _changes
}
