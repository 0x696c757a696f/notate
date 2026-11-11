package app.notate.editor

import android.content.Context
import android.content.Intent
import android.view.View
import app.notate.core.doc.registry.DocumentHandle
import app.notate.core.input.PaneId
import app.notate.core.model.DocKind
import app.notate.editor.pane.DocumentPane
import app.notate.editor.pane.PaneHost
import app.notate.core.doc.registry.DocumentRegistry
import app.notate.core.doc.select.InkClipboard
import app.notate.core.doc.settings.Settings
import app.notate.core.doc.tools.ToolController
import app.notate.core.input.EpdScheduler
import app.notate.core.model.Clock
import app.notate.core.model.DocId
import app.notate.core.model.log.Logger
import app.notate.core.store.library.LibraryStore
import app.notate.core.workspace.WorkspaceStore
import app.notate.platform.device.DeviceInfo
import app.notate.platform.thread.DispatcherSet
import app.notate.render.tile.PdfBackgroundFactory
import kotlinx.coroutines.CoroutineScope

/** Everything the editor needs from the app's dependency graph (the editor module cannot see `:next:app`). */
class EditorDeps(
    val registry: DocumentRegistry,
    val settings: Settings,
    val toolController: ToolController,
    val dispatchers: DispatcherSet,
    val device: DeviceInfo,
    val epd: EpdScheduler<View>,
    val clock: Clock,
    /** Outlives every screen; used for work that must finish after an activity is gone (flushing). */
    val appScope: CoroutineScope,
    val logger: Logger,
    /** Called on the main thread when an editor screen closes a document. */
    val onDocumentClosed: (DocId) -> Unit = {},
    /** Builds the pane for a document kind other than notebooks (the editor module cannot see `:next:web`). */
    val paneFactories: Map<DocKind, PaneFactory> = emptyMap(),
    /** Directory for a document's cached page thumbnails; null disables the disk cache. */
    /** Opens the Settings screen (a separate activity of the app); Back returns to the editor. */
    val openSettings: (Context) -> Unit = {},
    val thumbsDir: ((DocId) -> java.io.File)? = null,
    /** Draws PDF page backgrounds (implemented by `:next:pdf`); null = PDF pages show white. */
    val pdfBackgrounds: PdfBackgroundFactory? = null,
    /** Ink clipboard shared by every pane and document of the process (one [EditorDeps] per process). */
    val clipboard: InkClipboard = InkClipboard(),
    /** Saves and restores the open tabs; null keeps them in memory only. Resolved off the main thread (it opens `library.db`). */
    val workspaceStore: (() -> WorkspaceStore)? = null,
    /** The library index, for the document picker behind "+"; null disables the picker. */
    val libraryStore: (() -> LibraryStore)? = null,
    /** An intent that brings the bookshelf to the front (the editor module cannot see `:next:app`). */
    val libraryIntent: ((Context) -> Intent)? = null,
)

/** Creates the pane that shows a document of some kind. */
fun interface PaneFactory {
    fun create(context: Context, handle: DocumentHandle, deps: EditorDeps, host: PaneHost, scope: CoroutineScope, id: PaneId): DocumentPane
}

/** Holder filled by the application at startup. */
object EditorEnv {
    lateinit var deps: EditorDeps
}
