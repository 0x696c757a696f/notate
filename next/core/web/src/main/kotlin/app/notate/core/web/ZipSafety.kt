package app.notate.core.web

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Size limits of one import. */
data class ImportLimits(
    val maxTotalBytes: Long = 1024L * 1024 * 1024,
    val maxFiles: Int = 50_000,
    val maxFileBytes: Long = 512L * 1024 * 1024,
)

/** Running totals of one import; throws [WebImportException] when a limit is exceeded. Not thread-safe. */
class ImportBudget(private val limits: ImportLimits = ImportLimits()) {
    var files = 0; private set
    var bytes = 0L; private set

    /** Registers one more file; call before writing it. */
    fun addFile() {
        if (++files > limits.maxFiles) throw tooLarge("more than ${limits.maxFiles} files")
    }

    /** Registers [n] more bytes of the current file whose size so far is [fileBytes]. */
    fun addBytes(n: Int, fileBytes: Long) {
        bytes += n
        if (fileBytes > limits.maxFileBytes) throw tooLarge("a file is larger than ${limits.maxFileBytes} bytes")
        if (bytes > limits.maxTotalBytes) throw tooLarge("more than ${limits.maxTotalBytes} bytes")
    }

    private fun tooLarge(what: String) = WebImportException(WebImportException.Reason.TOO_LARGE, "Import too large: $what")
}

/** Safe extraction and hashing of imported content. */
object ZipSafety {
    /** Streams [input] to [target], counting against [budget]. */
    fun copyLimited(input: InputStream, target: File, budget: ImportBudget) {
        target.parentFile?.mkdirs()
        budget.addFile()
        target.outputStream().buffered().use { out ->
            val buf = ByteArray(64 * 1024)
            var size = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                size += n
                budget.addBytes(n, size)
                out.write(buf, 0, n)
            }
        }
    }

    /**
     * Extracts [zip] into [target]. Rejects entries that would land outside [target] (ZIP Slip) and enforces the
     * limits on the bytes actually written (declared sizes are not trusted). Throws [WebImportException];
     * a file that is not a ZIP gives NOT_SUPPORTED.
     */
    fun extract(zip: File, target: File, limits: ImportLimits = ImportLimits()) {
        target.mkdirs()
        val root = target.canonicalPath + File.separator
        val budget = ImportBudget(limits)
        val zf = try {
            ZipFile(zip)
        } catch (e: java.util.zip.ZipException) {
            throw WebImportException(WebImportException.Reason.NOT_SUPPORTED, "Not a ZIP file", e)
        }
        zf.use { z ->
            val entries = z.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name
                if (name.contains('\u0000')) throw unsafe(name)
                val file = File(target, name.replace('\\', '/'))
                val canonical = file.canonicalPath
                if (!(canonical + File.separator).startsWith(root)) throw unsafe(name)
                if (entry.isDirectory) { file.mkdirs(); continue }
                z.getInputStream(entry).use { copyLimited(it, file, budget) }
            }
        }
    }

    /** Lower-case hex SHA-256 of a file's bytes. */
    fun sha256OfFile(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { feed(md, it) }
        return hex(md.digest())
    }

    /** SHA-256 over the sorted relative paths and contents of every file under [root] (folder imports). */
    fun sha256OfTree(root: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val files = root.walkTopDown().filter { it.isFile }.map { it to it.relativeTo(root).invariantSeparatorsPath }.sortedBy { it.second }
        for ((f, rel) in files) {
            md.update(rel.toByteArray(Charsets.UTF_8)); md.update(0)
            f.inputStream().buffered().use { feed(md, it) }
        }
        return hex(md.digest())
    }

    private fun feed(md: MessageDigest, input: InputStream) {
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun unsafe(name: String) = WebImportException(WebImportException.Reason.UNSAFE, "Archive entry escapes the target directory: $name")
}
