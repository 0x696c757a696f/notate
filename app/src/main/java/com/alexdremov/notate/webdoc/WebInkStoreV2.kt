package com.alexdremov.notate.webdoc

import com.alexdremov.notate.util.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** `state.json`: everything about a web document's notes except the strokes themselves. */
@Serializable
internal data class WebDocState(
    val version: Int = 2,
    val lastPage: String? = null,
    val scrollY: Map<String, Float> = emptyMap(),
    val layoutWidth: Map<String, Float> = emptyMap(),
    val penMode: String = PenMode.WRITE.name,
    /** Journal entries with a sequence number up to this are already inside the ink files. */
    val appliedSeq: Long = 0,
    /** Page href -> ink file key, for the pages that have ink. */
    val pages: Map<String, String> = emptyMap(),
)

/** `ink/<key>.json`: the strokes of one page. */
@Serializable
internal class WebInkFile(
    val href: String,
    val strokes: List<WebInkStroke>,
)

/** One line of `ink.journal`. [k] is `add` (append [st] to the page) or `set` (replace the page's strokes). */
@Serializable
internal class WebJournalOp(
    val s: Long,
    val h: String,
    val k: String,
    val st: List<WebInkStroke>,
)

/**
 * On-disk layout of a web document's notes (version 2):
 *
 * ```
 * state.json            reading position, pen mode, layout widths, page index
 * ink/<pageKey>.json    strokes of one page; only pages that changed are rewritten
 * ink.journal           JSON Lines of edits since the last checkpoint, replayed on open
 * annotations.v1.bak.json   the old single-file format, kept after migration
 * ```
 */
internal class WebInkStoreV2(
    private val dir: File,
    private val json: Json = WebDocLibrary.json,
    journalSyncMs: Long = 250,
) {
    companion object {
        private const val TAG = "WebInkStoreV2"
        const val STATE = "state.json"
        const val V1 = "annotations.json"
        const val V1_BACKUP = "annotations.v1.bak.json"

        fun keyFor(href: String): String =
            MessageDigest
                .getInstance("SHA-1")
                .digest(href.toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(20)

        fun opLine(
            json: Json,
            op: WebJournalOp,
        ): String = json.encodeToString(WebJournalOp.serializer(), op)
    }

    val journal = WebInkJournal(File(dir, "ink.journal"), journalSyncMs)
    private val inkDir get() = File(dir, "ink")

    /**
     * Reads the notes. With [openForSession] the store also migrates a v1 file, replays the journal
     * and rewrites a clean checkpoint (sequence numbers restart per session); without it nothing on
     * disk is touched, which is what diagnostics and tests want.
     */
    fun load(openForSession: Boolean = false): WebDocAnnotations {
        val stateFile = File(dir, STATE)
        val v1 = File(dir, V1)
        val journalFile = File(dir, "ink.journal")
        if (!stateFile.isFile) {
            // Nothing checkpointed yet (e.g. killed before the first save): the journal may still hold ink.
            if (!v1.isFile && journalFile.length() == 0L) return WebDocAnnotations()
            if (v1.isFile) {
                val old = readV1(v1, backupIfCorrupt = openForSession) ?: return WebDocAnnotations()
                if (openForSession) {
                    write(old, null, 0)
                    retireV1()
                }
                return old
            }
        }
        val state =
            if (!stateFile.isFile) {
                WebDocState()
            } else try {
                json.decodeFromString(WebDocState.serializer(), stateFile.readText())
            } catch (e: Exception) {
                Logger.w(TAG, "Corrupt state.json in ${dir.name}", e)
                if (openForSession) stateFile.copyTo(File(dir, "state.corrupt-${System.currentTimeMillis()}.json"), overwrite = true)
                WebDocState()
            }
        val strokes = HashMap<String, List<WebInkStroke>>()
        for ((href, key) in state.pages) {
            val f = File(inkDir, "$key.json")
            try {
                if (f.isFile) strokes[href] = json.decodeFromString(WebInkFile.serializer(), f.readText()).strokes
            } catch (e: Exception) {
                Logger.w(TAG, "Corrupt ink file for $href", e)
                if (openForSession) f.copyTo(File(inkDir, "$key.corrupt-${System.currentTimeMillis()}.json"), overwrite = true)
            }
        }
        val replayed = replay(strokes, state.appliedSeq)
        if (File(dir, V1).isFile && openForSession) retireV1()
        val result =
            WebDocAnnotations(
                strokes = strokes.filterValues { it.isNotEmpty() },
                lastPage = state.lastPage,
                scrollY = state.scrollY,
                layoutWidth = state.layoutWidth,
                penMode = state.penMode,
            )
        if (openForSession && (replayed > 0 || state.appliedSeq != 0L || File(dir, "ink.journal").length() > 0)) {
            write(result, null, 0)
            journal.reset()
        }
        return result
    }

    /** Applies journal lines newer than [appliedSeq]; bad or truncated lines are skipped. Returns how many were applied. */
    private fun replay(
        strokes: MutableMap<String, List<WebInkStroke>>,
        appliedSeq: Long,
    ): Int {
        val f = File(dir, "ink.journal")
        if (!f.isFile) return 0
        var applied = 0
        try {
            f.forEachLine(Charsets.UTF_8) { line ->
                if (line.isBlank()) return@forEachLine
                val op =
                    try {
                        json.decodeFromString(WebJournalOp.serializer(), line)
                    } catch (e: Exception) {
                        Logger.w(TAG, "Skipping unreadable journal line (${e.javaClass.simpleName})")
                        return@forEachLine
                    }
                if (op.s <= appliedSeq) return@forEachLine
                strokes[op.h] = if (op.k == "add") strokes[op.h].orEmpty() + op.st else op.st
                applied++
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Journal replay stopped early", e)
        }
        return applied
    }

    /**
     * Writes a checkpoint of [snapshot] covering journal entries up to [seq]. Only the pages in
     * [dirty] get their ink file rewritten (all of them when null); `state.json` is always rewritten
     * last, so a crash in between leaves the previous consistent state plus the journal.
     */
    fun write(
        snapshot: WebDocAnnotations,
        dirty: Set<String>?,
        seq: Long,
    ) {
        inkDir.mkdirs()
        val targets = dirty ?: snapshot.strokes.keys
        for (href in targets) {
            val list = snapshot.strokes[href].orEmpty()
            val file = File(inkDir, "${keyFor(href)}.json")
            if (list.isEmpty()) {
                file.delete()
            } else {
                atomicWrite(file, json.encodeToString(WebInkFile.serializer(), WebInkFile(href, list)))
            }
        }
        val state =
            WebDocState(
                lastPage = snapshot.lastPage,
                scrollY = snapshot.scrollY,
                layoutWidth = snapshot.layoutWidth,
                penMode = snapshot.penMode,
                appliedSeq = seq,
                pages = snapshot.strokes.filterValues { it.isNotEmpty() }.keys.associateWith { keyFor(it) },
            )
        atomicWrite(File(dir, STATE), json.encodeToString(WebDocState.serializer(), state))
        journal.resetIfCovered(seq)
    }

    private fun readV1(
        f: File,
        backupIfCorrupt: Boolean,
    ): WebDocAnnotations? =
        try {
            json.decodeFromString(WebDocAnnotations.serializer(), f.readText())
        } catch (e: Exception) {
            Logger.w(TAG, "Corrupt annotations in ${dir.name}", e)
            if (backupIfCorrupt) f.copyTo(File(dir, "annotations.corrupt-${System.currentTimeMillis()}.json"), overwrite = true)
            null
        }

    private fun retireV1() {
        val v1 = File(dir, V1)
        val bak = File(dir, V1_BACKUP)
        if (bak.exists()) bak.delete()
        if (!v1.renameTo(bak)) Logger.w(TAG, "Could not rename $V1 to $V1_BACKUP")
    }

    private fun atomicWrite(
        target: File,
        text: String,
    ) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw IOException("Could not write $target")
        }
    }
}
