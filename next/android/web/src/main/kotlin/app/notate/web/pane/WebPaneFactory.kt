package app.notate.web.pane

import android.content.Context
import app.notate.core.doc.registry.DocumentHandle
import app.notate.core.input.PaneId
import app.notate.editor.EditorDeps
import app.notate.editor.PaneFactory
import app.notate.editor.pane.DocumentPane
import app.notate.editor.pane.PaneHost
import kotlinx.coroutines.CoroutineScope

/** Registered by the app for `DocKind.WEB`; the editor module cannot see this module. */
object WebPaneFactory : PaneFactory {
    override fun create(context: Context, handle: DocumentHandle, deps: EditorDeps, host: PaneHost, scope: CoroutineScope, id: PaneId): DocumentPane =
        WebPane(context, handle, deps, host, scope, id)
}
