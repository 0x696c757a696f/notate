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
import app.notate.core.workspace.WorkspaceStore
import app.notate.library.LibraryDeps
import app.notate.platform.device.DeviceInfo
import app.notate.platform.fs.AppPaths
import app.notate.platform.log.AndroidLogger
import app.notate.platform.prefs.PrefsKeyValueStore
import app.notate.platform.sql.AndroidSqlDriverFactory
import app.notate.platform.thread.DispatcherSet
import app.notate.pdf.PdfBackgrounds
import app.notate.pdf.PdfImporter
import app.notate.render.ink.GeometryCache
import app.notate.render.ink.InkRenderer
import app.notate.render.paper.BackgroundRendererSet
import app.notate.render.thumb.ThumbnailRenderer
import app.notate.export.legacy.LegacyImporter
import app.notate.export.pkg.BackupEngine
import app.notate.export.pkg.PackageExporter
import app.notate.export.pkg.PackageImporter
import app.notate.export.legacy.LegacyLocalMigrator
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

    /** The open tabs, saved in the library index; first use must be off the main thread. */
    val workspaceStore: WorkspaceStore by lazy { WorkspaceStore(libraryStore, logger) }

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

    /** Imports PDFs as documents (R3-1). */
    val pdfImporter = PdfImporter(
        context = app,
        stagingRoot = paths.cacheDir,
        docDir = paths::docDir,
        docs = docStoreFactory,
        libraryProvider = { libraryStore },
        clock = clock,
        io = dispatchers.io,
        logger = logger,
    )

    /** PDF page backgrounds for every open PDF document; the PDF thread owns the renderers. */
    val pdfBackgrounds = PdfBackgrounds(dispatchers.pdf)

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

    val libraryDocs = LibraryDocsImpl(paths, documents, docStoreFactory, sqlDrivers, registry, { libraryStore }, dispatchers, clock)

    val covers = CoverService(
        paths, registry, { libraryStore },
        ThumbnailRenderer(BackgroundRendererSet(), InkRenderer(GeometryCache()), dispatchers.render),
        dispatchers, scope, logger,
    )

    /** Writes documents as `.notate` packages (Export…, backups). */
    val packageExporter = PackageExporter(docStoreFactory, { libraryStore }, paths::docDir, { registry.flushAll() }, dispatchers.io, clock::now)

    /** Reads `.notate` packages (Import Notate file, restore). */
    val packageImporter = PackageImporter(
        docs = docStoreFactory, library = { libraryStore }, docDir = paths::docDir,
        stagingRoot = java.io.File(paths.cacheDir, "package-import"), drivers = sqlDrivers, clock = clock, io = dispatchers.io,
        exporter = packageExporter, isOpen = registry::isOpen, logger = logger, onImported = { id -> covers.generateNow(id) },
    )

    val backupEngine = BackupEngine(
        app.contentResolver, packageExporter, packageImporter, { libraryStore }, sqlDrivers,
        java.io.File(paths.cacheDir, "backup"), clock, dispatchers.io, logger,
    )

    /** Settings → Backup: folder, schedule and status. */
    val backup = BackupController(app, backupEngine, settings, { libraryStore }, scope, clock, logger)

    /** Copies old Notate notes into the library (Settings → Import from old Notate…, and the in-place migration). */
    val legacyImporter = LegacyImporter(
        docs = docStoreFactory,
        libraryProvider = { libraryStore },
        docDir = paths::docDir,
        stagingRoot = paths.cacheDir,
        clock = clock,
        io = dispatchers.io,
        logger = logger,
        onImported = { id -> covers.generateNow(id) },
    )

    /** One-time move of the old app's own data; R7-1 starts it once Notate Next replaces Notate. */
    val legacyMigrator = LegacyLocalMigrator(legacyImporter, { libraryStore }, clock)

    /** Bookshelf dependencies; [libraryStore] must have been opened off the main thread first. */
    fun libraryDeps() = LibraryDeps(
        store = libraryStore, docs = libraryDocs, clock = clock, logger = logger, coverFile = paths::coverFile,
        defaultTemplate = settings.defaultTemplate, onNotebookCreated = covers::generateNow,
    )

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
            onDocumentClosed = { id ->
                appScope.launch { documents.touch(id) }
                covers.onClosed(id)
            },
            paneFactories = mapOf(DocKind.WEB to WebPaneFactory),
            openSettings = { ctx -> ctx.startActivity(SettingsActivity.intent(ctx)) },
            thumbsDir = { id -> java.io.File(paths.docDir(id), "thumbs") },
            pdfBackgrounds = pdfBackgrounds,
            workspaceStore = { workspaceStore },
            libraryStore = { libraryStore },
            onOpenFailed = OpenFailedDialog::show,
            libraryIntent = { ctx ->
                android.content.Intent(ctx, LibraryActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            },
        )
    }
}
