package app.notate.export.legacy

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide hand-over between the import screen and the foreground service: the scanned notes (they hold stream openers,
 * so they cannot travel in an Intent) and the running state. Main thread only.
 */
object LegacyImportSession {
    private val _state = MutableStateFlow<LegacyImportState>(LegacyImportState.Idle)
    val state: StateFlow<LegacyImportState> = _state

    /** The notes chosen on the preview screen; consumed by the service. */
    var pending: List<LegacyEntry> = emptyList()

    var job: Job? = null

    /** Set by Cancel; the importer stops after the note it is working on. */
    @Volatile var stopRequested = false

    fun running(done: Int, total: Int, current: String) { _state.value = LegacyImportState.Running(done, total, current) }
    fun finished(report: ImportReport) { job = null; _state.value = LegacyImportState.Finished(report) }

    /** Called when the result has been shown. */
    fun reset() { pending = emptyList(); stopRequested = false; _state.value = LegacyImportState.Idle }

    fun cancel() { stopRequested = true }
}
