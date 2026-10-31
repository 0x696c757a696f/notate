package com.alexdremov.notate.ui.split

import android.app.Activity
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.alexdremov.notate.ui.tabs.DocKind
import com.alexdremov.notate.ui.tabs.DocTab
import com.alexdremov.notate.ui.tabs.SplitOrientation
import com.alexdremov.notate.ui.tabs.Workspace
import com.alexdremov.notate.webdoc.WebDocActivity

/**
 * The "Open beside" / "Open below" dialog shared by the note editor and the reader: the open tabs,
 * "Browse notes…" (the host launches its note picker) and "Browse books…" (the book library).
 */
object SplitPartnerPicker {
    fun show(
        activity: Activity,
        workspace: Workspace,
        currentPath: String,
        orientation: SplitOrientation,
        onBrowseNotes: () -> Unit,
        onPicked: (DocTab) -> Unit,
    ) {
        val candidates = workspace.tabs.filter { it.path != currentPath }
        val labels = candidates.map { it.title } + "Browse notes…" + "Browse books…"
        AlertDialog
            .Builder(activity)
            .setTitle(if (orientation == SplitOrientation.SIDE_BY_SIDE) "Open beside" else "Open below")
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    which < candidates.size -> onPicked(candidates[which])
                    which == candidates.size + 1 -> showBookPicker(activity, currentPath, onPicked)
                    else -> onBrowseNotes()
                }
            }.show()
    }

    private fun showBookPicker(
        activity: Activity,
        currentPath: String,
        onPicked: (DocTab) -> Unit,
    ) {
        val books = WebDocActivity.library(activity).list().filter { it.id != currentPath }
        if (books.isEmpty()) {
            Toast.makeText(activity, "No books yet: import one from Home", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog
            .Builder(activity)
            .setTitle("Books")
            .setItems(books.map { it.title }.toTypedArray()) { _, which ->
                onPicked(DocTab(DocKind.WEB, books[which].id, books[which].title))
            }.show()
    }
}
