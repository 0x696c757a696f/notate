package app.notate.editor

import android.view.View
import app.notate.core.doc.registry.DocumentRegistry
import app.notate.core.doc.settings.Settings
import app.notate.core.doc.tools.ToolController
import app.notate.core.input.EpdScheduler
import app.notate.core.model.Clock
import app.notate.core.model.DocId
import app.notate.core.model.log.Logger
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
    /** Directory for a document's cached page thumbnails; null disables the disk cache. */
    val thumbsDir: ((DocId) -> java.io.File)? = null,
    /** Draws PDF page backgrounds (implemented by `:next:pdf`); null = PDF pages show white. */
    val pdfBackgrounds: PdfBackgroundFactory? = null,
)

/** Holder filled by the application at startup. */
object EditorEnv {
    lateinit var deps: EditorDeps
}
