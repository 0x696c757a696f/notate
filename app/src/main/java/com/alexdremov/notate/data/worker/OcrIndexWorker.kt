package com.alexdremov.notate.data.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.alexdremov.notate.data.PreferencesManager
import com.alexdremov.notate.ocr.index.OcrIndexWriter
import com.alexdremov.notate.util.Logger
import java.io.File

class OcrIndexWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        if (!PreferencesManager.isBackgroundOcrIndexingEnabled(applicationContext)) return Result.success()
        val sessionPath = inputData.getString(KEY_SESSION_PATH) ?: return Result.failure()
        val targetPath = inputData.getString(KEY_TARGET_PATH) ?: return Result.failure()
        val sessionDir = File(sessionPath)
        if (!sessionDir.isDirectory) return Result.retry()
        return try {
            val lastModified = com.alexdremov.notate.data.StorageUtils.getOriginInfo(applicationContext, targetPath).first
            val outcome = OcrIndexWriter(applicationContext).indexSession(sessionDir, targetPath, lastModified)
            Logger.i("OcrIndexWorker", "Indexed $targetPath: $outcome")
            Result.success()
        } catch (error: Throwable) {
            Logger.e("OcrIndexWorker", "Indexing failed for $targetPath", error)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val KEY_SESSION_PATH = "ocr_session_path"
        const val KEY_TARGET_PATH = "ocr_target_path"
        const val TAG = "OcrIndexWorker"
    }
}
