package app.notate.platform.log

import android.os.Process
import java.util.concurrent.atomic.AtomicReference

/**
 * Diagnostic: the last few warnings/errors this process wrote to logcat, plus anything tagged like the
 * Onyx pen stack. Shown in the debug HUD so SDK-side refusals are visible without adb.
 * Polls on a daemon thread every [PERIOD_MS]; [lines] is cheap and any-thread.
 */
object SdkLogTail {
    private const val PERIOD_MS = 3000L
    private const val KEEP = 6
    private const val MAX_LEN = 130
    private val INTERESTING = Regex("onyx|touchhelper|epd|rawdraw|pen|scribble|hiddenapi", RegexOption.IGNORE_CASE)
    private val LEVEL = Regex("^([VDIWEF])/([^(]+)\\(")

    private val latest = AtomicReference<List<String>>(listOf("(logcat: starting)"))
    @Volatile private var started = false

    fun lines(): List<String> {
        start()
        return latest.get()
    }

    @Synchronized
    private fun start() {
        if (started) return
        started = true
        Thread({
            while (true) {
                latest.set(read())
                try {
                    Thread.sleep(PERIOD_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "sdk-log-tail").apply { isDaemon = true }.start()
    }

    private fun read(): List<String> =
        try {
            val p = ProcessBuilder("logcat", "-d", "-t", "600", "-v", "brief", "--pid=${Process.myPid()}")
                .redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().use { it.readLines() }
            p.destroy()
            val keep = out.filter { line ->
                val m = LEVEL.find(line) ?: return@filter false
                val level = m.groupValues[1]
                val tag = m.groupValues[2]
                !tag.startsWith("Notate.") && (level in "WEF" || INTERESTING.containsMatchIn(tag) || INTERESTING.containsMatchIn(line))
            }
            val tail = keep.takeLast(KEEP).map { it.take(MAX_LEN) }
            if (tail.isEmpty()) listOf("(logcat: nothing from SDK; ${out.size} lines read)") else tail
        } catch (e: Throwable) {
            listOf("(logcat unavailable: ${e.javaClass.simpleName} ${e.message?.take(60)})")
        }
}
