package app.notate.export.doc

import java.io.File

object ExportFiles {
    private val BAD = Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]")
    private const val MAX_TITLE = 80
    private const val KEEP_DAYS = 7L

    /** `<title>.<ext>` with characters that break file names replaced; letters of any script are kept. */
    fun fileName(title: String, ext: String): String =
        title.replace(BAD, "_").trim().trim('.').take(MAX_TITLE).trim().ifEmpty { "Untitled" } + "." + ext

    /** `<title>-<n>.png` for page [number] (1-based) of a multi-page export. */
    fun pageFileName(title: String, number: Int, ext: String): String = fileName("$title-$number", ext)

    /** Deletes files in [dir] older than 7 days. Any thread. */
    fun cleanOld(dir: File, now: Long = System.currentTimeMillis()) {
        val cutoff = now - KEEP_DAYS * 24 * 60 * 60 * 1000
        dir.listFiles()?.forEach { f -> if (f.isFile && f.lastModified() < cutoff) f.delete() }
    }
}
