package app.notate.web.sandbox

import android.webkit.WebStorage
import app.notate.core.model.DocId
import app.notate.core.web.WebOrigin

/** Removes what a web document left in the WebView's storage (localStorage, IndexedDB); called when the document is deleted. */
object WebDocCleanup {
    fun clear(id: DocId) {
        WebStorage.getInstance().deleteOrigin(WebOrigin.base(id.value).trimEnd('/'))
    }
}
