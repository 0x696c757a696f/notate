package app.notate.export.legacy

import java.io.File
import java.io.InputStream

/** One old `.notate` note found by [LegacyScanner]. [open] streams the container ZIP; [directory] is set for an unpacked session folder. */
class LegacyEntry(
    val title: String,
    /** Folder names between the chosen root and the file, outermost first; they become library folders. */
    val folders: List<String>,
    val modified: Long,
    val size: Long,
    /** The old `CanvasData.uuid`; null for very old notes that never got one. */
    val uuid: String?,
    private val opener: () -> InputStream?,
    val directory: File? = null,
) {
    fun open(): InputStream? = opener()
}

/** An old web document folder (`webdocs/<id>/`); only reachable from the app's own private storage. */
class LegacyWebEntry(val dir: File)

class ImportFailure(val name: String, val reason: String)

/** What a scan of a folder found. [packages] counts new-format packages that belong to the package importer, not to this one. */
class ScanResult(val notes: List<LegacyEntry>, val packages: Int, val unreadable: List<ImportFailure>)

class ImportReport(
    val total: Int,
    val imported: Int,
    /** Already imported earlier (found through `kv["legacy:<uuid>"]`). */
    val alreadyImported: Int,
    val failures: List<ImportFailure>,
    /** Number of mapping warnings (dropped images, skipped strokes, …) across imported notes. */
    val warnings: Int,
    val cancelled: Boolean,
)

/** Live state of a running import, shown by the screen and the notification. */
sealed interface LegacyImportState {
    data object Idle : LegacyImportState
    data class Running(val done: Int, val total: Int, val current: String) : LegacyImportState
    data class Finished(val report: ImportReport) : LegacyImportState
}
