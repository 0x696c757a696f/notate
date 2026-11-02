package app.notate.export.legacy

import android.content.Context
import app.notate.core.model.Clock
import app.notate.core.store.library.LibraryStore
import java.io.File

/**
 * Moves the old app's data into the new store when Notate Next replaces Notate in place (R7-1 calls this at startup).
 * Reads `filesDir/projects/` (local notes, folder tree kept) and `filesDir/webdocs/` (books). Runs once: the time is saved in
 * `kv["legacy_migrated"]`. Old data stays on disk; notes that fail are reported and left alone.
 */
class LegacyLocalMigrator(
    private val importer: LegacyImporter,
    private val library: () -> LibraryStore,
    private val clock: Clock,
) {
    /** True when there is nothing left to migrate (already done, or no old data exists). Blocking; call on IO. */
    suspend fun isDone(): Boolean = library().getKv(KV_DONE) != null

    /** Returns null when the migration already ran; otherwise the report. A cancelled run is not marked done and resumes next time. */
    suspend fun migrate(ctx: Context, onProgress: (done: Int, total: Int, current: String) -> Unit = { _, _, _ -> }, stopRequested: () -> Boolean = { false }): ImportReport? =
        migrate(ctx.filesDir, onProgress, stopRequested)

    suspend fun migrate(filesDir: File, onProgress: (done: Int, total: Int, current: String) -> Unit = { _, _, _ -> }, stopRequested: () -> Boolean = { false }): ImportReport? {
        if (isDone()) return null
        val notes = File(filesDir, "projects").takeIf { it.isDirectory }?.let { LegacyScanner.scanDir(it).notes }.orEmpty()
        val webs = File(filesDir, "webdocs").listFiles()?.filter { File(it, "meta.json").isFile }?.sortedBy { it.name }
            ?.map(::LegacyWebEntry).orEmpty()
        val report = importer.importAll(notes, webs, onProgress, stopRequested)
        if (!report.cancelled) library().putKv(KV_DONE, clock.now().toString())
        return report
    }

    companion object {
        const val KV_DONE = "legacy_migrated"
    }
}
