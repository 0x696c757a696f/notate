package app.notate.platform.log

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import app.notate.platform.fs.AppPaths
import java.io.File

/** Hands logs and crash reports to other apps (mail, file manager) as a `text/plain` attachment through the app's FileProvider. */
object ShareLogs {
    /** FileProvider authority registered in the app manifest. */
    fun authority(context: Context): String = context.packageName + ".files"

    /** Merges the last days of logs into `exports/` and returns a chooser for them. */
    fun logsIntent(context: Context, paths: AppPaths, logger: AndroidLogger, subject: String): Intent =
        sendIntent(context, logger.exportLogs(out = File(paths.exportsDir, "notate-logs.txt")), subject)

    /** Merges [reports] (and the recent log lines) into `exports/` and returns a chooser for them. */
    fun crashIntent(context: Context, paths: AppPaths, logger: AndroidLogger, reports: List<File>, subject: String): Intent =
        sendIntent(context, CrashHandler.bundle(reports, File(paths.exportsDir, "notate-crash.txt")), subject)

    private fun sendIntent(context: Context, file: File, subject: String): Intent {
        val uri = FileProvider.getUriForFile(context, authority(context), file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, subject).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
