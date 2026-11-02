package app.notate.web

import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import app.notate.core.doc.registry.DocumentHandle
import app.notate.core.doc.registry.DocumentOpener
import app.notate.core.doc.registry.DocumentRegistry
import app.notate.core.doc.registry.Opened
import app.notate.core.doc.settings.Settings
import app.notate.core.doc.tools.ToolController
import app.notate.core.input.InkArbiter
import app.notate.core.model.Background
import app.notate.core.model.DocId
import app.notate.core.model.DocKind
import app.notate.core.model.PageId
import app.notate.core.model.PageInfo
import app.notate.core.model.SystemClock
import app.notate.core.model.WebDocSettings
import app.notate.core.model.log.Logger
import app.notate.core.store.DocStore
import app.notate.core.store.doc.SqliteDocStoreFactory
import app.notate.core.testing.FakeClock
import app.notate.core.testing.InMemoryKeyValueStore
import app.notate.core.testing.meta
import app.notate.editor.EditorDeps
import app.notate.editor.pane.PaneHost
import app.notate.platform.device.DeviceInfo
import app.notate.platform.epd.NoEpdPort
import app.notate.platform.sql.AndroidSqlDriverFactory
import app.notate.platform.thread.DispatcherSet
import app.notate.web.pane.WebPane
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeout
import java.nio.file.Files

/** A real SQLite web document with one chapter and a [WebPane] over it, laid out like a Boox screen. */
class WebPaneHarness {
    val dir = Files.createTempDirectory("webpane").toFile()
    val arbiter = InkArbiter()
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val factory = SqliteDocStoreFactory(AndroidSqlDriverFactory(), { Dispatchers.IO }, SystemClock)
    val settings = Settings(InMemoryKeyValueStore()).also { it.holdShapeMs.set(0) }
    val toolController = ToolController(settings)
    var openedStore: DocStore? = null
    lateinit var handle: DocumentHandle
    lateinit var pane: WebPane
    lateinit var registry: DocumentRegistry
    val chapter = PageId(1)

    suspend fun open(web: WebDocSettings = WebDocSettings()): WebPane {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val page = PageInfo(chapter, 1.0, 600f, null, Background.Web("ch1.xhtml", 800f, 1), inkRev = 0, modifiedAt = 0)
        val meta = meta(kind = DocKind.WEB).copy(web = web)
        factory.create(dir, meta, listOf(page)).close()
        registry = DocumentRegistry(
            opener = object : DocumentOpener {
                override suspend fun open(id: DocId): Opened {
                    val store = factory.open(dir)
                    openedStore = store
                    return Opened(store, store.readMeta(), store.readPages(), store.maxIds())
                }
            },
            scope = appScope, clock = SystemClock, main = Dispatchers.Unconfined, io = Dispatchers.IO,
        )
        handle = registry.acquire(meta.id)
        val deps = EditorDeps(
            registry, settings, toolController,
            DispatcherSet(Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.IO),
            DeviceInfo(false, "test", 227f, 4096f, false), app.notate.core.input.EpdScheduler(NoEpdPort), FakeClock(), appScope, Logger.NONE,
        )
        val host = object : PaneHost {
            override val arbiter = this@WebPaneHarness.arbiter
            override fun excludeRects() = emptyList<android.graphics.Rect>()
            override fun onDriverStroke(begin: Boolean, timeMs: Long) = Unit
        }
        pane = WebPane(context, handle, deps, host, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        val v = pane.view
        v.measure(View.MeasureSpec.makeMeasureSpec(1404, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1872, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 1404, 1872)
        withTimeout(10_000) { handle.editor.ensureLoaded(chapter) }
        return pane
    }

    suspend fun close() {
        pane.release()
        handle.save.flush()
        handle.save.close()
        openedStore?.close()
    }
}
