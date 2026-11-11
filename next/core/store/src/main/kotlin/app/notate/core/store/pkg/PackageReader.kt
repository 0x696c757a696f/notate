package app.notate.core.store.pkg

import app.notate.core.model.DocId
import app.notate.core.store.codec.JsonCodecs
import app.notate.core.store.sql.SqlDriverFactory
import app.notate.core.web.ImportLimits
import app.notate.core.web.WebImportException
import app.notate.core.web.ZipSafety
import java.io.File
import java.util.zip.ZipException
import java.util.zip.ZipFile

/** Reads `.notate` packages. [drivers] is only needed to rewrite the document id in [extract]. */
class PackageReader(private val drivers: SqlDriverFactory) {

    /** True when [zip] is a ZIP with a `manifest.json` whose `format` is `notate-package`. */
    fun isNotatePackage(zip: File): Boolean = try { readManifest(zip); true } catch (e: PackageException) { false }

    /** Reads only the manifest and entry sizes. Throws [PackageException] for anything that is not a current-version package. */
    fun inspect(zip: File): PackageInfo {
        val manifest = readManifest(zip)
        var db = 0L
        var assets = 0L
        ZipFile(zip).use { z ->
            for (e in z.entries()) {
                if (e.name == PackageManifest.DB) db = e.size
                else if (e.name.startsWith(PackageManifest.ASSETS) && !e.isDirectory) assets += e.size
            }
        }
        return PackageInfo(manifest.docId, manifest.title, manifest.tags, manifest.exportedAt, manifest.modifiedAt, db, assets)
    }

    /**
     * Reads the manifest from a stream without seeking (the writer puts `manifest.json` first); null when [input] is not a
     * current-version package. Sizes are 0. Used to list a backup folder cheaply.
     */
    fun peek(input: java.io.InputStream): PackageInfo? {
        val zin = java.util.zip.ZipInputStream(input)
        val entry = try { zin.nextEntry } catch (e: ZipException) { null } ?: return null
        if (entry.name != PackageManifest.MANIFEST) return null
        return try {
            val bytes = readLimited(zin)
            val m = PackageManifest.json.decodeFromString(PackageManifest.serializer(), bytes.toString(Charsets.UTF_8))
            if (m.format != PackageManifest.FORMAT || m.version > PackageManifest.VERSION) null
            else PackageInfo(m.docId, m.title, m.tags, m.exportedAt, m.modifiedAt, 0, 0)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Extracts the document into [targetDir] (becomes the document folder: `doc.db`, `assets/`). When [newDocId] is given,
     * the document id inside `doc.db` is rewritten. On failure [targetDir] is removed.
     */
    fun extract(zip: File, targetDir: File, newDocId: DocId? = null, limits: ImportLimits = ImportLimits()) {
        readManifest(zip)
        ZipFile(zip).use { z ->
            for (e in z.entries()) {
                val n = e.name
                if (n != PackageManifest.MANIFEST && n != PackageManifest.DB && !n.startsWith(PackageManifest.ASSETS)) {
                    throw PackageException(PackageException.Reason.UNSAFE, "Unexpected entry in package: $n")
                }
            }
            if (z.getEntry(PackageManifest.DB) == null) throw PackageException(PackageException.Reason.NOT_A_PACKAGE, "Package has no doc.db")
        }
        try {
            ZipSafety.extract(zip, targetDir, limits)
            File(targetDir, PackageManifest.MANIFEST).delete()
            if (newDocId != null) rewriteId(File(targetDir, PackageManifest.DB), newDocId)
        } catch (e: WebImportException) {
            targetDir.deleteRecursively()
            val reason = when (e.reason) {
                WebImportException.Reason.TOO_LARGE -> PackageException.Reason.TOO_LARGE
                WebImportException.Reason.UNSAFE -> PackageException.Reason.UNSAFE
                else -> PackageException.Reason.NOT_A_PACKAGE
            }
            throw PackageException(reason, e.message ?: "Invalid package", e)
        } catch (t: Throwable) {
            targetDir.deleteRecursively()
            throw t
        }
    }

    private fun rewriteId(db: File, id: DocId) {
        val driver = drivers.open(db, readOnly = false)
        try {
            driver.transaction {
                val meta = driver.query("SELECT value FROM meta WHERE key = 'doc'") { JsonCodecs.decodeMeta(it.string(0)!!) }.single()
                driver.execute("UPDATE meta SET value = ? WHERE key = 'doc'", JsonCodecs.encodeMeta(meta.copy(id = id)))
            }
        } finally {
            driver.close()
        }
    }

    private fun readManifest(zip: File): PackageManifest {
        val z = try { ZipFile(zip) } catch (e: ZipException) {
            throw PackageException(PackageException.Reason.NOT_A_PACKAGE, "Not a ZIP file", e)
        }
        z.use {
            val entry = it.getEntry(PackageManifest.MANIFEST) ?: throw PackageException(PackageException.Reason.NOT_A_PACKAGE, "No manifest")
            if (entry.size > 1_000_000) throw PackageException(PackageException.Reason.NOT_A_PACKAGE, "Manifest too large")
            val m = try {
                PackageManifest.json.decodeFromString(PackageManifest.serializer(), readLimited(it.getInputStream(entry)).toString(Charsets.UTF_8))
            } catch (e: Exception) {
                throw PackageException(PackageException.Reason.NOT_A_PACKAGE, "Bad manifest", e)
            }
            if (m.format != PackageManifest.FORMAT) throw PackageException(PackageException.Reason.NOT_A_PACKAGE, "Not a Notate package")
            if (m.version > PackageManifest.VERSION) {
                throw PackageException(PackageException.Reason.UNSUPPORTED_VERSION, "Package was made by a newer version of Notate")
            }
            return m
        }
    }

    /** Reads at most [MAX_MANIFEST] bytes (a longer manifest is not a package); avoids API 33's `readNBytes`. */
    private fun readLimited(input: java.io.InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() <= MAX_MANIFEST) {
            val n = input.read(buf)
            if (n < 0) return out.toByteArray()
            out.write(buf, 0, n)
        }
        throw PackageException(PackageException.Reason.NOT_A_PACKAGE, "Manifest too large")
    }

    private companion object { const val MAX_MANIFEST = 1_000_000 }
}
