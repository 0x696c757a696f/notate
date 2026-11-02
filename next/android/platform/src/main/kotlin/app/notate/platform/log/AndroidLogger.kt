package app.notate.platform.log

import android.util.Log
import app.notate.core.model.log.Logger
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Logcat + in-memory ring buffer (last [RING_SIZE] lines) + one `yyyy-MM-dd.txt` per day in [logDir].
 * File writes happen on one background thread. Any thread.
 */
class AndroidLogger(
    private val logDir: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val tagPrefix: String = "Notate.",
) : Logger {
    private val ring = ArrayDeque<String>(RING_SIZE)
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "notate-log").also { it.isDaemon = true } }
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    init { logDir.mkdirs() }

    override fun d(tag: String, msg: String, t: Throwable?) = log(Log.DEBUG, 'D', tag, msg, t)
    override fun i(tag: String, msg: String, t: Throwable?) = log(Log.INFO, 'I', tag, msg, t)
    override fun w(tag: String, msg: String, t: Throwable?) = log(Log.WARN, 'W', tag, msg, t)
    override fun e(tag: String, msg: String, t: Throwable?) = log(Log.ERROR, 'E', tag, msg, t)

    private fun log(priority: Int, level: Char, tag: String, msg: String, t: Throwable?) {
        Log.println(priority, tagPrefix + tag, if (t == null) msg else msg + "\n" + Log.getStackTraceString(t))
        val time = now()
        val line = synchronized(this) {
            val l = "${timeFormat.format(Date(time))} $level/$tag: $msg" + (if (t != null) "\n" + Log.getStackTraceString(t).trimEnd() else "")
            synchronized(ring) {
                if (ring.size >= RING_SIZE) ring.removeFirst()
                ring.addLast(l)
            }
            l
        }
        val file = fileFor(time)
        runCatching { writer.execute { runCatching { file.appendText(line + "\n") } } }
    }

    private fun fileFor(time: Long): File = File(logDir, synchronized(this) { dayFormat.format(Date(time)) } + ".txt")

    /** The last [n] lines held in memory, oldest first. */
    fun recent(n: Int = RING_SIZE): List<String> = synchronized(ring) { ring.toList().takeLast(n) }

    /** Waits until every line logged so far is in its file. For export and tests. */
    fun flush() {
        runCatching { writer.submit { }.get() }
    }

    /** Merges the last [days] days of log files into [out] (default `logDir/export.txt`) and returns it. */
    fun exportLogs(days: Int = 3, out: File = File(logDir, "export.txt")): File {
        flush()
        val files = logDir.listFiles { f -> f.isFile && DAY_FILE.matches(f.name) }.orEmpty().sortedBy { it.name }.takeLast(days)
        out.bufferedWriter().use { w ->
            for (f in files) {
                w.write("===== ${f.name} =====\n")
                w.write(f.readText())
            }
        }
        return out
    }

    private companion object {
        const val RING_SIZE = 2000
        val DAY_FILE = Regex("""\d{4}-\d{2}-\d{2}\.txt""")
    }
}
