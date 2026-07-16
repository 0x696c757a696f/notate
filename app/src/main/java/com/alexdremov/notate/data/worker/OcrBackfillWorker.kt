package com.alexdremov.notate.data.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.alexdremov.notate.data.CanvasRepository
import com.alexdremov.notate.data.PreferencesManager
import com.alexdremov.notate.data.ProjectRepository
import com.alexdremov.notate.ocr.OcrModelInfo
import com.alexdremov.notate.ocr.index.OcrIndexDatabase
import com.alexdremov.notate.ocr.index.OcrIndexWriter
import com.alexdremov.notate.util.Logger
import java.util.concurrent.TimeUnit

class OcrBackfillWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        if (!PreferencesManager.isBackgroundOcrIndexingEnabled(applicationContext)) return Result.success()
        val dao = OcrIndexDatabase.get(applicationContext).dao()
        val canvasRepository = CanvasRepository(applicationContext)
        val modelVersion = OcrModelInfo().indexVersion
        val allFiles = PreferencesManager.getProjects(applicationContext).flatMap { project ->
            runCatching {
                val repository = ProjectRepository(applicationContext, project.uri)
                repository.refreshIndex()
                repository.getAllIndexedFiles()
            }.getOrElse { error ->
                Logger.e("OcrBackfill", "Unable to scan ${project.name}", error)
                emptyList()
            }
        }.distinctBy { it.path }

        val currentPaths = allFiles.mapTo(mutableSetOf()) { it.path }
        dao.getAllDocuments().filter { it.path !in currentPaths }.forEach { document ->
            dao.deleteDocumentBlocks(document.documentId)
            dao.deleteDocument(document.documentId)
        }

        val pending = allFiles.filter { item ->
            val indexed = dao.getDocumentByPath(item.path)
            indexed == null || indexed.lastModified != item.lastModified || indexed.modelVersion != modelVersion || indexed.status != "INDEXED"
        }
        pending.take(BATCH_SIZE).forEach { item ->
            val session = canvasRepository.openCanvasSession(item.path)
            if (session == null) {
                Logger.w("OcrBackfill", "Unable to open ${item.path}")
                return@forEach
            }
            try {
                session.waitForInitialization()
                OcrIndexWriter(applicationContext).indexSession(session.sessionDir, item.path, item.lastModified)
            } catch (error: Throwable) {
                Logger.e("OcrBackfill", "Unable to index ${item.path}", error)
            } finally {
                canvasRepository.releaseCanvasSession(session)
            }
        }
        return if (pending.size > BATCH_SIZE) Result.retry() else Result.success()
    }

    companion object {
        private const val BATCH_SIZE = 3
        const val UNIQUE_NAME = "OcrBackfillWorker"
    }
}

object OcrBackfillScheduler {
    fun schedule(
        context: Context,
        replace: Boolean = false,
    ) {
        val request = OneTimeWorkRequestBuilder<OcrBackfillWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiresCharging(true)
                    .setRequiresDeviceIdle(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            OcrBackfillWorker.UNIQUE_NAME,
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
