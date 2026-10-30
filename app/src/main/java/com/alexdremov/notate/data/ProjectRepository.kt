package com.alexdremov.notate.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.alexdremov.notate.model.Tag
import java.io.File
import java.io.OutputStreamWriter

sealed interface FileSystemItem {
    val name: String
    val fileName: String
    val path: String
    val lastModified: Long
    val size: Long
    val syncStatus: SyncStatus
}

enum class SyncStatus {
    NONE,
    PLANNED,
    SYNCING,
}

data class ProjectItem(
    override val name: String,
    override val fileName: String,
    override val path: String,
    override val lastModified: Long,
    override val size: Long,
    val itemsCount: Int,
    override val syncStatus: SyncStatus = SyncStatus.NONE,
) : FileSystemItem

data class CanvasItem(
    override val name: String,
    override val fileName: String,
    override val path: String,
    override val lastModified: Long,
    override val size: Long,
    val thumbnail: String? = null,
    val tagIds: List<String> = emptyList(),
    val embeddedTags: List<Tag> = emptyList(),
    override val syncStatus: SyncStatus = SyncStatus.NONE,
    val uuid: String? = null, // Added UUID for linking
) : FileSystemItem

class ProjectRepository(
    private val context: Context,
    private val rootUriString: String? = null,
) {
    private val localStorage by lazy {
        // A project created "on this device" stores its own absolute folder path as its root;
        // anything else (no root, legacy configs) falls back to the shared app projects folder.
        val dir =
            if (rootUriString != null && rootUriString.startsWith("/")) {
                File(rootUriString)
            } else {
                defaultLocalRoot(context)
            }
        dir.apply { if (!exists()) mkdirs() }
        LocalStorageProvider(context, dir)
    }

    private val safStorage by lazy {
        if (rootUriString != null && rootUriString.startsWith("content://")) {
            SafStorageProvider(context, rootUriString)
        } else {
            null
        }
    }

    private val indexManager by lazy {
        val indexFile =
            if (rootUriString != null) {
                val hash = rootUriString.hashCode().toString()
                File(context.cacheDir, "index_$hash.json")
            } else {
                File(localStorage.getRootPath(), ".notate_index")
            }
        FileIndexManager(indexFile)
    }

    private fun getProvider(path: String?): StorageProvider {
        // If we have a specific SAF root, and the path is either null (root) or content://, use SAF
        if (safStorage != null && (path == null || path.startsWith("content://"))) {
            return safStorage!!
        }
        return localStorage
    }

    fun getRootPath(): String = rootUriString ?: localStorage.getRootPath()

    companion object {
        fun defaultLocalRoot(context: Context): File = File(context.filesDir, "projects")

        /**
         * Creates a fresh folder for a new on-device project under the app's projects folder.
         * The folder name is derived from [name]; a numeric suffix keeps it unique.
         */
        fun createLocalProjectFolder(
            context: Context,
            name: String,
        ): File {
            val root = defaultLocalRoot(context).apply { if (!exists()) mkdirs() }
            val base = name.trim().replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim('.', ' ').ifEmpty { "Project" }.take(64)
            var dir = File(root, base)
            var i = 2
            while (dir.exists()) {
                dir = File(root, "$base ($i)")
                i++
            }
            dir.mkdirs()
            return dir
        }
    }

    fun getItems(path: String?): List<FileSystemItem> = getProvider(path).getItems(path)

    suspend fun createProject(
        name: String,
        parentPath: String?,
    ): Boolean {
        val success = getProvider(parentPath).createFolder(name, parentPath)
        if (success) indexManager.updateIndex(getProvider(parentPath))
        return success
    }

    suspend fun createCanvas(
        name: String,
        parentPath: String?,
        type: CanvasType,
        pageWidth: Float,
        pageHeight: Float,
    ): String? {
        val emptyCanvas =
            com.alexdremov.notate.data.CanvasData(
                canvasType = type,
                pageWidth = pageWidth,
                pageHeight = pageHeight,
                uuid =
                    java.util.UUID
                        .randomUUID()
                        .toString(),
            )
        val result = getProvider(parentPath).createCanvas(name, parentPath, emptyCanvas)
        if (result != null) indexManager.updateIndex(getProvider(parentPath))
        return result
    }

    suspend fun deleteItem(path: String): Boolean {
        val success = getProvider(path).deleteItem(path)
        if (success) {
            indexManager.updateIndex(getProvider(path))
            val dao = com.alexdremov.notate.ocr.index.OcrIndexDatabase.get(context).dao()
            dao.getDocumentByPath(path)?.let { document ->
                dao.deleteDocumentBlocks(document.documentId)
                dao.deleteDocument(document.documentId)
            }
        }
        return success
    }

    suspend fun renameItem(
        path: String,
        newName: String,
    ): Boolean {
        val success = getProvider(path).renameItem(path, newName)
        if (success) {
            indexManager.updateIndex(getProvider(path))
            val dao = com.alexdremov.notate.ocr.index.OcrIndexDatabase.get(context).dao()
            dao.getDocumentByPath(path)?.let { document ->
                val moved = getAllIndexedFiles().firstOrNull { it.uuid == document.documentId }
                if (moved != null) {
                    dao.upsertDocument(document.copy(path = moved.path, name = moved.name, lastModified = moved.lastModified))
                }
            }
        }
        return success
    }

    suspend fun duplicateItem(
        path: String,
        parentPath: String?,
    ): Boolean {
        val success = getProvider(path).duplicateItem(path, parentPath)
        if (success) indexManager.updateIndex(getProvider(path))
        return success
    }

    suspend fun setTags(
        path: String,
        tagIds: List<String>,
        tagDefinitions: List<Tag> = emptyList(),
    ): Boolean {
        val success = getProvider(path).setTags(path, tagIds, tagDefinitions)
        if (success) {
            val currentMeta = indexManager.getFileMetadata(path)
            if (currentMeta != null) {
                val newMeta =
                    currentMeta.copy(
                        tagIds = tagIds,
                        embeddedTags = tagDefinitions,
                    )
                indexManager.updateFileEntry(path, newMeta)
            } else {
                indexManager.updateIndex(getProvider(path))
            }
        }
        return success
    }

    suspend fun findFilesWithTag(tagId: String): List<CanvasItem> {
        val indexed = indexManager.getIndexedFiles(tagId)
        val provider = getProvider(null)

        return indexed
            .mapNotNull { (path, meta) ->
                val metadata = provider.getFileMetadata(path)
                CanvasItem(
                    name = meta.name,
                    fileName = "${meta.name}.notate",
                    path = path,
                    lastModified = meta.lastModified,
                    size = 0,
                    thumbnail = metadata?.thumbnail,
                    tagIds = meta.tagIds,
                    embeddedTags = meta.embeddedTags,
                    uuid = meta.uuid ?: metadata?.uuid, // Fallback to live metadata if index is stale
                )
            }.sortedByDescending { it.lastModified }
    }

    /**
     * Returns all files known to the index.
     * Note: This relies on the index being up-to-date.
     */
    suspend fun getAllIndexedFiles(): List<CanvasItem> {
        val indexed = indexManager.getAllIndexedFiles()
        val provider = getProvider(null)

        return indexed
            .mapNotNull { (path, meta) ->
                val metadata = provider.getFileMetadata(path)
                CanvasItem(
                    name = meta.name,
                    fileName = "${meta.name}.notate", // Best effort guess from index
                    path = path,
                    lastModified = meta.lastModified,
                    size = 0, // Metadata doesn't store size currently, would need I/O to get it. Avoiding for search speed.
                    thumbnail = metadata?.thumbnail,
                    tagIds = meta.tagIds,
                    embeddedTags = meta.embeddedTags,
                    uuid = meta.uuid ?: metadata?.uuid,
                )
            }.sortedByDescending { it.lastModified }
    }

    // Throttle full index refreshes to avoid excessive I/O when called repeatedly.
    private var lastIndexRefreshTime: Long = 0L
    private val minIndexRefreshIntervalMs: Long = 1_000L

    suspend fun refreshIndex() {
        val now = System.currentTimeMillis()
        if (now - lastIndexRefreshTime < minIndexRefreshIntervalMs) {
            return
        }
        lastIndexRefreshTime = now
        indexManager.updateIndex(getProvider(null))
    }

    suspend fun getAllIndexedTags(): List<Tag> = indexManager.getAllTags()

    suspend fun getPathForUuid(uuid: String): String? = indexManager.getPathForUuid(uuid)

    suspend fun ensureUuid(path: String): String? {
        val uuid = getProvider(path).ensureUuid(path)
        if (uuid != null) {
            val currentMeta = indexManager.getFileMetadata(path)
            if (currentMeta != null) {
                val newMeta = currentMeta.copy(uuid = uuid)
                indexManager.updateFileEntry(path, newMeta)
            } else {
                indexManager.updateIndex(getProvider(path))
            }
        }
        return uuid
    }
}
