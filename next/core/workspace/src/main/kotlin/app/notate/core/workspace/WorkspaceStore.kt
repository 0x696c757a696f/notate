package app.notate.core.workspace

import app.notate.core.model.log.Logger
import app.notate.core.store.library.LibraryStore
import kotlinx.serialization.json.Json

/**
 * Keeps the [Workspace] in `library.db` (`kv["workspace"]`). Documents that were deleted or moved to the trash are dropped
 * when the workspace is loaded. Call from any thread; the library store does the work on its own dispatcher.
 */
class WorkspaceStore(private val library: LibraryStore, private val logger: Logger = Logger.NONE) {
    /** The stored workspace, or an empty one when nothing (readable) is stored. */
    suspend fun load(): Workspace {
        val text = library.getKv(KEY) ?: return Workspace()
        val ws = try {
            WorkspaceOps.normalize(json.decodeFromString(Workspace.serializer(), text))
        } catch (e: Exception) {
            logger.e(TAG, "Stored workspace is unreadable; starting empty", e)
            return Workspace()
        }
        val alive = ws.tabs.filter { t -> library.get(t.doc)?.let { it.trashed == null } == true }.map { it.doc }.toSet()
        return WorkspaceOps.prune(ws) { it in alive }
    }

    suspend fun save(ws: Workspace) {
        library.putKv(KEY, if (ws.isEmpty) null else json.encodeToString(Workspace.serializer(), ws))
    }

    companion object {
        const val KEY = "workspace"
        private const val TAG = "WorkspaceStore"
        internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
