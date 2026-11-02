package app.notate.platform.log

import android.os.Build
import app.notate.platform.device.DeviceInfo
import app.notate.platform.fs.AppPaths
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Writes `crash/<time>.txt` (stack trace, device, last 200 log lines) on an uncaught exception, then defers to the old handler. */
object CrashHandler {
    private const val LOG_LINES = 200

    /** Installs the handler; call once from `Application.onCreate`. */
    fun install(paths: AppPaths, logger: AndroidLogger, device: DeviceInfo) {
        install(paths, logger, device, Thread.getDefaultUncaughtExceptionHandler())
    }

    internal fun install(paths: AppPaths, logger: AndroidLogger, device: DeviceInfo, previous: Thread.UncaughtExceptionHandler?) {
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(paths, logger, device, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun write(paths: AppPaths, logger: AndroidLogger, device: DeviceInfo, thread: Thread, error: Throwable) {
        paths.crashDir.mkdirs()
        val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss-SSS", Locale.US).format(Date())
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        File(paths.crashDir, "$stamp.txt").writeText(
            buildString {
                appendLine("Thread: ${thread.name}")
                appendLine("Device: $device")
                appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine()
                appendLine(trace)
                appendLine("--- last $LOG_LINES log lines ---")
                logger.recent(LOG_LINES).forEach { appendLine(it) }
            },
        )
    }

    /** Moves [reports] to `crash/sent/` so they are not offered again (whether or not the user shared them). */
    fun markSent(paths: AppPaths, reports: List<File>) {
        val sent = File(paths.crashDir, "sent").also { it.mkdirs() }
        for (f in reports) if (!f.renameTo(File(sent, f.name))) f.delete()
    }

    /** Merges [reports] into one text file [out] (oldest first) for sharing. */
    fun bundle(reports: List<File>, out: File): File {
        out.bufferedWriter().use { w ->
            for (f in reports) {
                w.write("===== ${f.name} =====\n")
                w.write(f.readText())
                w.write("\n")
            }
        }
        return out
    }

    /** Crash reports not yet cleared by the user, oldest first. */
    fun pendingCrashReports(paths: AppPaths): List<File> =
        paths.crashDir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }.orEmpty().sortedBy { it.name }
}
