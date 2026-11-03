package app.notate.web.pane

import android.webkit.WebView

/**
 * Freezes web pages while the user writes on them. `WebView.pauseTimers()` stops layout, parsing and JavaScript timers for
 * every WebView of the process, so a page cannot redraw itself (animations, widgets, script-driven DOM changes) and compete
 * with the pen driver for the e-ink panel. Because the switch is process-wide, timers are paused only while every open web
 * pane asks for it (a pane in Interact mode, loading, or just touched by a finger keeps all of them running).
 * Main thread only.
 */
internal object WebTimers {
    private val wantsFrozen = LinkedHashMap<Any, Boolean>()
    private val views = LinkedHashMap<Any, WebView>()
    private var paused = false

    fun register(owner: Any, view: WebView) {
        wantsFrozen[owner] = false
        views[owner] = view
        apply()
    }

    /** Call before the pane's WebView is destroyed. */
    fun unregister(owner: Any) {
        val view = views.remove(owner)
        wantsFrozen.remove(owner)
        if (wantsFrozen.isEmpty()) {
            if (paused) runCatching { view?.resumeTimers() }
            paused = false
        } else {
            apply()
        }
    }

    fun want(owner: Any, frozen: Boolean) {
        if (owner !in wantsFrozen || wantsFrozen[owner] == frozen) return
        wantsFrozen[owner] = frozen
        apply()
    }

    val isPaused: Boolean get() = paused

    private fun apply() {
        val v = views.values.firstOrNull() ?: return
        val pause = wantsFrozen.isNotEmpty() && wantsFrozen.values.all { it }
        if (pause == paused) return
        paused = pause
        runCatching { if (pause) v.pauseTimers() else v.resumeTimers() }
    }
}
