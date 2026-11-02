package app.notate

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.notate.core.doc.settings.Settings
import app.notate.core.model.Clock
import app.notate.core.model.log.Logger
import app.notate.core.store.library.LibraryStore
import app.notate.export.pkg.BackupEngine
import app.notate.export.pkg.BackupResult
import app.notate.library.settings.BackupStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Runs backups into the folder chosen in Settings and publishes [status] for the Settings screen. One run at a time,
 * whether started by "Back up now" or by [BackupWorker]. The last result survives restarts in the library's key-value table.
 */
class BackupController(
    private val context: Context,
    private val engine: BackupEngine,
    private val settings: Settings,
    private val library: () -> LibraryStore,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val logger: Logger,
) {
    private val mutex = Mutex()
    private val _status = MutableStateFlow(BackupStatus())
    val status: StateFlow<BackupStatus> = _status.asStateFlow()
    private val _folderName = MutableStateFlow<String?>(null)

    /** Display name of the backup folder; null when none is set or it is no longer accessible. */
    val folderName: StateFlow<String?> = _folderName.asStateFlow()

    init {
        scope.launch {
            try {
                val lib = library()
                _status.update { it.copy(lastAt = lib.getKv(KV_LAST_AT)?.toLongOrNull(), lastError = lib.getKv(KV_LAST_ERROR)) }
            } catch (e: Exception) {
                logger.w(TAG, "Reading the last backup state failed", e)
            }
            refreshFolder()
        }
    }

    /** The chosen folder, or null when none is set or the permission was lost. */
    fun folder(): DocumentFile? =
        settings.backupUri.value?.let { DocumentFile.fromTreeUri(context, Uri.parse(it)) }?.takeIf { it.exists() && it.canWrite() }

    fun refreshFolder() { _folderName.value = folder()?.name }

    /** Starts a backup in the app scope (it continues when Settings is left). */
    fun startNow() { scope.launch { runNow() } }

    /** Runs a backup and records the outcome. Returns null when no folder is available. */
    suspend fun runNow(): BackupResult? = mutex.withLock {
        val root = folder()
        if (root == null) {
            record(null, "Backup folder is not available")
            return null
        }
        _status.update { it.copy(running = true, done = 0, total = 0) }
        try {
            val result = engine.run(root) { done, total -> _status.update { s -> s.copy(done = done, total = total) } }
            record(if (result.failed == 0) clock.now() else null, result.firstError.takeIf { result.failed > 0 })
            result
        } catch (e: Exception) {
            logger.e(TAG, "Backup failed", e)
            record(null, e.message ?: e.javaClass.simpleName)
            null
        }
    }

    private suspend fun record(successAt: Long?, error: String?) {
        _status.update { it.copy(running = false, lastAt = successAt ?: it.lastAt, lastError = error) }
        try {
            val lib = library()
            if (successAt != null) lib.putKv(KV_LAST_AT, successAt.toString())
            lib.putKv(KV_LAST_ERROR, error)
        } catch (e: Exception) {
            logger.w(TAG, "Saving the backup state failed", e)
        }
    }

    private companion object {
        const val TAG = "BackupController"
        const val KV_LAST_AT = "backup.lastAt"
        const val KV_LAST_ERROR = "backup.lastError"
    }
}
