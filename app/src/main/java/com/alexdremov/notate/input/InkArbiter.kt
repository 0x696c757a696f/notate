package com.alexdremov.notate.input

/**
 * The only place that decides whether the pen driver inks, and the only caller of [DriverPort].
 *
 * One arbiter serves a window. At most one surface (the owner) has its driver active; a surface that
 * releases its session when it is not the owner is closed before the new owner is opened, because two
 * raw-drawing sessions fight over the digitizer. The owner's driver is active only while no
 * [BlockReason] applies, neither window-wide ([block]) nor to that surface.
 *
 * Main thread only.
 */
class InkArbiter {
    private class State(
        val surface: InkSurface,
    ) {
        var opened = false

        /** null = unknown (just opened), so the next reconcile always writes the state. */
        var active: Boolean? = null
        var suspended = false
        val reasons = HashSet<BlockReason>()
    }

    private val states = LinkedHashMap<InkSurface, State>()
    private val windowReasons = HashSet<BlockReason>()
    private var reconciling = false
    private var again = false

    var owner: InkSurface? = null
        private set

    /** Called when [isWindowInkAllowed] flips, for panes that have no driver but must stop software ink too. */
    var onWindowInkAllowedChanged: ((Boolean) -> Unit)? = null

    /**
     * Registers a surface. The first surface becomes the owner. Pass [alreadyOpen] if its driver session
     * was opened by the surface itself (web overlays create theirs in the constructor).
     */
    fun attach(
        surface: InkSurface,
        alreadyOpen: Boolean = false,
    ) {
        if (states.containsKey(surface)) return
        states[surface] = State(surface).also { it.opened = alreadyOpen }
        if (owner == null) owner = surface
        reconcile()
    }

    /** Unregisters a surface, shutting its driver down. */
    fun detach(surface: InkSurface) {
        val st = states[surface] ?: return
        if (owner === surface) owner = null
        shutDown(st)
        states.remove(surface)
        reconcile()
    }

    /** Gives the pen to [surface] (null: to nobody). The previous owner's driver is released first. */
    fun setOwner(surface: InkSurface?) {
        if (surface != null && !states.containsKey(surface)) attach(surface)
        if (owner === surface) return
        owner = surface
        reconcile()
    }

    fun isOwner(surface: InkSurface) = owner === surface

    /** Window-wide reason (popups, sidebar, divider...). */
    fun block(
        reason: BlockReason,
        on: Boolean,
    ) {
        if (on == windowReasons.contains(reason)) return
        val before = isWindowInkAllowed
        if (on) windowReasons.add(reason) else windowReasons.remove(reason)
        reconcile()
        if (before != isWindowInkAllowed) onWindowInkAllowedChanged?.invoke(isWindowInkAllowed)
    }

    /** Reason that concerns one surface only (its pan, its refresh, its write mode...). */
    fun block(
        surface: InkSurface,
        reason: BlockReason,
        on: Boolean,
    ) {
        val st = states[surface] ?: return
        if (on == st.reasons.contains(reason)) return
        if (on) st.reasons.add(reason) else st.reasons.remove(reason)
        reconcile()
    }

    fun isBlockedBy(reason: BlockReason) = windowReasons.contains(reason)

    fun isBlockedBy(
        surface: InkSurface,
        reason: BlockReason,
    ) = states[surface]?.reasons?.contains(reason) == true

    /** No window-wide reason forbids inking (what the toolbar and book panes ask). */
    val isWindowInkAllowed: Boolean get() = windowReasons.none { it.suspendsInk }

    /** The pen must not ink on [surface] at all right now. */
    fun isSuspended(surface: InkSurface): Boolean = states[surface]?.suspended == true

    /** The driver is really inking on [surface]. */
    fun isDriverActive(surface: InkSurface): Boolean = states[surface]?.active == true

    /**
     * The surface under the driver was (re)created: open the session again if this surface owns the pen.
     * A non-owner stays closed.
     */
    fun reopen(surface: InkSurface) {
        val st = states[surface] ?: return
        st.opened = false
        st.active = null
        reconcile()
    }

    /** The surface under the driver is gone: end the session (the surface stays attached). */
    fun closeSession(surface: InkSurface) {
        val st = states[surface] ?: return
        val wasActive = st.active == true
        st.active = false
        if (wasActive) {
            st.surface.driver.setActive(false)
            st.surface.onDriverDeactivated()
        }
        if (st.opened) st.surface.driver.close()
        st.opened = false
    }

    /** Restarts an active driver, so it picks up changed limit/exclusion rects. */
    fun restart(surface: InkSurface) {
        val st = states[surface] ?: return
        if (st.active != true) return
        st.surface.driver.setActive(false)
        st.surface.driver.setActive(true)
    }

    /** Runs [action] (a panel refresh) with the driver aside, then puts it back if it should be on. */
    fun <T> pausedDuring(
        surface: InkSurface,
        reason: BlockReason = BlockReason.REFRESH,
        action: () -> T,
    ): T {
        val wasBlocked = isBlockedBy(surface, reason)
        if (!wasBlocked) block(surface, reason, true)
        try {
            return action()
        } finally {
            if (!wasBlocked) block(surface, reason, false)
        }
    }

    private fun shutDown(st: State) {
        val wasActive = st.active == true
        st.active = false
        if (wasActive && st.opened) {
            st.surface.driver.setActive(false)
            st.surface.onDriverDeactivated()
        }
        if (st.opened) {
            st.surface.driver.close()
            st.opened = false
        }
        if (st.suspended) {
            st.suspended = false
            st.surface.onInkSuspended(false)
        }
    }

    private fun reconcile() {
        if (reconciling) {
            again = true
            return
        }
        reconciling = true
        try {
            do {
                again = false
                reconcileOnce()
            } while (again)
        } finally {
            reconciling = false
        }
    }

    private fun reconcileOnce() {
        val hardWindow = windowReasons.any { it.suspendsInk }
        // 1. Release everything that must not hold the driver, before anything is opened.
        for (st in states.values.toList()) {
            val isOwner = st.surface === owner
            val wantActive = isOwner && windowReasons.isEmpty() && st.reasons.isEmpty()
            if (!wantActive && st.active != false) {
                val wasActive = st.active == true
                st.active = false
                if (st.opened) st.surface.driver.setActive(false)
                if (wasActive) st.surface.onDriverDeactivated()
            }
            if (!isOwner && st.surface.closesWhenNotOwner && st.opened) {
                st.surface.driver.close()
                st.opened = false
            }
            val suspended = hardWindow || st.reasons.any { it.suspendsInk }
            if (suspended != st.suspended) {
                st.suspended = suspended
                st.surface.onInkSuspended(suspended)
            }
        }
        // 2. Open and activate the owner.
        val st = owner?.let { states[it] } ?: return
        if (!st.opened) {
            st.surface.driver.open()
            st.opened = true
            st.active = null
        }
        val wantActive = windowReasons.isEmpty() && st.reasons.isEmpty()
        if (st.active != wantActive) {
            st.active = wantActive
            st.surface.driver.setActive(wantActive)
        }
    }
}
