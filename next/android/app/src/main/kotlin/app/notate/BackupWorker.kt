package app.notate

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.notate.core.doc.settings.BackupFreq
import java.util.concurrent.TimeUnit

/** Periodic automatic backup (Settings → Backup): runs while the battery and storage are not low. */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val result = NotateApp.graph.backup.runNow() ?: return Result.success()   // no folder: nothing to do
        return if (result.failed > 0) Result.retry() else Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "backup"

        /** Schedules or cancels the job for the current settings. */
        fun schedule(context: Context, freq: BackupFreq, hasFolder: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (freq == BackupFreq.OFF || !hasFolder) {
                wm.cancelUniqueWork(UNIQUE_NAME)
                return
            }
            val days = if (freq == BackupFreq.DAILY) 1L else 7L
            val request = PeriodicWorkRequestBuilder<BackupWorker>(days, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build())
                .build()
            wm.enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
