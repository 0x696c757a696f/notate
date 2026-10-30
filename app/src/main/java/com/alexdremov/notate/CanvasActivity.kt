@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.alexdremov.notate

import android.content.Intent
import android.graphics.RectF
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.view.WindowCompat
import androidx.core.view.doOnLayout
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.alexdremov.notate.data.LinkType
import com.alexdremov.notate.databinding.ActivityMainBinding
import com.alexdremov.notate.export.CanvasExportCoordinator
import com.alexdremov.notate.model.ToolType
import com.alexdremov.notate.model.ToolbarItem
import com.alexdremov.notate.ui.OnyxCanvasView
import com.alexdremov.notate.ui.SettingsSidebarController
import com.alexdremov.notate.ui.SidebarCoordinator
import com.alexdremov.notate.ui.ToolbarCoordinator
import com.alexdremov.notate.ui.export.ExportAction
import com.alexdremov.notate.ui.tabs.DocKind
import com.alexdremov.notate.ui.tabs.DocTab
import com.alexdremov.notate.ui.tabs.SplitOrientation
import com.alexdremov.notate.ui.tabs.SplitState
import com.alexdremov.notate.ui.tabs.Workspace
import com.alexdremov.notate.ui.tabs.WorkspaceStore
import com.alexdremov.notate.ui.toolbar.MainToolbar
import com.alexdremov.notate.ui.view.FloatingWindowView
import com.alexdremov.notate.util.Logger
import com.alexdremov.notate.vm.DrawingViewModel
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import androidx.compose.ui.geometry.Rect as ComposeRect

class CanvasActivity : AppCompatActivity() {
    companion object {
        /** Optional content URI of a PDF to import into the opened note. */
        const val EXTRA_IMPORT_PDF_URI = "IMPORT_PDF_URI"
        private const val STATE_PDF_IMPORT_CONSUMED = "pdf_import_consumed"
    }

    private lateinit var binding: ActivityMainBinding
    private val viewModel: DrawingViewModel by viewModels()
    private var activePenPopup: com.alexdremov.notate.ui.dialog.PenSettingsPopup? = null
    private var isGridOpen = false

    // Every state that turns the stylus into a pointer; drives viewModel.setDrawingEnabled.
    private val inkGate =
        com.alexdremov.notate.ui.input.InkGate { allowed -> viewModel.setDrawingEnabled(allowed) }
    private val isToolbarHorizontal = mutableStateOf(true)
    private var progressDialog: androidx.appcompat.app.AlertDialog? = null

    private lateinit var sidebarCoordinator: SidebarCoordinator
    private lateinit var sidebarController: SettingsSidebarController
    private lateinit var toolbarCoordinator: ToolbarCoordinator
    private lateinit var exportCoordinator: CanvasExportCoordinator

    // Repository retained for SyncManager, but session management is delegated to ViewModel
    private lateinit var canvasRepository: com.alexdremov.notate.data.CanvasRepository
    private lateinit var projectRepository: com.alexdremov.notate.data.ProjectRepository
    private lateinit var syncManager: com.alexdremov.notate.data.SyncManager

    private var autoSaveJob: Job? = null

    private var currentCanvasPath: String? = null
    private var pendingSearchBounds: RectF? = null

    // PDF to import once the session is loaded (new note created from "Import PDF").
    private var pendingPdfImport: Uri? = null
    private var isFixedPageState: androidx.compose.runtime.MutableState<Boolean>? = null

    // Floating Window State
    private var floatingWindow: FloatingWindowView? = null
    private var floatingSession: com.alexdremov.notate.data.CanvasSession? = null

    // Tabs and split view
    private var workspace = Workspace()
    private var secondary: SecondaryPane? = null
    private var secondaryActive = false
    private var isSwitchingTabs = false
    private var pendingSplitOrientation = SplitOrientation.SIDE_BY_SIDE
    private val activeCanvasState = mutableStateOf<OnyxCanvasView?>(null)
    private var progressCallback: ((isVisible: Boolean, message: String?, progress: Int) -> Unit)? = null

    /** The canvas the pen, toolbar and menus act on: the focused pane in split view. */
    private val activeCanvas: OnyxCanvasView
        get() = (if (secondaryActive) secondary?.canvas else null) ?: binding.canvasView

    private val allCanvases: List<OnyxCanvasView>
        get() = listOfNotNull(binding.canvasView, secondary?.canvas)

    private fun activePath(): String? = if (secondaryActive) secondary?.path else currentCanvasPath

    private fun activeSession(): com.alexdremov.notate.data.CanvasSession? =
        if (secondaryActive) secondary?.session else viewModel.currentSession.value

    /** The second note in split view. Its session is owned here; the main note's by the ViewModel. */
    private inner class SecondaryPane(
        val path: String,
    ) {
        val container = FrameLayout(this@CanvasActivity)
        val canvas = OnyxCanvasView(this@CanvasActivity)
        val cursor = com.alexdremov.notate.ui.CursorView(this@CanvasActivity)
        var session: com.alexdremov.notate.data.CanvasSession? = null
        var saveJob: Job? = null
        val saveMutex = kotlinx.coroutines.sync.Mutex()

        init {
            // Must never open the pen driver before it is focused.
            canvas.setPaneActive(false)
            container.setBackgroundColor(android.graphics.Color.WHITE)
            container.addView(canvas, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            container.addView(cursor, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            canvas.setCursorView(cursor)
        }
    }

    private val splitPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val path = result.data?.getStringExtra("NOTE_PATH")
            if (result.resultCode == RESULT_OK && path != null) startSplit(path, pendingSplitOrientation)
        }

    private var pendingLinkCallback: ((name: String, uuid: String) -> Unit)? = null
    private var pendingFileCallback: ((name: String, path: String) -> Unit)? = null

    private val notePickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = pendingLinkCallback
            pendingLinkCallback = null
            if (result.resultCode == RESULT_OK) {
                val data = result.data
                val name = data?.getStringExtra("NOTE_NAME")
                val uuid = data?.getStringExtra("NOTE_UUID")

                if (name != null && uuid != null) {
                    callback?.invoke(name, uuid)
                }
            }
        }

    private val filePickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val callback = pendingFileCallback
            pendingFileCallback = null
            if (uri != null && callback != null) {
                lifecycleScope.launch {
                    val session = activeSession()
                    if (session != null) {
                        val importedPath = canvasRepository.importAsset(session, uri)
                        if (importedPath != null) {
                            val name =
                                com.alexdremov.notate.util.UriUtils
                                    .getFileName(this@CanvasActivity, uri) ?: "File"
                            callback.invoke(name, importedPath)
                        } else {
                            android.widget.Toast
                                .makeText(
                                    this@CanvasActivity,
                                    "Failed to import file",
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                        }
                    }
                }
            }
        }

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            exportCoordinator.onFilePickerResult(uri)
            if (uri != null) {
                sidebarCoordinator.close()
            }
        }

    private val pdfPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importPdf(uri)
        }

    private fun importPdf(uri: Uri) {
        lifecycleScope.launch {
            val pages = activeCanvas.getController().importPdf(uri)
            if (pages > 0) {
                android.widget.Toast
                    .makeText(this@CanvasActivity, "Imported $pages PDF page(s)", android.widget.Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    private val imagePickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                lifecycleScope.launch {
                    val (finalUriStr, w, h) =
                        withContext(Dispatchers.IO) {
                            val importedPath =
                                com.alexdremov.notate.util.ImageImportHelper
                                    .importImage(this@CanvasActivity, uri)

                            val uriToUse =
                                if (importedPath != null) {
                                    Uri.parse(importedPath)
                                } else {
                                    try {
                                        contentResolver.takePersistableUriPermission(
                                            uri,
                                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                        )
                                    } catch (e: Exception) {
                                        Logger.e("ImageImport", "Failed to take permission", e)
                                    }
                                    uri
                                }

                            var width = 400f
                            var height = 400f
                            try {
                                val options = android.graphics.BitmapFactory.Options()
                                options.inJustDecodeBounds = true

                                val scheme = uriToUse.scheme
                                if (scheme == "file") {
                                    android.graphics.BitmapFactory.decodeFile(uriToUse.path, options)
                                } else {
                                    contentResolver.openInputStream(uriToUse)?.use {
                                        android.graphics.BitmapFactory.decodeStream(it, null, options)
                                    }
                                }

                                if (options.outWidth > 0 && options.outHeight > 0) {
                                    width = options.outWidth.toFloat()
                                    height = options.outHeight.toFloat()
                                    val maxDim = 800f
                                    if (width > maxDim || height > maxDim) {
                                        val s = kotlin.math.min(maxDim / width, maxDim / height)
                                        width *= s
                                        height *= s
                                    }
                                }
                            } catch (e: Exception) {
                                Logger.e("ImageImport", "Failed to decode dimensions", e, showToUser = true)
                            }
                            Triple(uriToUse.toString(), width, height)
                        }

                    val matrix = android.graphics.Matrix()
                    activeCanvas.getViewportMatrix(matrix)
                    val screenCenterX = activeCanvas.width / 2f
                    val screenCenterY = activeCanvas.height / 2f

                    val values = FloatArray(9)
                    matrix.getValues(values)
                    val tx = values[android.graphics.Matrix.MTRANS_X]
                    val ty = values[android.graphics.Matrix.MTRANS_Y]
                    val scale = values[android.graphics.Matrix.MSCALE_X]

                    val worldX = (screenCenterX - tx) / scale
                    val worldY = (screenCenterY - ty) / scale

                    activeCanvas.getController().pasteImage(finalUriStr, worldX, worldY, w, h)
                }
            }
        }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // The intent survives recreation; don't import the same PDF twice.
        outState.putBoolean(STATE_PDF_IMPORT_CONSUMED, pendingPdfImport == null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Prevent sync while canvas is opening
        com.alexdremov.notate.data.SyncManager
            .cancelAllSyncs()
        com.alexdremov.notate.data.SyncManager.isCanvasOpen = true

        // Intercept Back Press - Save in background and close immediately
        onBackPressedDispatcher.addCallback(this) { closeAndFinish() }

        // Initialize State Holder
        isFixedPageState = mutableStateOf(false)
        canvasRepository =
            com.alexdremov.notate.data
                .CanvasRepository(this)
        projectRepository =
            com.alexdremov.notate.data
                .ProjectRepository(this)
        syncManager =
            com.alexdremov.notate.data
                .SyncManager(this, canvasRepository)

        EpdController.setViewDefaultUpdateMode(window.decorView, UpdateMode.DU)
        EpdController.setDisplayScheme(EpdController.SCHEME_SCRIBBLE)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        currentCanvasPath = intent.getStringExtra("CANVAS_PATH")
        if (intent.hasExtra(com.alexdremov.notate.ocr.index.OcrNavigation.EXTRA_LEFT)) {
            pendingSearchBounds =
                RectF(
                    intent.getFloatExtra(com.alexdremov.notate.ocr.index.OcrNavigation.EXTRA_LEFT, 0f),
                    intent.getFloatExtra(com.alexdremov.notate.ocr.index.OcrNavigation.EXTRA_TOP, 0f),
                    intent.getFloatExtra(com.alexdremov.notate.ocr.index.OcrNavigation.EXTRA_RIGHT, 0f),
                    intent.getFloatExtra(com.alexdremov.notate.ocr.index.OcrNavigation.EXTRA_BOTTOM, 0f),
                )
        }
        if (savedInstanceState?.getBoolean(STATE_PDF_IMPORT_CONSUMED) != true) {
            pendingPdfImport = intent.getStringExtra(EXTRA_IMPORT_PDF_URI)?.let { Uri.parse(it) }
        }

        enableImmersiveMode()

        // Initialize Coordinators
        exportCoordinator =
            CanvasExportCoordinator(
                this,
                lifecycleScope,
                { activeCanvas.getModel() },
                exportLauncher,
            )

        sidebarCoordinator = SidebarCoordinator(this, binding.settingsSidebarContainer, binding.sidebarScrim)
        sidebarCoordinator.onStateChanged = {
            updateDrawingEnabledState()
            updateExclusionRects()
        }

        toolbarCoordinator =
            ToolbarCoordinator(this, binding.toolbarContainer, binding.root) { _ ->
                updateExclusionRects()
            }
        toolbarCoordinator.topInset = resources.getDimensionPixelSize(R.dimen.tab_bar_height)
        toolbarCoordinator.onOrientationChanged = {
            isToolbarHorizontal.value = toolbarCoordinator.getOrientation() == LinearLayout.HORIZONTAL
        }
        toolbarCoordinator.onDragStateChanged = { isDragging ->
            viewModel.setToolbarDragging(isDragging)
        }

        val toolbarTouch = com.alexdremov.notate.ui.input.InkGate.Reason.TOOLBAR_TOUCH
        val finishToolbarInteraction = {
            if (inkGate.isBlockedBy(toolbarTouch)) {
                // Only lifts the toolbar's own block: a popup this tap opened keeps ink off.
                inkGate.set(toolbarTouch, false)
                com.onyx.android.sdk.api.device.EpdDeviceManager
                    .exitAnimationUpdate(true)
            }
        }

        binding.toolbarContainer.onDown = {
            if (!inkGate.isBlockedBy(toolbarTouch)) {
                inkGate.set(toolbarTouch, true)
                com.onyx.android.sdk.api.device.EpdDeviceManager
                    .enterAnimationUpdate(true)
            }
        }

        binding.toolbarContainer.onUp = { finishToolbarInteraction() }

        binding.toolbarContainer.onLongPress = {
            finishToolbarInteraction()
            if (!viewModel.isToolbarCollapsed.value) {
                viewModel.setEditMode(true)
            }
        }

        toolbarCoordinator.setup()

        toolbarCoordinator.onRequestCollapse = {
            val shouldCollapse =
                with(viewModel) {
                    !isToolbarCollapsed.value &&
                        !isToolbarDragging.value &&
                        !isEditMode.value &&
                        !isPenPopupOpen.value
                }
            if (shouldCollapse && !sidebarCoordinator.isOpen) {
                viewModel.setToolbarCollapsed(true)
            }
        }

        // Initialize Toolbar UI (Compose)
        binding.toolbarContainer.removeAllViews()
        val composeToolbar =
            androidx.compose.ui.platform.ComposeView(this).apply {
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                setViewCompositionStrategy(
                    androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
                )
                setContent {
                    val horizontal by remember { isToolbarHorizontal }
                    val toolbarCanvas = activeCanvasState.value ?: binding.canvasView
                    MainToolbar(
                        viewModel = viewModel,
                        isHorizontal = horizontal,
                        canvasController = toolbarCanvas.getController(),
                        canvasModel = toolbarCanvas.getModel(),
                        onToolClick = { item: ToolbarItem, rect: ComposeRect ->
                            val androidRect =
                                android.graphics.Rect(
                                    rect.left.toInt(),
                                    rect.top.toInt(),
                                    rect.right.toInt(),
                                    rect.bottom.toInt(),
                                )
                            handleToolClick(item.id, androidRect)
                        },
                        onActionClick = { action ->
                            when (action) {
                                com.alexdremov.notate.model.ActionType.UNDO -> {
                                    lifecycleScope.launch { activeCanvas.undo() }
                                }

                                com.alexdremov.notate.model.ActionType.REDO -> {
                                    lifecycleScope.launch { activeCanvas.redo() }
                                }

                                com.alexdremov.notate.model.ActionType.INSERT_IMAGE -> {
                                    // Image, PDF, link or paste: one menu, shared with long-press on the canvas.
                                    activeCanvas.showInsertMenu()
                                }

                                com.alexdremov.notate.model.ActionType.ZOOM_WINDOW -> {
                                    activeCanvas.toggleZoomWindow()
                                }

                                com.alexdremov.notate.model.ActionType.GUIDES -> {
                                    showGuidesPicker()
                                }
                            }
                        },
                        onOpenSidebar = {
                            sidebarCoordinator.open()
                            sidebarController.showMainMenu()
                        },
                        onToolbarExpandStart = { toolbarCoordinator.savePosition() },
                        onToolbarExpanded = {
                            toolbarCoordinator.ensureOnScreen()
                            activeCanvas.refreshScreen()
                        },
                        onToolbarCollapsed = { toolbarCoordinator.restorePosition() },
                    )
                }
            }
        binding.toolbarContainer.addView(composeToolbar)

        sidebarController =
            SettingsSidebarController(
                this,
                binding.settingsSidebarContainer,
                viewModel,
                getCurrentStyle = { activeCanvas.getBackgroundStyle() },
                isFixedPageMode = {
                    activeCanvas.getModel().canvasType == com.alexdremov.notate.data.CanvasType.FIXED_PAGES
                },
                onStyleUpdate = { newStyle ->
                    lifecycleScope.launch { activeCanvas.setBackgroundStyle(newStyle) }
                },
                onExportRequest = { action ->
                    when (action) {
                        is ExportAction.Export -> {
                            exportCoordinator.requestExport(action.isVector)
                        }

                        is ExportAction.Share -> {
                            exportCoordinator.requestShare(action.isVector)
                            sidebarCoordinator.close()
                        }
                    }
                },
                onEditToolbar = {
                    sidebarCoordinator.close()
                    viewModel.setEditMode(true)
                },
                onGeneratePatterns = { type, intensity ->
                    lifecycleScope.launch(Dispatchers.Default) {
                        // Calculate visible rect in Model coordinates
                        val matrix = android.graphics.Matrix()
                        activeCanvas.getViewportMatrix(matrix)

                        val screenWidth = activeCanvas.width.toFloat()
                        val screenHeight = activeCanvas.height.toFloat()

                        val invertMatrix = android.graphics.Matrix()
                        matrix.invert(invertMatrix)

                        val visibleRect = android.graphics.RectF(0f, 0f, screenWidth, screenHeight)
                        invertMatrix.mapRect(visibleRect)

                        val strokes =
                            com.alexdremov.notate.util.PatternGenerator.generateStrokes(
                                type,
                                intensity,
                                visibleRect,
                            )
                        activeCanvas.getController().addStrokes(strokes)
                    }
                },
                onPalmRejectionChanged = { enabled ->
                    allCanvases.forEach { it.setPalmRejectionEnabled(enabled) }
                    binding.splitLayout.fingerTapActivates = !enabled
                },
                onTwoFingerTapActionChange = { action ->
                    allCanvases.forEach { it.setTwoFingerTapAction(action) }
                },
                onStylusButtonActionChange = { action ->
                    allCanvases.forEach { it.setStylusButtonAction(action) }
                },
            )
        // Setup Progress Dialog
        val progressView = layoutInflater.inflate(R.layout.dialog_progress, null)
        val progressDialogBuilder =
            androidx.appcompat.app.AlertDialog
                .Builder(this)
                .setView(progressView)
                .setCancelable(false)
        progressDialog = progressDialogBuilder.create()

        val progressBar = progressView.findViewById<android.widget.ProgressBar>(R.id.progressBar)
        val progressMessage = progressView.findViewById<android.widget.TextView>(R.id.tv_progress_message)

        progressCallback = { isVisible, message, progress ->
            runOnUiThread {
                if (isVisible) {
                    if (!progressDialog!!.isShowing) {
                        progressDialog!!.show()
                    }
                    message?.let { progressMessage.text = it }
                    progressBar.progress = progress
                } else {
                    if (progressDialog!!.isShowing) {
                        progressDialog!!.dismiss()
                    }
                }
            }
        }

        binding.canvasView.setCursorView(binding.cursorView)
        binding.minimapView.setup(binding.canvasView)
        wireCanvas(binding.canvasView)
        setupTabsAndSplit()

        // ViewModel observation
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                launch {
                    Logger.userEvents.collect { event ->
                        binding.errorBanner.show(event)
                    }
                }
                launch {
                    viewModel.activeTool.collect { tool ->
                        allCanvases.forEach { canvas ->
                            canvas.setTool(tool)
                            if (tool.type == ToolType.ERASER) canvas.setEraser(tool)
                        }
                    }
                }
                launch {
                    viewModel.currentEraser.collect { eraser ->
                        eraser?.let { e -> allCanvases.forEach { it.setEraser(e) } }
                    }
                }
                launch {
                    viewModel.isDrawingEnabled.collect { enabled ->
                        allCanvases.forEach { it.setDrawingEnabled(enabled) }
                    }
                }
                launch {
                    viewModel.isEditMode.collect { isEdit ->
                        Logger.d("NotateDebug", "CanvasActivity:  isEditMode=$isEdit")
                        binding.toolbarContainer.isDragEnabled = !isEdit
                        inkGate.set(com.alexdremov.notate.ui.input.InkGate.Reason.TOOLBAR_EDIT_MODE, isEdit)
                    }
                }
                launch {
                    viewModel.isFixedPageCenterHorizontal.collect { enabled ->
                        allCanvases.forEach { it.setFixedPageCenterHorizontal(enabled) }
                    }
                }

                launch {
                    viewModel.isDistractionFree.collect { setDistractionFree(it) }
                }

                launch {
                    viewModel.saveEvents.collect { event ->
                        when (event) {
                            is DrawingViewModel.SaveEvent.Conflict -> {
                                val fileName = event.conflictPath.substringAfterLast('/')
                                Logger.w("CanvasActivity", "External change detected! Saved as copy: $fileName")
                                binding.errorBanner.show(
                                    Logger.UserEvent(
                                        message = "External modification detected. Saved as copy:\n$fileName",
                                        level = Logger.Level.WARNING,
                                    ),
                                    duration = 8000L,
                                )
                            }
                        }
                    }
                }

                // Session Observation
                launch {
                    viewModel.currentSession.collect { session ->
                        if (session != null) {
                            withContext(Dispatchers.Main) {
                                val tUiStart = System.currentTimeMillis()

                                binding.canvasView
                                    .getModel()
                                    .initializeSession(session.regionManager, session.metadata.nextStrokeOrder)
                                binding.canvasView.loadMetadata(session.metadata)
                                pendingSearchBounds?.let { bounds ->
                                    pendingSearchBounds = null
                                    binding.canvasView.post { binding.canvasView.focusSearchMatch(bounds) }
                                }

                                val isFixed = session.metadata.canvasType == com.alexdremov.notate.data.CanvasType.FIXED_PAGES
                                if (!secondaryActive) {
                                    isFixedPageState?.value = isFixed
                                    viewModel.setFixedPageMode(isFixed)
                                }

                                pendingPdfImport?.let { uri ->
                                    pendingPdfImport = null
                                    importPdf(uri)
                                }

                                // Toolbar init logic is now in ViewModel's loadCanvasSession

                                Logger.d("CanvasActivity", "UI Load:  ${System.currentTimeMillis() - tUiStart}ms")
                            }
                        }
                    }
                }
            }
        }

        loadCanvas()
        setupAutoSave()
    }

    /** Callbacks and settings shared by the main canvas and the split-view canvas. */
    private fun wireCanvas(view: OnyxCanvasView) {
        view.setActionPopupContainer(binding.selectionActionsContainer)
        view.onStrokeStarted = {
            activePenPopup?.dismiss()
            activePenPopup = null
            if (sidebarCoordinator.isOpen) {
                sidebarCoordinator.close()
            }
        }

        // If the driver already inked a stylus tap on the toolbar, repaint it clean.
        view.onExcludedStrokeDiscarded = {
            EpdController.invalidate(binding.toolbarContainer, UpdateMode.GC)
            if (sidebarCoordinator.isOpen) {
                EpdController.invalidate(binding.settingsSidebarContainer, UpdateMode.GC)
            }
        }

        view.onRequestInsertImage = {
            imagePickerLauncher.launch(arrayOf("image/*"))
        }

        view.onRequestInsertPdf = {
            pdfPickerLauncher.launch(arrayOf("application/pdf"))
        }

        view.onBrowseFiles = { callback ->
            pendingLinkCallback = callback

            lifecycleScope.launch {
                val currentPath = activePath()
                val projectId =
                    if (currentPath != null) {
                        withContext(Dispatchers.IO) {
                            syncManager.findProjectForFile(currentPath)
                        }
                    } else {
                        null
                    }

                val currentUuid =
                    activeSession()
                        ?.metadata
                        ?.uuid

                val intent =
                    Intent(this@CanvasActivity, NotePickerActivity::class.java).apply {
                        if (projectId != null) {
                            putExtra("LOCKED_PROJECT_ID", projectId)
                        }
                        if (currentUuid != null) {
                            putExtra("DISABLED_ITEM_UUID", currentUuid)
                        }
                    }
                notePickerLauncher.launch(intent)
            }
        }

        view.onSelectFile = { callback ->
            pendingFileCallback = callback
            filePickerLauncher.launch(arrayOf("*/*"))
        }

        view.onLinkActivated = { link ->
            handleLinkActivation(link)
        }

        progressCallback?.let { view.getController().setProgressCallback(it) }
        view.setPalmRejectionEnabled(
            com.alexdremov.notate.data.PreferencesManager.isPalmRejectionEnabled(this),
        )
        view.onThreeFingerTap = {
            viewModel.toggleDistractionFree()
        }
        view.setTwoFingerTapAction(
            com.alexdremov.notate.data.PreferencesManager.getTwoFingerTapAction(this),
        )
        view.setStylusButtonAction(
            com.alexdremov.notate.data.PreferencesManager.getStylusButtonAction(this),
        )
        view.setTool(viewModel.activeTool.value)
        viewModel.currentEraser.value?.let { view.setEraser(it) }
        view.setFixedPageCenterHorizontal(viewModel.isFixedPageCenterHorizontal.value)
    }

    private val externalProjectRepositories = mutableMapOf<String, com.alexdremov.notate.data.ProjectRepository>()

    /** Ruler / protractor picker behind the single toolbar button: icon toggles, no text. */
    private fun showGuidesPicker() {
        val canvas = activeCanvas
        val dp = resources.displayMetrics.density
        val row =
            android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER
                val pad = (16 * dp).toInt()
                setPadding(pad, pad, pad, pad)
            }
        val dialog =
            androidx.appcompat.app.AlertDialog
                .Builder(this)
                .setView(row)
                .create()

        fun toggle(
            iconRes: Int,
            description: String,
            isOn: () -> Boolean,
            set: (Boolean) -> Unit,
        ) = android.widget.ImageView(this).apply {
            setImageResource(iconRes)
            contentDescription = description
            setBackgroundResource(R.drawable.bg_tool_selected)
            val pad = (14 * dp).toInt()
            setPadding(pad, pad, pad, pad)
            isSelected = isOn()
            layoutParams =
                android.widget.LinearLayout.LayoutParams((72 * dp).toInt(), (72 * dp).toInt()).apply {
                    marginStart = (8 * dp).toInt()
                    marginEnd = (8 * dp).toInt()
                }
            setOnClickListener {
                set(!isOn())
                dialog.dismiss()
            }
        }

        row.addView(toggle(R.drawable.ic_guide_ruler, "Ruler", { canvas.isRulerVisible }, canvas::setRulerVisible))
        row.addView(toggle(R.drawable.ic_guide_protractor, "Protractor", { canvas.isProtractorVisible }, canvas::setProtractorVisible))
        dialog.show()
    }

    private fun handleLinkActivation(link: com.alexdremov.notate.model.LinkItem) {
        // Close existing window if any
        floatingWindow?.onClose?.invoke()

        floatingWindow =
            FloatingWindowView(this).apply {
                setTitle(link.label)
                onClose = {
                    // Save state
                    com.alexdremov.notate.data.PreferencesManager.saveFloatingWindowRect(
                        this@CanvasActivity,
                        this.x.toInt(),
                        this.y.toInt(),
                        this.width,
                        this.height,
                    )

                    (this.parent as? ViewGroup)?.removeView(this)
                    floatingWindow = null
                    closeFloatingSession()
                }
            }

        // Restore State or Default
        val savedRect =
            com.alexdremov.notate.data.PreferencesManager
                .getFloatingWindowRect(this)
        val screenW = binding.root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val screenH = binding.root.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels

        val params: FrameLayout.LayoutParams

        if (savedRect != null) {
            var x = savedRect[0]
            var y = savedRect[1]
            var w = savedRect[2]
            var h = savedRect[3]

            // Clamp Size
            w = w.coerceIn(300, screenW)
            h = h.coerceIn(300, screenH)

            // Clamp Position (Ensure header is reachable and window is partially visible)
            // x: allow dragging off-screen but keep 50px visible
            x = x.coerceIn(-w + 100, screenW - 100)
            // y: keep top within screen (0) to screenH - 100
            y = y.coerceIn(0, screenH - 100)

            params = FrameLayout.LayoutParams(w, h)
            params.gravity = Gravity.TOP or Gravity.START
            floatingWindow?.translationX = x.toFloat()
            floatingWindow?.translationY = y.toFloat()
        } else {
            // Default Center
            val defaultW = 1000.coerceAtMost(screenW - 50)
            val defaultH = 800.coerceAtMost(screenH - 50)

            params = FrameLayout.LayoutParams(defaultW, defaultH)
            params.gravity = Gravity.TOP or Gravity.START

            val initialX = (screenW - defaultW) / 2
            val initialY = (screenH - defaultH) / 2
            floatingWindow?.translationX = initialX.toFloat()
            floatingWindow?.translationY = initialY.toFloat()
        }

        floatingWindow?.layoutParams = params

        // Add to root view (CoordinatorLayout/FrameLayout)
        (binding.root as? ViewGroup)?.addView(floatingWindow)

        when (link.type) {
            LinkType.EXTERNAL_URL -> {
                val webView = WebView(this)
                webView.webViewClient =
                    object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean {
                            val url = request?.url?.toString() ?: return false
                            // Allow only http and https
                            return !url.startsWith("http://") && !url.startsWith("https://")
                        }

                        override fun onReceivedSslError(
                            view: WebView?,
                            handler: SslErrorHandler?,
                            error: android.net.http.SslError?,
                        ) {
                            // Default behavior: cancel loading on SSL errors for security
                            handler?.cancel()
                        }
                    }

                webView.settings.apply {
                    javaScriptEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                }

                webView.loadUrl(link.target)
                floatingWindow?.setContentView(webView)

                val previousOnClose = floatingWindow?.onClose
                floatingWindow?.onClose = {
                    webView.destroy()
                    previousOnClose?.invoke()
                }
            }

            LinkType.INTERNAL_NOTE -> {
                lifecycleScope.launch {
                    val targetUuid = link.target
                    Logger.d("LinkResolution", "Resolving UUID: $targetUuid")

                    val path =
                        withContext(Dispatchers.IO) {
                            // 1. Try default internal repository
                            var foundPath = projectRepository.getPathForUuid(targetUuid)
                            if (foundPath == null) {
                                Logger.d("LinkResolution", "Not found in default cache, refreshing index...")
                                projectRepository.refreshIndex()
                                foundPath = projectRepository.getPathForUuid(targetUuid)
                            }

                            if (foundPath == null) {
                                val projects =
                                    com.alexdremov.notate.data.PreferencesManager
                                        .getProjects(this@CanvasActivity)
                                Logger.d("LinkResolution", "Searching ${projects.size} external projects")

                                for (project in projects) {
                                    val repo =
                                        externalProjectRepositories.getOrPut(project.uri) {
                                            com.alexdremov.notate.data
                                                .ProjectRepository(this@CanvasActivity, project.uri)
                                        }
                                    foundPath = repo.getPathForUuid(targetUuid)

                                    if (foundPath == null) {
                                        Logger.d("LinkResolution", "Refreshing index for project: ${project.name}")
                                        repo.refreshIndex()
                                        foundPath = repo.getPathForUuid(targetUuid)
                                    }

                                    if (foundPath != null) {
                                        Logger.d("LinkResolution", "Found in project: ${project.name}")
                                        break
                                    }
                                }
                            }
                            foundPath
                        }

                    if (path != null) {
                        Logger.d("LinkResolution", "Resolved path: $path")
                        openFloatingCanvas(path)
                    } else {
                        Logger.e("LinkResolution", "Failed to resolve note with UUID: $targetUuid")
                        android.widget.Toast
                            .makeText(this@CanvasActivity, "Note not found", android.widget.Toast.LENGTH_SHORT)
                            .show()
                        floatingWindow?.onClose?.invoke()
                    }
                }
            }

            LinkType.LOCAL_FILE -> {
                lifecycleScope.launch {
                    val session = viewModel.currentSession.value ?: return@launch
                    val assetPath = link.target
                    val file = canvasRepository.getAssetFile(session, assetPath)

                    if (file.exists()) {
                        if (assetPath.lowercase().endsWith(".pdf")) {
                            openPdfViewer(file)
                        } else {
                            android.widget.Toast
                                .makeText(
                                    this@CanvasActivity,
                                    "File type not supported for inline viewing",
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            floatingWindow?.onClose?.invoke()
                        }
                    } else {
                        android.widget.Toast
                            .makeText(
                                this@CanvasActivity,
                                "Linked file not found",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        floatingWindow?.onClose?.invoke()
                    }
                }
            }
        }
    }

    private fun openPdfViewer(file: java.io.File) {
        val pdfView =
            com.alexdremov.notate.ui.view
                .SimplePdfView(this)
        pdfView.setPdfFile(file)
        floatingWindow?.setContentView(pdfView)
    }

    private suspend fun openFloatingCanvas(path: String) {
        val session =
            withContext(Dispatchers.IO) {
                canvasRepository.openCanvasSession(path)
            }

        if (session != null) {
            floatingSession = session
            val canvasView = OnyxCanvasView(this)

            // Initialize
            canvasView.getModel().initializeSession(session.regionManager, session.metadata.nextStrokeOrder)
            canvasView.loadMetadata(session.metadata)
            canvasView.setReadOnly(true)

            floatingWindow?.setContentView(canvasView)
        } else {
            android.widget.Toast
                .makeText(this@CanvasActivity, "Failed to load note", android.widget.Toast.LENGTH_SHORT)
                .show()
            floatingWindow?.onClose?.invoke()
        }
    }

    private fun closeFloatingSession() {
        floatingSession?.let { session ->
            lifecycleScope.launch(Dispatchers.IO) {
                canvasRepository.releaseCanvasSession(session)
            }
            floatingSession = null
        }
    }


    // --- Tabs and split view ---

    private fun setupTabsAndSplit() {
        val path = currentCanvasPath
        if (path != null) {
            workspace = WorkspaceStore.update(this) { it.open(DocTab(DocKind.NOTE, path, WorkspaceStore.titleFor(path))) }
        }
        binding.splitLayout.setPanes(binding.primaryPane, null)
        binding.splitLayout.fingerTapActivates =
            !com.alexdremov.notate.data.PreferencesManager.isPalmRejectionEnabled(this)
        binding.splitLayout.onPaneTouched = { index -> setActivePane(useSecondary = index != primarySlot()) }
        binding.splitLayout.onDividerDragStateChanged = { dragging ->
            inkGate.set(com.alexdremov.notate.ui.input.InkGate.Reason.SPLIT_DIVIDER, dragging)
            if (dragging) {
                com.onyx.android.sdk.api.device.EpdDeviceManager
                    .enterAnimationUpdate(true)
            } else {
                com.onyx.android.sdk.api.device.EpdDeviceManager
                    .exitAnimationUpdate(true)
                updateExclusionRects()
            }
        }
        binding.splitLayout.onDragReleased = { outcome ->
            when (outcome) {
                is com.alexdremov.notate.ui.split.SplitDrag.Outcome.Resize -> {
                    currentCanvasPath?.let { p -> workspace = WorkspaceStore.update(this) { it.updateSplit(p) { s -> s.copy(ratio = outcome.ratio) } } }
                }

                is com.alexdremov.notate.ui.split.SplitDrag.Outcome.Collapse -> {
                    if (outcome.keepIndex == primarySlot()) exitSplit() else secondary?.path?.let { promoteToSingle(it) }
                }
            }
        }

        binding.tabBar.onHome = { closeAndFinish() }
        binding.tabBar.onTabClick = { tab -> onTabSelected(tab) }
        binding.tabBar.onTabClose = { tab -> closeTab(tab) }
        binding.tabBar.onSplit = { orientation -> onSplitButton(orientation) }
        binding.tabBar.onSwapPanes = {
            currentCanvasPath?.let { p ->
                workspace = WorkspaceStore.update(this) { it.updateSplit(p) { s -> s.copy(first = s.second, second = s.first, ratio = 1f - s.ratio) } }
                layoutPanes()
            }
        }
        binding.tabBar.onExitSplit = { exitSplit() }

        val split = path?.let { workspace.splitFor(it) }
        val partner = split?.partnerOf(path)
        if (partner != null) openSecondary(partner)
        layoutPanes()
        renderTabs()
    }

    private fun currentSplit(): SplitState? = currentCanvasPath?.let { workspace.splitFor(it) }

    /** Slot (0 = left/top, 1 = right/bottom) of the main note. */
    private fun primarySlot(): Int {
        val split = currentSplit() ?: return 0
        return if (split.first == currentCanvasPath) 0 else 1
    }

    private fun layoutPanes() {
        val split = currentSplit()
        val pane = secondary
        val layout = binding.splitLayout
        if (split == null || pane == null) {
            layout.setPanes(binding.primaryPane, null)
        } else {
            if (primarySlot() == 0) layout.setPanes(binding.primaryPane, pane.container) else layout.setPanes(pane.container, binding.primaryPane)
            layout.orientation = split.orientation
            layout.ratio = split.ratio
            layout.activeIndex = if (secondaryActive) 1 - primarySlot() else primarySlot()
        }
        // The minimap belongs to the main pane and gets in the way of a half-width pane.
        binding.minimapView.visibility = if (layout.isSplit || viewModel.isDistractionFree.value) View.GONE else View.VISIBLE
        layout.post { updateExclusionRects() }
        renderTabs()
    }

    private fun renderTabs() {
        val shown = setOfNotNull(currentCanvasPath, secondary?.path)
        binding.tabBar.render(workspace.tabs, activePath(), shown, if (secondary != null) currentSplit() else null)
    }

    /** Moves the pen (raw drawing), toolbar and menus to the other pane. */
    private fun setActivePane(useSecondary: Boolean) {
        val pane = secondary
        val target = if (useSecondary && pane != null) pane.canvas else binding.canvasView
        if (target === activeCanvas && target.isPaneActive) return
        val old = activeCanvas
        old.setPaneActive(false)
        secondaryActive = useSecondary && pane != null
        target.setPaneActive(true)
        target.doOnLayout { if (it === activeCanvas) target.setDrawingEnabled(inkGate.isInkAllowed) }
        activeCanvasState.value = target

        val isFixed = target.getModel().canvasType == com.alexdremov.notate.data.CanvasType.FIXED_PAGES
        isFixedPageState?.value = isFixed
        viewModel.setFixedPageMode(isFixed)

        binding.splitLayout.activeIndex = if (secondaryActive) 1 - primarySlot() else primarySlot()
        activePath()?.let { p -> workspace = WorkspaceStore.update(this) { it.activate(p) } }
        updateExclusionRects()
        renderTabs()
    }

    private fun onTabSelected(tab: DocTab) {
        when (tab.path) {
            activePath() -> return
            currentCanvasPath -> setActivePane(useSecondary = false)
            secondary?.path -> setActivePane(useSecondary = true)
            else -> switchToTab(tab)
        }
    }

    private fun closeTab(tab: DocTab) {
        when (tab.path) {
            secondary?.path -> {
                exitSplit()
                workspace = WorkspaceStore.update(this) { it.close(tab.path) }
                renderTabs()
            }

            currentCanvasPath -> {
                workspace = WorkspaceStore.update(this) { it.close(tab.path) }
                val next = secondary?.path?.let { workspace.tab(it) } ?: workspace.activePath?.let { workspace.tab(it) }
                if (next != null) switchToTab(next) else closeAndFinish()
            }

            else -> {
                workspace = WorkspaceStore.update(this) { it.close(tab.path) }
                renderTabs()
            }
        }
    }

    private fun onSplitButton(orientation: SplitOrientation) {
        val split = currentSplit()
        if (split != null && secondary != null) {
            if (split.orientation != orientation) {
                currentCanvasPath?.let { p -> workspace = WorkspaceStore.update(this) { it.updateSplit(p) { s -> s.copy(orientation = orientation) } } }
                layoutPanes()
            }
            return
        }
        pendingSplitOrientation = orientation
        val current = currentCanvasPath ?: return
        val candidates = workspace.tabs.filter { it.kind == DocKind.NOTE && it.path != current }
        val labels = candidates.map { it.title } + "Browse notes\u2026"
        androidx.appcompat.app.AlertDialog
            .Builder(this)
            .setTitle(if (orientation == SplitOrientation.SIDE_BY_SIDE) "Open beside" else "Open below")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < candidates.size) {
                    startSplit(candidates[which].path, orientation)
                } else {
                    val intent =
                        Intent(this, NotePickerActivity::class.java).apply {
                            activeSession()?.metadata?.uuid?.let { putExtra("DISABLED_ITEM_UUID", it) }
                        }
                    splitPickerLauncher.launch(intent)
                }
            }.show()
    }

    private fun startSplit(
        other: String,
        orientation: SplitOrientation,
    ) {
        val current = currentCanvasPath ?: return
        if (other == current) {
            android.widget.Toast.makeText(this, "This note is already open", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        if (secondary != null) closeSecondary()
        workspace =
            WorkspaceStore.update(this) {
                it
                    .open(DocTab(DocKind.NOTE, other, WorkspaceStore.titleFor(other)))
                    .split(current, other, orientation)
            }
        openSecondary(other)
        layoutPanes()
        // Like Notability: the note just opened beside gets the pen.
        setActivePane(useSecondary = true)
    }

    /** Leaves split view, keeping the main note full screen. */
    private fun exitSplit() {
        val path = currentCanvasPath ?: return
        setActivePane(useSecondary = false)
        closeSecondary()
        workspace = WorkspaceStore.update(this) { it.unsplit(path) }
        layoutPanes()
    }

    /** Leaves split view keeping the other note: reopens the editor on it. */
    private fun promoteToSingle(path: String) {
        workspace = WorkspaceStore.update(this) { it.unsplit(path) }
        switchToTab(workspace.tab(path) ?: DocTab(DocKind.NOTE, path, WorkspaceStore.titleFor(path)))
    }

    /**
     * Opens a tab that is not on screen: saves this editor, then opens the note (restoring its
     * split) or the book in the reader.
     */
    private fun switchToTab(tab: DocTab) {
        if (isSwitchingTabs) return
        isSwitchingTabs = true
        workspace = WorkspaceStore.update(this) { it.activate(tab.path) }
        closeAndFinish { startActivity(com.alexdremov.notate.ui.tabs.TabNavigator.intentFor(this, tab)) }
    }

    /** Saves and closes everything, then finishes. [beforeFinish] runs once the data is captured. */
    private fun closeAndFinish(beforeFinish: (() -> Unit)? = null) {
        val path = currentCanvasPath
        lifecycleScope.launch {
            closeSecondary()
            if (path != null) {
                val finalMetadata =
                    try {
                        binding.canvasView.getCanvasData().copy(
                            toolbarItems = viewModel.toolbarItems.value,
                        )
                    } catch (e: Exception) {
                        Logger.e("CanvasActivity", "Failed to capture final metadata", e)
                        null
                    }

                // Hand off to Process Scope for saving
                ProcessLifecycleOwner.get().lifecycleScope.launch {
                    viewModel.closeSession(path, finalMetadata)
                }
            }
            beforeFinish?.invoke()
            finish()
            if (beforeFinish != null) overridePendingTransition(0, 0)
        }
    }

    private fun openSecondary(path: String) {
        val pane = SecondaryPane(path)
        secondary = pane
        wireCanvas(pane.canvas)
        pane.canvas.onContentChanged = {
            pane.saveJob?.cancel()
            pane.saveJob =
                lifecycleScope.launch {
                    delay(500)
                    if (isActive) saveSecondary(pane, commit = false)
                }
        }
        lifecycleScope.launch {
            val session =
                try {
                    withContext(Dispatchers.IO) { canvasRepository.openCanvasSession(path) }
                } catch (e: Exception) {
                    Logger.e("CanvasActivity", "Failed to open split note", e, showToUser = true)
                    null
                }
            if (secondary !== pane) {
                session?.let { withContext(Dispatchers.IO) { canvasRepository.releaseCanvasSession(it) } }
                return@launch
            }
            if (session == null) {
                exitSplit()
                return@launch
            }
            pane.session = session
            pane.canvas.getModel().initializeSession(session.regionManager, session.metadata.nextStrokeOrder)
            pane.canvas.loadMetadata(session.metadata)
            if (secondaryActive) {
                val isFixed = session.metadata.canvasType == com.alexdremov.notate.data.CanvasType.FIXED_PAGES
                isFixedPageState?.value = isFixed
                viewModel.setFixedPageMode(isFixed)
            }
        }
    }

    private suspend fun saveSecondary(
        pane: SecondaryPane,
        commit: Boolean,
    ) {
        val session = pane.session ?: return
        val data =
            withContext(Dispatchers.Main) {
                try {
                    pane.canvas.getCanvasData().copy(toolbarItems = viewModel.toolbarItems.value)
                } catch (e: Exception) {
                    Logger.e("CanvasActivity", "Failed to capture split note", e)
                    null
                }
            } ?: return
        withContext(Dispatchers.IO) {
            pane.saveMutex.withLock {
                if (session.isClosed()) return@withLock
                try {
                    session.updateMetadata(data)
                    val result = canvasRepository.saveCanvasSession(pane.path, session, commitToZip = commit)
                    session.updateOrigin(result.newLastModified, result.newSize)
                    if (result.isConflict) {
                        Logger.w("CanvasActivity", "Split note changed outside the app. Saved as copy: ${result.savedPath}")
                    }
                } catch (e: Exception) {
                    Logger.e("CanvasActivity", "Failed to save split note", e, showToUser = true)
                }
            }
        }
    }

    /** Saves the split-view note and releases it. Safe to call from onDestroy. */
    private fun closeSecondary() {
        val pane = secondary ?: return
        if (secondaryActive) setActivePane(useSecondary = false)
        secondary = null
        pane.saveJob?.cancel()
        pane.canvas.setPaneActive(false)
        val session = pane.session
        ProcessLifecycleOwner.get().lifecycleScope.launch {
            if (session != null) {
                saveSecondary(pane, commit = false)
                withContext(Dispatchers.IO) {
                    pane.saveMutex.withLock {
                        try {
                            canvasRepository.saveAndCloseSession(pane.path, session)
                        } catch (e: Exception) {
                            Logger.e("CanvasActivity", "Failed to close split note", e)
                            canvasRepository.releaseCanvasSession(session)
                        }
                    }
                }
            }
            withContext(Dispatchers.Main) { (pane.container.parent as? ViewGroup)?.removeView(pane.container) }
        }
    }

    private fun setupAutoSave() {
        binding.canvasView.onContentChanged = {
            scheduleAutoSave()
        }
    }

    private fun scheduleAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob =
            lifecycleScope.launch {
                delay(500)
                if (isActive) {
                    saveCanvas(commit = false)
                }
            }
    }

    private fun loadCanvas() {
        val path = currentCanvasPath ?: return
        lifecycleScope.launch {
            viewModel.loadCanvasSession(path)
        }
    }

    override fun onResume() {
        super.onResume()
        // Ensure no sync runs while canvas is active
        com.alexdremov.notate.data.SyncManager
            .cancelAllSyncs()
        com.alexdremov.notate.data.SyncManager.isCanvasOpen = true
    }

    override fun onPause() {
        super.onPause()
        // Allow sync to proceed in background
        com.alexdremov.notate.data.SyncManager.isCanvasOpen = false

        val path = currentCanvasPath
        if (path != null) {
            // Sequential Save -> Sync to prevent race condition
            ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.IO) {
                try {
                    // 1. Full Save (Blocking in this coroutine)
                    saveCanvasSuspend(commit = true)
                    secondary?.let { saveSecondary(it, commit = true) }

                    // 2. Trigger Sync (only after save completes)
                    val projectId = syncManager.findProjectForFile(path)
                    if (projectId != null) {
                        Logger.d("CanvasActivity", "Triggering background sync for project $projectId")
                        syncManager.syncProject(projectId)
                    }
                } catch (e: Exception) {
                    Logger.e("CanvasActivity", "Background save/sync failed", e)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        autoSaveJob?.cancel()
        closeFloatingSession()
        closeSecondary()
        // Session cleanup is handled by ViewModel or explicit close in onBackPressed
    }

    /**
     * Triggers an async save.  Does not wait for completion.
     */
    private fun saveCanvas(commit: Boolean = false) {
        ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.Default) {
            try {
                saveCanvasSuspend(commit)
            } catch (e: Exception) {
                Logger.e("CanvasActivity", "Auto-save failed", e)
            }
        }
    }

    /**
     * Performs the actual save.  Can be awaited for synchronous save (e.g., on back press).
     */
    private suspend fun saveCanvasSuspend(commit: Boolean = true) {
        val path = currentCanvasPath ?: return

        try {
            // Capture metadata from UI thread
            val updatedMetadata =
                withContext(Dispatchers.Main) {
                    try {
                        binding.canvasView.getCanvasData().copy(
                            toolbarItems = viewModel.toolbarItems.value,
                        )
                    } catch (e: Exception) {
                        Logger.e("CanvasActivity", "Failed to get canvas data", e)
                        null
                    }
                }

            if (updatedMetadata == null) {
                Logger.w("CanvasActivity", "Skipping save: failed to capture metadata")
                return
            }

            viewModel.saveCanvasSession(path, updatedMetadata, commit)
        } catch (e: Exception) {
            Logger.e("CanvasActivity", "saveCanvasSuspend exception", e)
        }
    }

    private fun enableImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun updateDrawingEnabledState() {
        inkGate.set(com.alexdremov.notate.ui.input.InkGate.Reason.SIDEBAR, sidebarCoordinator.isOpen)
        inkGate.set(com.alexdremov.notate.ui.input.InkGate.Reason.PAGE_GRID, isGridOpen)
        inkGate.set(com.alexdremov.notate.ui.input.InkGate.Reason.TOOL_POPUP, activePenPopup != null)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Focusable popups, menus and dialogs (pen settings, toolbar menus, new tool
        // panels...) take window focus. The Onyx digitizer ignores windows, so without
        // this the stylus would ink on the canvas underneath them.
        inkGate.set(com.alexdremov.notate.ui.input.InkGate.Reason.WINDOW_UNFOCUSED, !hasFocus)
    }

    private fun setDistractionFree(enabled: Boolean) {
        val vis = if (enabled) View.GONE else View.VISIBLE
        binding.toolbarContainer.visibility = vis
        binding.minimapView.visibility = if (enabled || binding.splitLayout.isSplit) View.GONE else View.VISIBLE
        binding.tabBar.visibility = vis
        (binding.splitLayout.layoutParams as ViewGroup.MarginLayoutParams).let { lp ->
            lp.topMargin = if (enabled) 0 else resources.getDimensionPixelSize(R.dimen.tab_bar_height)
            binding.splitLayout.layoutParams = lp
        }
        if (enabled) {
            sidebarCoordinator.close()
        }
        // Recompute stylus exclusion so the hidden toolbar stops reserving area.
        updateExclusionRects()
    }

    private fun updateExclusionRects() {
        val rects = mutableListOf<android.graphics.Rect>()
        rects.addAll(toolbarCoordinator.getRects())

        if (sidebarCoordinator.isOpen) {
            val sidebarRect = android.graphics.Rect()
            binding.settingsSidebarContainer.getGlobalVisibleRect(sidebarRect)
            rects.add(sidebarRect)
        }

        activeCanvas.setExclusionRects(rects)
    }

    private fun handleToolClick(
        toolId: String,
        targetRect: android.graphics.Rect,
    ) {
        Logger.d("NotateDebug", "handleToolClick ID=$toolId")

        val item = viewModel.toolbarItems.value.find { it.id == toolId }
        val isSelectionSafeTool =
            when (item) {
                is ToolbarItem.Pen -> item.penTool.type == ToolType.TEXT
                is ToolbarItem.Select -> true
                else -> false
            }

        // If clicking same tool (opening settings) OR switching to TEXT/SELECT, preserve selection.
        if (viewModel.activeToolId.value != toolId && !isSelectionSafeTool) {
            lifecycleScope.launch { activeCanvas.getController().clearSelection() }
        }
        activeCanvas.dismissActionPopup()

        // The shape pen is a single toolbar button whose shapes live in its popup, so a tap always opens it.
        val opensOnFirstTap = item is ToolbarItem.Pen && item.penTool.isShapeTool
        if (viewModel.activeToolId.value != toolId) {
            viewModel.selectTool(toolId)
            if (!opensOnFirstTap) return
        }

        run {
            val tool =
                when (item) {
                    is ToolbarItem.Pen -> item.penTool
                    is ToolbarItem.Eraser -> viewModel.activeTool.value
                    is ToolbarItem.Select -> viewModel.activeTool.value
                    else -> null
                } ?: return

            val popup =
                com.alexdremov.notate.ui.dialog.PenSettingsPopup(
                    this,
                    tool,
                    onUpdate = { updatedTool ->
                        viewModel.updateTool(updatedTool)
                        if (updatedTool.type == ToolType.TEXT) {
                            lifecycleScope.launch {
                                activeCanvas.getController().updateSelectedTextStyle(
                                    fontSize = updatedTool.width,
                                    color = updatedTool.color,
                                )
                            }
                        }
                    },
                    onRemove = { toolToRemove -> viewModel.removePen(toolToRemove.id) },
                    onDismiss = {
                        com.alexdremov.notate.util.EpdFastModeController
                            .exitFastMode()
                        activePenPopup = null
                        viewModel.setPenPopupOpen(false)
                        updateDrawingEnabledState()
                    },
                )

            activePenPopup = popup
            viewModel.setPenPopupOpen(true)
            updateDrawingEnabledState()

            com.alexdremov.notate.util.EpdFastModeController
                .enterFastMode()
            activePenPopup?.show(binding.root, targetRect)
        }
    }
}
