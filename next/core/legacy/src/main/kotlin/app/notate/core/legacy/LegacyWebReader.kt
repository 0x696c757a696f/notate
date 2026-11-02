package app.notate.core.legacy

import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * Reads an old web document folder without modifying it: `meta.json`, `content/`, notes as v2 (`state.json`, `ink/<key>.json`
 * plus `ink.journal` replay) or v1 (`annotations.json`). Corrupt parts are skipped with a warning. Any thread.
 */
object LegacyWebReader {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /** Ink file key for a page href: first 20 hex chars of SHA-1 of the href (matches the old `WebInkStoreV2.keyFor`). */
    fun keyFor(href: String): String =
        MessageDigest.getInstance("SHA-1").digest(href.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)

    fun read(webdocDir: File): LegacyWebDoc {
        val warnings = ArrayList<String>()
        val metaFile = File(webdocDir, "meta.json")
        if (!metaFile.isFile) throw LegacyFormatException("Missing meta.json in ${webdocDir.name}")
        val meta = try {
            json.decodeFromString(LegacyWebMeta.serializer(), metaFile.readText())
        } catch (e: Exception) {
            throw LegacyFormatException("Unreadable meta.json: ${e.message}", e)
        }
        val stateFile = File(webdocDir, "state.json")
        val v1File = File(webdocDir, "annotations.json")
        val journal = File(webdocDir, "ink.journal")
        var strokes: MutableMap<String, List<LegacyWebStroke>> = HashMap()
        var state = LegacyWebState()
        var useV2 = stateFile.isFile || (!v1File.isFile && journal.length() > 0)
        if (!useV2 && v1File.isFile) {
            try {
                val v1 = json.decodeFromString(LegacyWebV1.serializer(), v1File.readText())
                strokes = HashMap(v1.strokes)
                state = LegacyWebState(lastPage = v1.lastPage, scrollY = v1.scrollY, layoutWidth = v1.layoutWidth, penMode = v1.penMode, layout = v1.layout)
            } catch (e: Exception) {
                warnings.add("Unreadable annotations.json: ${e.message}")
            }
        }
        if (useV2) {
            if (stateFile.isFile) {
                try {
                    state = json.decodeFromString(LegacyWebState.serializer(), stateFile.readText())
                } catch (e: Exception) {
                    warnings.add("Unreadable state.json: ${e.message}")
                }
            }
            for ((href, key) in state.pages) {
                val f = File(webdocDir, "ink/$key.json")
                if (!f.isFile) { warnings.add("Missing ink file for $href"); continue }
                try {
                    strokes[href] = json.decodeFromString(LegacyWebInkFile.serializer(), f.readText()).strokes
                } catch (e: Exception) {
                    warnings.add("Unreadable ink for $href: ${e.message}")
                }
            }
            replay(journal, state.appliedSeq, strokes, warnings)
        }
        return LegacyWebDoc(
            meta, strokes.filterValues { it.isNotEmpty() }, state.lastPage, state.scrollY, state.layoutWidth, state.layout,
            state.penMode, File(webdocDir, "content"), warnings,
        )
    }

    /** Applies journal lines newer than [appliedSeq]; unreadable or truncated lines are skipped. */
    private fun replay(file: File, appliedSeq: Long, strokes: MutableMap<String, List<LegacyWebStroke>>, warnings: MutableList<String>) {
        if (!file.isFile) return
        var bad = 0
        file.forEachLine(Charsets.UTF_8) { line ->
            if (line.isBlank()) return@forEachLine
            val op = try {
                json.decodeFromString(LegacyWebJournalOp.serializer(), line)
            } catch (e: Exception) {
                bad++; return@forEachLine
            }
            if (op.s <= appliedSeq) return@forEachLine
            strokes[op.h] = if (op.k == "add") strokes[op.h].orEmpty() + op.st else op.st
        }
        if (bad > 0) warnings.add("Skipped $bad unreadable journal line(s)")
    }
}
