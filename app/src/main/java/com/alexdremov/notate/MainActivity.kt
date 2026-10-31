package com.alexdremov.notate

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.alexdremov.notate.CanvasActivity
import androidx.lifecycle.lifecycleScope
import com.alexdremov.notate.data.CanvasItem
import com.alexdremov.notate.data.DailyNotesManager
import com.alexdremov.notate.data.PreferencesManager
import com.alexdremov.notate.data.ProjectItem
import com.alexdremov.notate.data.RemoteStorageType
import com.alexdremov.notate.data.SortOption
import com.alexdremov.notate.ui.home.*
import com.alexdremov.notate.ui.home.components.DeleteConfirmationDialog
import com.alexdremov.notate.ui.home.components.EmptyState
import com.alexdremov.notate.ui.home.components.EmptyStateAction
import com.alexdremov.notate.ui.theme.Ink
import com.alexdremov.notate.ui.theme.Spacing
import com.alexdremov.notate.ui.theme.NotateTheme
import com.alexdremov.notate.util.Logger
import com.alexdremov.notate.vm.HomeViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: HomeViewModel by viewModels()

    companion object {
        private var didAutoOpenDaily = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Use fast animation mode for the menu UI
        com.alexdremov.notate.ui.epd.Epd.scheduler.acquireAnimation("home")

        // Handle incoming intent (e.g. from File Manager)
        handleIntent(intent)

        if (savedInstanceState == null &&
            !didAutoOpenDaily &&
            PreferencesManager.isOpenDailyOnStartEnabled(this)
        ) {
            didAutoOpenDaily = true
            lifecycleScope.launch {
                val path = DailyNotesManager.openOrCreateTodayNote(this@MainActivity)
                if (path != null) {
                    startActivity(
                        Intent(this@MainActivity, CanvasActivity::class.java).apply {
                            putExtra("CANVAS_PATH", path)
                        },
                    )
                }
            }
        }

        setContent {
            NotateTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    MainScreen(viewModel)
                }
            }
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            val uri = intent.data ?: return
            val path = if (uri.scheme == "file") uri.path else uri.toString()
            if (path != null) {
                val canvasIntent =
                    Intent(this, CanvasActivity::class.java).apply {
                        putExtra("CANVAS_PATH", path)
                    }
                startActivity(canvasIntent)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        com.alexdremov.notate.ui.epd.Epd.scheduler.acquireAnimation("home")
        viewModel.refresh()
    }

    override fun onPause() {
        super.onPause()
        com.alexdremov.notate.ui.epd.Epd.scheduler.releaseAnimation("home")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: HomeViewModel,
    isPickerMode: Boolean = false,
    lockedProjectId: String? = null,
    disabledItemUuid: String? = null,
    onFilePicked: ((CanvasItem) -> Unit)? = null,
) {
    val currentProject by viewModel.currentProject.collectAsState()
    val projects by viewModel.projects.collectAsState()
    val browserItems by viewModel.browserItems.collectAsState()
    val breadcrumbs by viewModel.breadcrumbs.collectAsState()
    val title by viewModel.title.collectAsState()
    val syncProgress by viewModel.syncProgress.collectAsState()
    val syncingProjectIds by viewModel.syncingProjectIds.collectAsState()
    val tags by viewModel.tags.collectAsState()
    val selectedTag by viewModel.selectedTag.collectAsState()
    val sortOption by viewModel.sortOption.collectAsState()
    val savingPaths by viewModel.savingPaths.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val recents by viewModel.recents.collectAsState()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = androidx.activity.compose.LocalActivity.current as? ComponentActivity

    // Listen for global errors
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            Logger.userEvents.collect { event ->
                snackbarHostState.showSnackbar(
                    message = event.message,
                    withDismissAction = true,
                    duration = SnackbarDuration.Short,
                )
            }
        }
    }

    // Dialog State
    var showNameDialog by remember { mutableStateOf<DialogType?>(null) }
    var showRemoteStorages by remember { mutableStateOf(false) }
    var showEditStorage by remember { mutableStateOf(false) }
    var editingStorage by remember { mutableStateOf<com.alexdremov.notate.data.RemoteStorageConfig?>(null) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showWebDocs by remember { mutableStateOf(false) }
    var showNewMenu by remember { mutableStateOf(false) }
    var isSearchExpanded by remember { mutableStateOf(false) }

    // Project Actions State
    var projectToDelete by remember { mutableStateOf<com.alexdremov.notate.data.ProjectConfig?>(null) }
    var projectToManage by remember { mutableStateOf<com.alexdremov.notate.data.ProjectConfig?>(null) }
    var projectToRename by remember { mutableStateOf<com.alexdremov.notate.data.ProjectConfig?>(null) }
    var projectToSync by remember { mutableStateOf<com.alexdremov.notate.data.ProjectConfig?>(null) }

    // Temporary state for Project Creation flow
    var pendingProjectName by remember { mutableStateOf<String?>(null) }

    // Folder Picker for "Add Project"
    val projectLocationPicker =
        rememberLauncherForActivityResult(
            contract =
                object : ActivityResultContracts.OpenDocumentTree() {
                    override fun createIntent(
                        context: android.content.Context,
                        input: android.net.Uri?,
                    ): Intent {
                        val intent = super.createIntent(context, input)
                        intent.putExtra(Intent.EXTRA_LOCAL_ONLY, false)
                        intent.addFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
                        )
                        return intent
                    }
                },
        ) { uri ->
            if (uri != null && pendingProjectName != null) {
                val flags =
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                try {
                    context.contentResolver.takePersistableUriPermission(uri, flags)
                } catch (e: Exception) {
                    // Ignore if already granted or failed
                }
                viewModel.addProject(pendingProjectName!!, uri.toString())
                pendingProjectName = null
            }
        }

    // "Import PDF": create a note sized to the PDF, then let CanvasActivity import the pages.
    val pdfImportPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                viewModel.createCanvasFromPdf(uri) { path ->
                    val intent =
                        Intent(context, CanvasActivity::class.java).apply {
                            putExtra("CANVAS_PATH", path)
                            putExtra(CanvasActivity.EXTRA_IMPORT_PDF_URI, uri.toString())
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    context.startActivity(intent)
                }
            }
        }

    // Back Handler
    val isAtRoot = breadcrumbs.size <= 1
    val shouldHandleBack =
        if (isSearchExpanded || searchQuery.isNotBlank()) {
            true
        } else if (lockedProjectId != null) {
            !isAtRoot
        } else {
            currentProject != null
        }

    BackHandler(enabled = shouldHandleBack) {
        if (isSearchExpanded || searchQuery.isNotBlank()) {
            viewModel.setSearchQuery("")
            isSearchExpanded = false
        } else {
            viewModel.navigateUp()
        }
    }

    Row(modifier = Modifier.fillMaxSize()) {
        // --- Sidebar (Left) ---
        // Hide sidebar if project is locked to enforce single-project mode
        if (lockedProjectId == null) {
            Sidebar(
                projects = projects,
                tags = tags,
                selectedProject = currentProject,
                selectedTag = selectedTag,
                onProjectSelected = { viewModel.openProject(it) },
                onProjectLongClick = { projectToManage = it },
                onTagSelected = { viewModel.selectTag(it) },
                onAddProject = { showNameDialog = DialogType.ADD_PROJECT },
                onManageTags = { showNameDialog = DialogType.MANAGE_TAGS },
                onSettingsClick = { showSettingsDialog = true },
                onReadingClick = { showWebDocs = true },
                onTodayClick = {
                    scope.launch {
                        val path = DailyNotesManager.openOrCreateTodayNote(context)
                        if (path != null) {
                            context.startActivity(
                                Intent(context, CanvasActivity::class.java).apply {
                                    putExtra("CANVAS_PATH", path)
                                },
                            )
                        } else {
                            snackbarHostState.showSnackbar("Add a project first to use Daily Notes")
                        }
                    }
                },
            )
        }

        // --- Content Area (Right) ---
        Scaffold(
            modifier = Modifier.weight(1f),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        if (isSearchExpanded && !isPickerMode) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = viewModel::setSearchQuery,
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                placeholder = { Text("Search all notes") },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                trailingIcon = {
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                                        }
                                    }
                                },
                            )
                        } else {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.headlineMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    navigationIcon = {
                        if (isPickerMode) {
                            IconButton(onClick = { activity?.finish() }) {
                                Icon(Icons.Default.Close, contentDescription = "Cancel")
                            }
                        }
                    },
                    actions = {
                        if (!isPickerMode) {
                            IconButton(
                                onClick = {
                                    isSearchExpanded = !isSearchExpanded
                                    if (!isSearchExpanded) viewModel.setSearchQuery("")
                                },
                            ) {
                                Icon(
                                    if (isSearchExpanded) Icons.Default.Close else Icons.Default.Search,
                                    contentDescription = if (isSearchExpanded) "Close search" else "Search notes",
                                )
                            }
                        }
                        if (currentProject != null || selectedTag != null) {
                            // Sort Action
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(Icons.Filled.SortByAlpha, contentDescription = "Sort")
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false },
                            ) {
                                SortOption.values().forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.displayName) },
                                        onClick = {
                                            viewModel.setSortOption(option)
                                            showSortMenu = false
                                        },
                                        leadingIcon =
                                            if (option == sortOption) {
                                                { Icon(Icons.Filled.Check, contentDescription = null) }
                                            } else {
                                                null
                                            },
                                    )
                                }
                            }
                        }

                        // One labelled "New" entry instead of a row of unlabelled icons. Everything
                        // that creates or brings in content lives here; Settings stays in the sidebar.
                        if (!isPickerMode && selectedTag == null) {
                            Box {
                                TextButton(onClick = { showNewMenu = true }) {
                                    Icon(Icons.Filled.Add, contentDescription = null, tint = Ink)
                                    Spacer(Modifier.width(Spacing.xs))
                                    Text("New", color = Ink, style = MaterialTheme.typography.labelLarge)
                                }
                                DropdownMenu(
                                    expanded = showNewMenu,
                                    onDismissRequest = { showNewMenu = false },
                                ) {
                                    if (currentProject != null) {
                                        DropdownMenuItem(
                                            text = { Text("New canvas") },
                                            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                            onClick = {
                                                showNewMenu = false
                                                showNameDialog = DialogType.CREATE_CANVAS
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("New folder") },
                                            leadingIcon = { Icon(Icons.Filled.CreateNewFolder, contentDescription = null) },
                                            onClick = {
                                                showNewMenu = false
                                                showNameDialog = DialogType.CREATE_FOLDER
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Import PDF") },
                                            leadingIcon = { Icon(Icons.Filled.PictureAsPdf, contentDescription = null) },
                                            onClick = {
                                                showNewMenu = false
                                                pdfImportPicker.launch(arrayOf("application/pdf"))
                                            },
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text("Import EPUB / HTML") },
                                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null) },
                                        onClick = {
                                            showNewMenu = false
                                            showWebDocs = true
                                        },
                                    )
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("New project") },
                                        leadingIcon = { Icon(Icons.Filled.CreateNewFolder, contentDescription = null) },
                                        onClick = {
                                            showNewMenu = false
                                            showNameDialog = DialogType.ADD_PROJECT
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
            },
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding)) {
                if (searchQuery.isNotBlank()) {
                    OcrSearchResultsScreen(
                        query = searchQuery,
                        results = searchResults,
                        projects = projects,
                        onResultClick = { result ->
                            val intent = Intent(context, CanvasActivity::class.java).apply {
                                putExtra("CANVAS_PATH", result.path)
                                result.bounds?.let { bounds ->
                                    putExtra(com.alexdremov.notate.ocr.index.OcrNavigation.EXTRA_LEFT, bounds.left)
                                    putExtra(com.alexdremov.notate.ocr.index.OcrNavigation.EXTRA_TOP, bounds.top)
                                    putExtra(com.alexdremov.notate.ocr.index.OcrNavigation.EXTRA_RIGHT, bounds.right)
                                    putExtra(com.alexdremov.notate.ocr.index.OcrNavigation.EXTRA_BOTTOM, bounds.bottom)
                                }
                            }
                            context.startActivity(intent)
                        },
                    )
                } else if (currentProject == null && selectedTag == null) {
                    // --- Empty State ---
                    when {
                        lockedProjectId != null -> {
                            EmptyState(title = "Opening project")
                        }

                        projects.isEmpty() -> {
                            EmptyState(
                                title = "No projects yet",
                                message = "A project holds your notebooks, like a shelf. Create one to start writing.",
                                icon = Icons.Outlined.FolderOpen,
                                actions =
                                    if (isPickerMode) {
                                        emptyList()
                                    } else {
                                        listOf(EmptyStateAction("New project") { showNameDialog = DialogType.ADD_PROJECT })
                                    },
                            )
                        }

                        else -> {
                            EmptyState(
                                title = "Pick a project",
                                message = "Choose a project from the sidebar to see its notes.",
                                icon = Icons.Outlined.FolderOpen,
                            )
                        }
                    }
                } else {
                    // --- Level 1+: File Browser ---
                    FileBrowserScreen(
                        items = browserItems,
                        breadcrumbs = breadcrumbs,
                        allTags = tags,
                        disabledItemUuid = disabledItemUuid,
                        isReadOnly = isPickerMode,
                        onBreadcrumbClick = { viewModel.loadBrowserItems(it.path) },
                        onItemClick = { item ->
                            when (item) {
                                is ProjectItem -> {
                                    viewModel.loadBrowserItems(item.path)
                                }

                                is CanvasItem -> {
                                    if (isPickerMode && onFilePicked != null) {
                                        onFilePicked(item)
                                    } else {
                                        PreferencesManager.addRecent(context, item.uuid ?: item.path)
                                        val intent =
                                            Intent(context, CanvasActivity::class.java).apply {
                                                putExtra("CANVAS_PATH", item.path)
                                            }
                                        context.startActivity(intent)
                                    }
                                }
                            }
                        },
                        onItemDelete = { if (!isPickerMode) viewModel.deleteItem(it) },
                        onItemRename = { item, newName -> if (!isPickerMode) viewModel.renameItem(item, newName) },
                        onItemDuplicate = { item -> if (!isPickerMode) viewModel.duplicateItem(item) },
                        onSetFileTags = { item, newTags -> if (!isPickerMode) viewModel.setFileTags(item, newTags) },
                        favorites = favorites,
                        recents = recents,
                        onCreateCanvas = { showNameDialog = DialogType.CREATE_CANVAS },
                        onCreateFolder = { showNameDialog = DialogType.CREATE_FOLDER },
                        onToggleFavorite = { if (!isPickerMode) viewModel.toggleFavorite(it) },
                    )
                }

                // Sync Progress Overlay
                // Hide sync progress if in Picker Mode (Read Only / Minimal Distraction)
                if (!isPickerMode) {
                    syncProgress?.let { (progress, message) ->
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.1f)),
                            contentAlignment = Alignment.BottomCenter,
                        ) {
                            Card(
                                modifier =
                                    Modifier
                                        .padding(16.dp)
                                        .fillMaxWidth()
                                        .border(2.dp, Color.Black, RoundedCornerShape(12.dp)),
                                colors = CardDefaults.cardColors(containerColor = Color.White),
                            ) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(message, style = MaterialTheme.typography.bodyMedium)
                                    Spacer(Modifier.height(8.dp))
                                    LinearProgressIndicator(
                                        progress = progress / 100f,
                                        modifier = Modifier.fillMaxWidth(),
                                        color = Color.Black,
                                        trackColor = Color.LightGray,
                                    )
                                }
                            }
                        }
                    }
                }

                // Background Saving Indicator
                if (savingPaths.isNotEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        contentAlignment = Alignment.BottomStart,
                    ) {
                        Card(
                            modifier = Modifier.border(1.dp, Color.Black, RoundedCornerShape(8.dp)),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // Static icon: an animated spinner keeps the e-ink panel refreshing.
                                Icon(
                                    imageVector = Icons.Outlined.Save,
                                    contentDescription = null,
                                    modifier = Modifier.size(Spacing.lg),
                                    tint = Ink,
                                )
                                Spacer(Modifier.width(Spacing.sm))
                                Text(
                                    text = if (savingPaths.size == 1) "Saving canvas" else "Saving ${savingPaths.size} canvases",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Handle Project Actions Dialogs
    val manageTarget = projectToManage
    if (manageTarget != null) {
        AlertDialog(
            modifier = Modifier.border(2.dp, Color.Black, RoundedCornerShape(28.dp)),
            onDismissRequest = { projectToManage = null },
            title = { Text("Actions for \"${manageTarget.name}\"") },
            confirmButton = {},
            dismissButton = {
                OutlinedButton(onClick = { projectToManage = null }) {
                    Text("Close")
                }
            },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            projectToSync = manageTarget
                            projectToManage = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Synchronization", color = Color.Black)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = {
                            projectToRename = manageTarget
                            projectToManage = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Rename", color = Color.Black)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = {
                            projectToDelete = manageTarget
                            projectToManage = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Remove project", color = Color.Red)
                    }
                }
            },
        )
    }

    val syncTarget = projectToSync
    if (syncTarget != null) {
        ProjectSyncConfigDialog(
            projectId = syncTarget.id,
            onDismiss = { projectToSync = null },
            onSyncNow = {
                viewModel.syncProject(syncTarget.id)
                projectToSync = null
            },
        )
    }

    val renameTarget = projectToRename
    if (renameTarget != null) {
        TextInputDialog(
            title = "Rename project",
            initialValue = renameTarget.name,
            confirmText = "Rename",
            onDismiss = { projectToRename = null },
            onConfirm = { newName ->
                viewModel.renameProject(renameTarget, newName)
                projectToRename = null
            },
        )
    }

    val deleteTarget = projectToDelete
    if (deleteTarget != null) {
        DeleteConfirmationDialog(
            itemName = deleteTarget.name,
            onDismiss = { projectToDelete = null },
            onConfirm = {
                viewModel.removeProject(deleteTarget)
                projectToDelete = null
            },
        )
    }

    // Handle Dialogs
    if (showWebDocs) {
        WebDocLibraryDialog(onDismiss = { showWebDocs = false })
    }

    if (showSettingsDialog) {
        SettingsDialog(
            onDismiss = { showSettingsDialog = false },
            onOpenSync = {
                showSettingsDialog = false
                showRemoteStorages = true
            },
        )
    }

    var refreshStoragesTrigger by remember { mutableStateOf(0) }

    if (showRemoteStorages) {
        RemoteStorageListDialog(
            onDismiss = { showRemoteStorages = false },
            onManageStorage = { storage ->
                editingStorage = storage
                showEditStorage = true
            },
            refreshTrigger = refreshStoragesTrigger,
        )
    }

    if (showEditStorage) {
        EditRemoteStorageDialog(
            storage = editingStorage,
            onDismiss = { showEditStorage = false },
            onConfirm = { config, password ->
                val current =
                    com.alexdremov.notate.data.SyncPreferencesManager
                        .getRemoteStorages(context)
                        .toMutableList()
                // Remove existing if editing
                current.removeAll { it.id == config.id }
                current.add(config)
                com.alexdremov.notate.data.SyncPreferencesManager
                    .saveRemoteStorages(context, current)

                if (config.type == RemoteStorageType.WEBDAV) {
                    com.alexdremov.notate.data.SyncPreferencesManager
                        .savePassword(context, config.id, password)
                } else {
                    com.alexdremov.notate.data.SyncPreferencesManager
                        .savePassword(context, config.id, "")
                }
                showEditStorage = false
                refreshStoragesTrigger++
            },
        )
    }

    when (showNameDialog) {
        DialogType.MANAGE_TAGS -> {
            ManageTagsDialog(
                tags = tags,
                onAddTag = { name, color -> viewModel.addTag(name, color) },
                onUpdateTag = { tag -> viewModel.updateTag(tag) },
                onRemoveTag = { id -> viewModel.removeTag(id) },
                onDismiss = { showNameDialog = null },
            )
        }

        DialogType.CREATE_CANVAS -> {
            CreateCanvasDialog(
                onDismiss = { showNameDialog = null },
                onConfirm = { name, type, w, h ->
                    viewModel.createCanvas(name, type, w, h) { path ->
                        val intent =
                            Intent(context, CanvasActivity::class.java).apply {
                                putExtra("CANVAS_PATH", path)
                            }
                        context.startActivity(intent)
                    }
                    showNameDialog = null
                },
            )
        }

        DialogType.ADD_PROJECT -> {
            NewProjectDialog(
                onDismiss = { showNameDialog = null },
                onConfirm = { name, location ->
                    when (location) {
                        ProjectLocation.ON_DEVICE -> {
                            viewModel.addLocalProject(name)
                        }

                        ProjectLocation.CUSTOM_FOLDER -> {
                            pendingProjectName = name
                            projectLocationPicker.launch(null)
                        }
                    }
                    showNameDialog = null
                },
            )
        }

        DialogType.CREATE_FOLDER -> {
            TextInputDialog(
                title = "New folder",
                onDismiss = { showNameDialog = null },
                onConfirm = { name ->
                    viewModel.createFolder(name)
                    showNameDialog = null
                },
            )
        }

        null -> {}
    }
}
