package com.alexdremov.notate.ui.tabs

import android.content.Context
import android.content.Intent
import com.alexdremov.notate.CanvasActivity
import com.alexdremov.notate.webdoc.WebDocActivity

/** Which screen opens a tab: the note editor or the EPUB/HTML reader. */
object TabNavigator {
    fun intentFor(
        context: Context,
        tab: DocTab,
    ): Intent =
        when (tab.kind) {
            DocKind.NOTE -> Intent(context, CanvasActivity::class.java).putExtra("CANVAS_PATH", tab.path)
            DocKind.WEB -> WebDocActivity.openIntent(context, tab.path)
        }.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
}
