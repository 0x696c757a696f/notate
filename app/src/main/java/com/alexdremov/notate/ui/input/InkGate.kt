package com.alexdremov.notate.ui.input

/**
 * Decides whether the stylus may ink, given every UI state that should turn it into a
 * plain pointer (toolbar touch, popups opened from the toolbar, sidebar, ...).
 *
 * Each source sets only its own [Reason]; ink is allowed only when no reason is active.
 * This keeps one source from re-enabling ink that another one still needs blocked
 * (e.g. lifting the stylus off the toolbar button that just opened pen settings).
 */
class InkGate(
    // Called after every update, so callers can re-assert the state if something else
    // wrote it in between.
    private val onUpdate: (inkAllowed: Boolean) -> Unit,
) {
    enum class Reason {
        TOOLBAR_TOUCH,
        TOOL_POPUP,
        SIDEBAR,
        PAGE_GRID,
        TOOLBAR_EDIT_MODE,

        // Any focusable popup, menu or dialog of this activity (covers new toolbar
        // buttons and popups without registering them here).
        WINDOW_UNFOCUSED,
    }

    private val active = mutableSetOf<Reason>()

    val isInkAllowed: Boolean
        get() = active.isEmpty()

    fun set(
        reason: Reason,
        blocking: Boolean,
    ) {
        if (blocking) active.add(reason) else active.remove(reason)
        onUpdate(isInkAllowed)
    }

    fun isBlockedBy(reason: Reason): Boolean = reason in active
}
