package app.notate

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.notate.core.doc.library.LibraryController

/** Daily job: deletes documents that have been in the Trash for more than 30 days (files first, then the index row). */
class TrashPurgeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = NotateApp.graph
        return try {
            val cutoff = graph.clock.now() - LibraryController.TRASH_DAYS * DAY_MS
            val store = graph.libraryStore
            val expired = store.trashedBefore(cutoff)
            for (id in expired) {
                graph.libraryDocs.deleteFiles(id)
                store.purge(id)
            }
            if (expired.isNotEmpty()) graph.logger.i(TAG, "Purged ${expired.size} expired trashed documents")
            Result.success()
        } catch (e: Exception) {
            graph.logger.e(TAG, "Trash purge failed", e)
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_NAME = "trash-purge"
        private const val TAG = "TrashPurgeWorker"
        private const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
