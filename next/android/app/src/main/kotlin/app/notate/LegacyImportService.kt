package app.notate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.notate.export.legacy.LegacyImportSession
import app.notate.export.legacy.LegacyImportState
import kotlinx.coroutines.launch

/**
 * Runs the old-note import in the foreground so it survives leaving the screen ("Importing notes… 12 / 40", cancellable).
 * The work itself lives in the app-wide scope; the service only keeps the process alive and shows the notification.
 */
class LegacyImportService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            LegacyImportSession.cancel()
            return START_NOT_STICKY
        }
        val graph = NotateApp.graph
        val notes = LegacyImportSession.pending
        notify(0, notes.size, "", first = true)
        if (LegacyImportSession.job?.isActive == true) return START_NOT_STICKY
        LegacyImportSession.stopRequested = false
        LegacyImportSession.job = graph.appScope.launch {
            try {
                val report = graph.legacyImporter.importAll(
                    notes,
                    onProgress = { done, total, name ->
                        LegacyImportSession.running(done, total, name)
                        notify(done, total, name, first = false)
                    },
                    stopRequested = { LegacyImportSession.stopRequested },
                )
                LegacyImportSession.finished(report)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                graph.logger.e("LegacyImport", "Import failed", e)
                LegacyImportSession.finished(
                    app.notate.export.legacy.ImportReport(notes.size, 0, 0, listOf(app.notate.export.legacy.ImportFailure("Import", e.message ?: "Unexpected error")), 0, false),
                )
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun notify(done: Int, total: Int, name: String, first: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.legacy_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, LegacyImportActivity.intent(this), PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, LegacyImportService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.legacy_notification, done, total))
            .setContentText(name.ifEmpty { null })
            .setProgress(total, done, total == 0)
            .setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                getString(R.string.legacy_cancel_import), cancel,
            ).build())
            .build()
        if (first) {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(NOTIFICATION_ID, n)
        } else {
            nm.notify(NOTIFICATION_ID, n)
        }
    }

    companion object {
        private const val CHANNEL = "legacy_import"
        private const val NOTIFICATION_ID = 6201
        private const val ACTION_CANCEL = "app.notate.action.CANCEL_LEGACY_IMPORT"

        fun startIntent(context: Context): Intent = Intent(context, LegacyImportService::class.java)
    }
}
