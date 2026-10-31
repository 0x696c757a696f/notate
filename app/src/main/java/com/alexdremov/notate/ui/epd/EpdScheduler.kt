package com.alexdremov.notate.ui.epd

/** What the scheduler needs from the panel; the Onyx implementation is [OnyxEpdPort]. [V] is the view type. */
interface EpdPort<V : Any> {
    fun enterFast()

    fun exitFast()

    fun enterAnimation()

    fun exitAnimation()

    /** Refreshes [rect] (view coordinates; null = the whole view) with the named waveform. */
    fun invalidate(
        view: V,
        rect: IntArray?,
        mode: EpdMode,
    )

    /** Runs [action] once [view] has really been drawn (at once for a surface that draws synchronously). */
    fun afterDraw(
        view: V,
        action: () -> Unit,
    )

    fun postDelayed(
        delayMs: Long,
        action: () -> Unit,
    ): Any

    fun cancel(handle: Any)
}

enum class EpdMode { GC, GU, DU, ANIMATION }

/** When a refresh runs. */
enum class EpdTiming {
    /** Right now. */
    NOW,

    /** After the view's pending frame was drawn, so the refresh shows the new content, not the old. */
    AFTER_DRAW,
}

/**
 * The only door to the panel's refresh and fast-mode switches.
 *
 * Fast ("turbo/transient") mode and the animation waveform are held by named owners: the mode is on as
 * long as anyone holds it, so a pan ending cannot switch it off under a selection drag. Owners are
 * idempotent (acquiring twice is one hold) and expire after [MAX_HOLD_MS], so a forgotten release
 * cannot leave the screen in a ghosting waveform for good.
 *
 * Main thread only.
 */
class EpdScheduler<V : Any>(
    private val port: EpdPort<V>,
) {
    companion object {
        const val MAX_HOLD_MS = 10_000L
    }

    private class Hold(
        var watchdog: Any?,
    )

    private val fastHolds = LinkedHashMap<String, Hold>()
    private val animationHolds = LinkedHashMap<String, Hold>()

    val isFast: Boolean get() = fastHolds.isNotEmpty()
    val isAnimation: Boolean get() = animationHolds.isNotEmpty()

    fun acquireFast(owner: String) {
        val wasEmpty = fastHolds.isEmpty()
        hold(fastHolds, owner) { releaseFast(owner) }
        if (wasEmpty) port.enterFast()
    }

    fun releaseFast(owner: String) {
        if (!drop(fastHolds, owner)) return
        if (fastHolds.isEmpty()) port.exitFast()
    }

    fun acquireAnimation(owner: String) {
        val wasEmpty = animationHolds.isEmpty()
        hold(animationHolds, owner) { releaseAnimation(owner) }
        if (wasEmpty) port.enterAnimation()
    }

    fun releaseAnimation(owner: String) {
        if (!drop(animationHolds, owner)) return
        if (animationHolds.isEmpty()) port.exitAnimation()
    }

    /** Refreshes [rect] ([left, top, right, bottom], null = whole view). */
    fun refresh(
        view: V,
        mode: EpdMode,
        rect: IntArray? = null,
        timing: EpdTiming = EpdTiming.NOW,
    ) {
        when (timing) {
            EpdTiming.NOW -> port.invalidate(view, rect, mode)
            EpdTiming.AFTER_DRAW -> port.afterDraw(view) { port.invalidate(view, rect, mode) }
        }
    }

    /** Runs [action] after [view] was drawn. */
    fun afterDraw(
        view: V,
        action: () -> Unit,
    ) = port.afterDraw(view, action)

    private fun hold(
        holds: LinkedHashMap<String, Hold>,
        owner: String,
        expire: () -> Unit,
    ) {
        holds[owner]?.watchdog?.let { port.cancel(it) }
        val h = holds.getOrPut(owner) { Hold(null) }
        h.watchdog = port.postDelayed(MAX_HOLD_MS, expire)
    }

    private fun drop(
        holds: LinkedHashMap<String, Hold>,
        owner: String,
    ): Boolean {
        val h = holds.remove(owner) ?: return false
        h.watchdog?.let { port.cancel(it) }
        return true
    }
}
