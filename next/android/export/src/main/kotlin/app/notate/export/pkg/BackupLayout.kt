package app.notate.export.pkg

import org.json.JSONArray
import org.json.JSONObject

/** Folder structure and tag mapping saved next to the backups as `library.json`. */
data class BackupLayout(
    val folders: List<Folder>,
    val docs: List<Doc>,
) {
    data class Folder(val id: String, val parentId: String?, val name: String)

    /** [tags] are tag names. */
    data class Doc(val id: String, val title: String, val folderId: String?, val tags: List<String>, val pinned: Boolean)

    fun toJson(): String = JSONObject().apply {
        put("format", "notate-library")
        put("version", 1)
        put("folders", JSONArray(folders.map { f ->
            JSONObject().put("id", f.id).put("parentId", f.parentId ?: JSONObject.NULL).put("name", f.name)
        }))
        put("docs", JSONArray(docs.map { d ->
            JSONObject().put("id", d.id).put("title", d.title).put("folderId", d.folderId ?: JSONObject.NULL)
                .put("tags", JSONArray(d.tags)).put("pinned", d.pinned)
        }))
    }.toString(2)

    /** Folders ordered so every parent precedes its children (cycles and unknown parents are treated as roots). */
    fun foldersParentFirst(): List<Folder> {
        val byId = folders.associateBy { it.id }
        val out = ArrayList<Folder>()
        val done = HashSet<String>()
        fun add(f: Folder, seen: Set<String>) {
            if (f.id in done) return
            val p = f.parentId?.let(byId::get)
            if (p != null && p.id !in seen) add(p, seen + f.id)
            if (done.add(f.id)) out.add(f)
        }
        folders.forEach { add(it, emptySet()) }
        return out
    }

    companion object {
        fun parse(json: String): BackupLayout? = try {
            val o = JSONObject(json)
            if (o.optString("format") != "notate-library") null
            else BackupLayout(
                folders = o.optJSONArray("folders").objects().map {
                    Folder(it.getString("id"), if (it.isNull("parentId")) null else it.getString("parentId"), it.getString("name"))
                },
                docs = o.optJSONArray("docs").objects().map { d ->
                    Doc(
                        d.getString("id"), d.optString("title"), if (d.isNull("folderId")) null else d.getString("folderId"),
                        d.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                        d.optBoolean("pinned"),
                    )
                },
            )
        } catch (e: org.json.JSONException) {
            null
        }

        private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
    }
}
