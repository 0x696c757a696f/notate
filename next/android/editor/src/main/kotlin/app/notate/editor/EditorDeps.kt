package app.notate.editor

import android.content.Context
import android.view.View
import app.notate.core.doc.registry.DocumentHandle
import app.notate.core.model.DocKind
import app.notate.editor.pane.DocumentPane
import app.notate.editor.pane.PaneHost
import app.notate.core.doc.registry.DocumentRegistry
import app.notate.core.doc.settings.Settings
import app.notate.core.doc.tools.ToolController
import app.notate.core.input.EpdScheduler
import app.notate.core.model.Clock
import app.notate.core.model.DocId
import app.notate.core.model.log.Logger
import app.notate.platform.device.DeviceInfo
import app.notate.platform.thread.DispatcherSet
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
    val thumbsDir: ((DocId) -> java.io.File)? = null,
)

/** Creates the pane that shows a document of some kind. */
fun interface PaneFactory {
    fun create(context: Context, handle: DocumentHandle, deps: EditorDeps, host: PaneHost, scope: CoroutineScope): DocumentPane
}

/** Holder filled by the application at startup. */
object EditorEnv {
    lateinit var deps: EditorDeps
}
