package com.alexdremov.notate.ui.tabs

import android.content.Context
import android.net.Uri
import com.alexdremov.notate.util.Logger
import kotlinx.serialization.json.Json

/** Keeps the open tabs and split pairs across launches. Main thread only. */
object WorkspaceStore {
    private const val PREFS = "workspace_tabs"
    private const val KEY = "workspace"
    private val json = Json { ignoreUnknownKeys = true }

    fun load(context: Context): Workspace {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return Workspace()
        return try {
            json.decodeFromString(Workspace.serializer(), raw)
        } catch (e: Exception) {
            Logger.w("WorkspaceStore", "Dropping unreadable tab state: ${e.message}")
            Workspace()
        }
    }

    fun save(
        context: Context,
        workspace: Workspace,
    ) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, json.encodeToString(Workspace.serializer(), workspace))
            .apply()
    }

    fun update(
        context: Context,
        transform: (Workspace) -> Workspace,
    ): Workspace = transform(load(context)).also { save(context, it) }

    /** File name without folders or extension, for a file path or a document URI. */
    fun titleFor(path: String): String {
        val decoded = Uri.decode(path)
        val name = decoded.substringAfterLast('/').substringAfterLast(':')
        val dot = name.lastIndexOf('.')
        return (if (dot > 0) name.substring(0, dot) else name).ifBlank { "Untitled" }
    }
}
