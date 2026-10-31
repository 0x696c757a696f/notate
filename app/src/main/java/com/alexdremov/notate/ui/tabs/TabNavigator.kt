package com.alexdremov.notate.ui.tabs

import android.content.Context
import android.content.Intent
import com.alexdremov.notate.CanvasActivity
import com.alexdremov.notate.webdoc.WebDocActivity

/** Which screen opens a tab: the note editor or the EPUB/HTML reader. */
object TabNavigator {
    /**
     * Which tab's activity hosts the split containing [tab]: a note host wins, else [tab] itself.
     * A split with a note is always shown by the note editor; a book|book split by the reader.
     */
    fun hostFor(
        workspace: Workspace,
        tab: DocTab,
    ): DocTab {
        val split = workspace.splitFor(tab.path) ?: return tab
        val partner = split.partnerOf(tab.path)?.let { workspace.tab(it) } ?: return tab
        return if (tab.kind == DocKind.WEB && partner.kind == DocKind.NOTE) partner else tab
    }

    fun intentFor(
        context: Context,
        tab: DocTab,
        workspace: Workspace = WorkspaceStore.load(context),
    ): Intent {
        val host = hostFor(workspace, tab)
        return when (host.kind) {
            DocKind.NOTE -> Intent(context, CanvasActivity::class.java).putExtra("CANVAS_PATH", host.path)
            DocKind.WEB -> WebDocActivity.openIntent(context, host.path)
        }.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}
