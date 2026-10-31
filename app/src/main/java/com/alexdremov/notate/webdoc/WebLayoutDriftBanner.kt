package com.alexdremov.notate.webdoc

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Thin strip at the top of a page: the layout width differs from the one the handwriting was written
 * at, so reflowed text may no longer sit under the ink. Tap to dismiss for this page.
 */
class WebLayoutDriftBanner(
    context: Context,
) : TextView(context) {
    private var dismissedFor: String? = null
    private var pageKey: String? = null

    init {
        text = "Layout changed since you wrote here. Handwriting may be misaligned."
        textSize = 13f
        setTextColor(Color.BLACK)
        setBackgroundColor(Color.WHITE)
        gravity = Gravity.CENTER
        val pad = (6 * resources.displayMetrics.density).roundToInt()
        setPadding(pad, pad, pad, pad)
        visibility = View.GONE
        setOnClickListener {
            dismissedFor = pageKey
            visibility = View.GONE
        }
    }

    /** Adds the banner on top of [parent] (a FrameLayout holding the page and its ink). */
    fun attachTo(parent: FrameLayout) {
        parent.addView(
            this,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP),
        )
    }

    /** Shows or hides the banner for the page [href] according to [drifted]. */
    fun update(
        href: String?,
        drifted: Boolean,
    ) {
        pageKey = href
        visibility = if (drifted && href != null && dismissedFor != href) View.VISIBLE else View.GONE
    }
}
