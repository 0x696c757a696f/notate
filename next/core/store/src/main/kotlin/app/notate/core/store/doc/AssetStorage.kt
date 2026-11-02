package app.notate.core.store.doc

import app.notate.core.model.AssetId
import app.notate.core.store.AssetKind
import app.notate.core.store.sql.SqlDriver
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Content-addressed asset files under `<doc dir>/assets` plus the `asset` table. */
internal class AssetStorage(private val driver: SqlDriver, private val dir: File) {
    private val assetsDir get() = File(dir, "assets")

    fun put(kind: AssetKind, mime: String, source: File, ext: String): AssetId {
        if (kind == AssetKind.WEB || source.isDirectory) return putWeb(mime, source)
        val md = MessageDigest.getInstance("SHA-256")
        var size = 0L
        source.inputStream().buffered().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n); size += n
            }
        }
        val id = AssetId(hex(md.digest()).substring(0, 32))
        val existing = driver.query("SELECT path FROM asset WHERE id = ?", id.value) { it.string(0)!! }.firstOrNull()
        if (existing != null && File(dir, existing).exists()) return id
        val rel = "assets/${id.value}.${ext.trimStart('.')}"
        val target = File(dir, rel)
        target.parentFile.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        Files.copy(source.toPath(), tmp.toPath(), StandardCopyOption.REPLACE_EXISTING)
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        driver.execute("INSERT OR IGNORE INTO asset(id, kind, mime, path, bytes) VALUES (?, ?, ?, ?, ?)", id.value, kind.code, mime, rel, size)
        return id
    }

    /** Copies a web bundle directory into `assets/web/`; the id hashes relative paths and contents. */
    private fun putWeb(mime: String, source: File): AssetId {
        require(source.isDirectory) { "Web assets must be directories" }
        val files = source.walkTopDown().filter { it.isFile }
            .map { it to it.relativeTo(source).invariantSeparatorsPath }.sortedBy { it.second }.toList()
        val md = MessageDigest.getInstance("SHA-256")
        var total = 0L
        for ((f, rel) in files) {
            md.update(rel.toByteArray(Charsets.UTF_8)); md.update(0)
            val len = f.length()
            for (i in 0 until 8) md.update((len ushr (8 * i)).toByte())
            f.inputStream().buffered().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            total += len
        }
        val id = AssetId(hex(md.digest()).substring(0, 32))
        val exists = driver.query("SELECT 1 FROM asset WHERE id = ?", id.value) { 1 }.isNotEmpty()
        if (exists) return id
        val target = File(assetsDir, "web")
        for ((f, rel) in files) {
            val dest = File(target, rel)
            dest.parentFile.mkdirs()
            Files.copy(f.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        driver.execute("INSERT OR IGNORE INTO asset(id, kind, mime, path, bytes) VALUES (?, ?, ?, ?, ?)",
            id.value, AssetKind.WEB.code, mime, "assets/web/", total)
        return id
    }

    fun file(id: AssetId): File? =
        driver.query("SELECT path FROM asset WHERE id = ?", id.value) { it.string(0)!! }.firstOrNull()?.let { File(dir, it) }

    /** Removes asset rows and files not in [used]; WEB assets are kept while [keepWeb]; AUDIO is never removed. */
    fun removeUnused(used: Set<String>, keepWeb: Boolean) {
        val rows = driver.query("SELECT id, kind, path FROM asset") { Triple(it.string(0)!!, it.long(1).toInt(), it.string(2)!!) }
        for ((id, kind, path) in rows) {
            val keep = id in used || kind == AssetKind.AUDIO.code || (kind == AssetKind.WEB.code && keepWeb)
            if (keep) continue
            driver.execute("DELETE FROM asset WHERE id = ?", id)
            File(dir, path).deleteRecursively()
        }
    }

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
}
