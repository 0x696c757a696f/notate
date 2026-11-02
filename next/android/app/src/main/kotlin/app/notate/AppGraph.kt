package app.notate

import android.app.Application
import app.notate.core.doc.registry.DocumentOpener
import app.notate.core.doc.registry.DocumentRegistry
import app.notate.core.doc.registry.Opened
import app.notate.core.doc.settings.Settings
import app.notate.core.doc.tools.ToolController
import app.notate.core.input.EpdScheduler
import app.notate.editor.EditorDeps
import app.notate.core.model.DocKind
import app.notate.editor.EditorEnv
import app.notate.web.pane.WebPaneFactory
import app.notate.platform.epd.NoEpdPort
import app.notate.platform.epd.OnyxEpdPort
import app.notate.core.model.DocId
import app.notate.core.model.SystemClock
import app.notate.core.store.DocStoreFactory
import app.notate.core.store.doc.SqliteDocStoreFactory
import app.notate.core.store.library.LibraryStore
import app.notate.core.store.library.SqliteLibraryStore
import app.notate.platform.device.DeviceInfo
import app.notate.platform.fs.AppPaths
import app.notate.platform.log.AndroidLogger
import app.notate.platform.prefs.PrefsKeyValueStore
import app.notate.platform.sql.AndroidSqlDriverFactory
import app.notate.platform.thread.DispatcherSet
import app.notate.web.WebImporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Hand-written dependency graph (no DI framework). Built once in [NotateApp.onCreate]; main thread only. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppGraph(
    app: Application,
    val paths: AppPaths,
    val logger: AndroidLogger,
    val device: DeviceInfo,
) {
    val clock = SystemClock
    val dispatchers: DispatcherSet = DispatcherSet.create()
    val settings = Settings(PrefsKeyValueStore(app))
    val sqlDrivers = AndroidSqlDriverFactory()
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)

    /** Outlives every screen; flushing and other background work run here. */
    val appScope: CoroutineScope get() = scope
    val toolController = ToolController(settings)
    val epd = EpdScheduler<android.view.View>(if (device.isOnyx) OnyxEpdPort else NoEpdPort)

    /** One single-threaded IO lane per document, as the stores require. */
    val docStoreFactory: DocStoreFactory = SqliteDocStoreFactory(sqlDrivers, { dispatchers.io.limitedParallelism(1) }, clock)

    /** Opened lazily (it touches the disk); first use must be off the main thread. */
    val libraryStore: LibraryStore by lazy {
        SqliteLibraryStore.open(sqlDrivers.open(paths.libraryDb, readOnly = false), dispatchers.io.limitedParallelism(1), clock)
    }

    /** Imports EPUB / HTML books as web documents. */
    val webImporter = WebImporter(
        context = app,
        stagingRoot = paths.cacheDir,
        docDir = paths::docDir,
        docs = docStoreFactory,
        libraryProvider = { libraryStore },
        settings = settings,
        clock = clock,
        io = dispatchers.io,
        logger = logger,
        paneWidthDp = { app.resources.displayMetrics.let { it.widthPixels / it.density } },
    )

    val registry = DocumentRegistry(
        opener = object : DocumentOpener {
            override suspend fun open(id: DocId): Opened {
                val store = docStoreFactory.open(paths.docDir(id))
                return Opened(store, store.readMeta(), store.readPages(), store.maxIds())
            }
        },
        scope = scope,
        clock = clock,
        main = dispatchers.main,
        io = dispatchers.io,
    )

    val documents = DocumentService(paths, docStoreFactory, { libraryStore }, dispatchers, clock)

    init {
        EditorEnv.deps = EditorDeps(
            registry = registry,
            settings = settings,
            toolController = toolController,
            dispatchers = dispatchers,
            device = device,
            epd = epd,
            clock = clock,
            appScope = appScope,
            logger = logger,
            onDocumentClosed = { id -> appScope.launch { documents.touch(id) } },
            paneFactories = mapOf(DocKind.WEB to WebPaneFactory),
            thumbsDir = { id -> java.io.File(paths.docDir(id), "thumbs") },
        )
    }
}
