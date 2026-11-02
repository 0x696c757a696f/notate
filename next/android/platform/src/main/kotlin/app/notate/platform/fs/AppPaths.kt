package app.notate.platform.fs

import android.content.Context
import app.notate.core.model.DocId
import java.io.File

/** All on-disk locations of the app (03-data-model §5). Directories are created on first use. Any thread. */
class AppPaths(ctx: Context) {
    /** `filesDir/notate`. */
    val root: File = File(ctx.filesDir, "notate").also { it.mkdirs() }
    val libraryDb: File = File(root, "library.db")
    val crashDir: File = File(root, "crash").also { it.mkdirs() }
    val logDir: File = File(root, "logs").also { it.mkdirs() }
    val cacheDir: File = File(ctx.cacheDir, "notate").also { it.mkdirs() }

    /** `docs/<docId>/` holding `doc.db`, `assets/` and `thumbs/`; not created here (the store creates it). */
    fun docDir(id: DocId): File = File(File(root, "docs"), id.value)
}
