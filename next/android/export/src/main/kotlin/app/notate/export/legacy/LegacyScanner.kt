package app.notate.export.legacy

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.notate.core.legacy.LegacyNotateReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * Finds old `.notate` notes below a folder. A `.notate` ZIP is classified by its entries: `manifest.bin` means an old note,
 * `manifest.json` with `notate-package` means a new-format package (handed to the package importer), anything else is
 * reported as unreadable. Blocking; call on IO.
 */
object LegacyScanner {
    private const val EXT = "notate"
    private const val MAX_DEPTH = 16
    private const val MAX_MANIFEST = 4 * 1024 * 1024

    /** Scans the folder behind a `OpenDocumentTree` [tree]. */
    fun scanTree(context: Context, tree: Uri): ScanResult {
        val root = DocumentFile.fromTreeUri(context, tree) ?: return ScanResult(emptyList(), 0, listOf(ImportFailure(tree.toString(), "Cannot open folder")))
        val resolver = context.contentResolver
        return scan(object : Node {
            override val name = root.name ?: ""
            override val isDirectory: Boolean = true
            override val modified = root.lastModified()
            override val size = 0L
            override val file: File? = null
            override fun children(): List<Node> = docChildren(root, resolver)
            override fun open(): InputStream? = null
        })
    }

    /** Scans a plain directory (the app's own `projects/` folder, or a test folder). */
    fun scanDir(dir: File): ScanResult = scan(fileNode(dir))

    private interface Node {
        val name: String
        val isDirectory: Boolean
        val modified: Long
        val size: Long
        val file: File?
        fun children(): List<Node>
        fun open(): InputStream?
    }

    private fun fileNode(f: File): Node = object : Node {
        override val name = f.name
        override val isDirectory = f.isDirectory
        override val modified = f.lastModified()
        override val size = f.length()
        override val file = f
        override fun children(): List<Node> = f.listFiles()?.sortedBy { it.name }?.map { fileNode(it) } ?: emptyList()
        override fun open(): InputStream? = if (f.isFile) f.inputStream() else null
    }

    private fun docChildren(dir: DocumentFile, resolver: android.content.ContentResolver): List<Node> =
        dir.listFiles().sortedBy { it.name ?: "" }.map { d ->
            object : Node {
                override val name = d.name ?: ""
                override val isDirectory = d.isDirectory
                override val modified = d.lastModified()
                override val size = d.length()
                override val file: File? = null
                override fun children(): List<Node> = docChildren(d, resolver)
                override fun open(): InputStream? = resolver.openInputStream(d.uri)
            }
        }

    private fun scan(root: Node): ScanResult {
        val notes = ArrayList<LegacyEntry>()
        val bad = ArrayList<ImportFailure>()
        var packages = 0
        fun walk(node: Node, path: List<String>, depth: Int) {
            if (depth > MAX_DEPTH) return
            for (child in node.children()) {
                val name = child.name
                if (name.isEmpty() || name.startsWith(".")) continue
                val isNote = name.endsWith(".$EXT", ignoreCase = true)
                if (child.isDirectory) {
                    val dirFile = child.file
                    if (isNote && dirFile != null && File(dirFile, LegacyNotateReader.MANIFEST).isFile) {
                        val uuid = runCatching { LegacyNotateReader.peek(File(dirFile, LegacyNotateReader.MANIFEST).readBytes()).uuid }.getOrNull()
                        notes.add(LegacyEntry(name.dropLast(EXT.length + 1), path, child.modified, 0L, uuid, { null }, dirFile))
                    } else {
                        walk(child, path + name, depth + 1)
                    }
                    continue
                }
                if (!isNote) continue
                when (val p = probe(child::open)) {
                    is Probe.Old -> notes.add(LegacyEntry(name.dropLast(EXT.length + 1), path, child.modified, child.size, p.uuid, child::open))
                    Probe.Package -> packages++
                    is Probe.Bad -> bad.add(ImportFailure((path + name).joinToString("/"), p.reason))
                }
            }
        }
        walk(root, emptyList(), 0)
        return ScanResult(notes, packages, bad)
    }

    internal sealed interface Probe {
        class Old(val uuid: String?) : Probe
        data object Package : Probe
        class Bad(val reason: String) : Probe
    }

    /** Reads ZIP entry names in order until a manifest shows up. */
    internal fun probe(open: () -> InputStream?): Probe {
        val input = try { open() } catch (e: IOException) { return Probe.Bad("Cannot open file: ${e.message}") } ?: return Probe.Bad("Cannot open file")
        try {
            ZipInputStream(input.buffered()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    when (e.name.replace('\\', '/')) {
                        LegacyNotateReader.MANIFEST -> return Probe.Old(LegacyNotateReader.peek(readLimited(z)).uuid)
                        "manifest.json" -> if (readLimited(z).decodeToString().contains("notate-package")) return Probe.Package
                    }
                }
            }
        } catch (e: ZipException) {
            return Probe.Bad("Not a valid note file")
        } catch (e: IOException) {
            return Probe.Bad("Cannot read file: ${e.message}")
        } catch (e: app.notate.core.legacy.LegacyFormatException) {
            return Probe.Bad(e.message ?: "Unreadable note")
        }
        return Probe.Bad("Not a Notate note")
    }

    private fun readLimited(z: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() <= MAX_MANIFEST) {
            val n = z.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        if (out.size() > MAX_MANIFEST) throw IOException("manifest too large")
        return out.toByteArray()
    }
}
