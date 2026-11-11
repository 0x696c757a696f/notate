package app.notate.core.input

/**
 * Decides, stroke by stroke, whether a pane inks a pen stroke from the e-ink driver's raw points or from its touch events
 * (02 §5.3). Replaces the old rule "once the driver has delivered one stroke, touch events never ink again", which lost every
 * stroke written after the driver went deaf while still marked active.
 *
 * - No active driver, a stroke already inked from touch events, or the first stroke after a focus switch: touch events ink.
 * - A driver that has not delivered a stroke yet (or missed the last one) is not trusted: touch events ink at once.
 * - A trusted driver: a touch pen-down is held for [holdMs]. A driver begin in that window takes the stroke and the held
 *   events are dropped; otherwise the held events ink it (late but not lost) and the driver is no longer trusted until it
 *   delivers a stroke again.
 * - A driver begin that arrives for a stroke the touch events already ink (or inked just now) is ignored to its end, so no
 *   stroke is stored twice.
 *
 * The caller owns the clock and the timer: after every call it schedules [onTimeout] at [deadline] (if not null).
 * Main thread only; times are uptime milliseconds.
 */
class PenSourceGate(
    private val holdMs: Long = HOLD_MS,
    private val duplicateMs: Long = DUPLICATE_MS,
) {
    /** The driver delivered the last stroke it was asked for. */
    var trusted = false
        private set

    /** Strokes the held touch events had to ink because the driver stayed silent (debug HUD). */
    var fallbacks = 0
        private set

    /** When the held events must ink if the driver has not begun; null while nothing is held. */
    var deadline: Long? = null
        private set

    private var held: ArrayList<PenEvent>? = null
    /** Driver begins that came before their touch pen-down (times); a pen-down within [holdMs] belongs to one of them. */
    private val earlyBegins = ArrayDeque<Long>()
    private var lastFallbackAt = 0L
    private var ignoringRaw = false

    /**
     * A pen event that reached the pane as a touch event. Returns the events to ink from touch now, in order (the held ones
     * first when they are released).
     */
    fun onTouch(ev: PenEvent, driverActive: Boolean, touchStroke: Boolean, driverStroke: Boolean, nowMs: Long): List<PenEvent> {
        if (ev is PenEvent.Hover) return emptyList()
        val h = held
        if (h != null) {
            if (!driverActive) return release() + ev       // the driver went away: what was held is a touch stroke
            // A new stroke while the last one is still held: the driver missed that one.
            if (ev is PenEvent.Down) return fallBack(nowMs) + onTouch(ev, driverActive, false, driverStroke, nowMs)
            if (ev is PenEvent.Cancel) { drop(); return emptyList() }
            if (h.size < MAX_HELD) h.add(ev)
            return emptyList()
        }
        if (!driverActive || touchStroke) return listOf(ev)
        if (ev !is PenEvent.Down) return emptyList()       // a stroke the driver owns
        if (ev.focusSwitched) return listOf(ev)
        prune(nowMs)
        if (driverStroke) {
            earlyBegins.removeFirstOrNull()
            return emptyList()
        }
        if (earlyBegins.isNotEmpty()) {
            earlyBegins.removeFirst()
            return emptyList()
        }
        if (!trusted) return listOf(ev)
        held = arrayListOf(ev)
        deadline = nowMs + holdMs
        return emptyList()
    }

    /** The timer at [deadline] fired: the held events ink if the driver still has not begun. */
    fun onTimeout(nowMs: Long): List<PenEvent> {
        val d = deadline ?: return emptyList()
        if (nowMs < d) return emptyList()
        return fallBack(nowMs)
    }

    private fun fallBack(nowMs: Long): List<PenEvent> {
        trusted = false
        fallbacks++
        lastFallbackAt = nowMs
        return release()
    }

    /** Releases what is held without blaming the driver (it was switched off or the pane closes). */
    fun flush(): List<PenEvent> = release()

    /**
     * The driver began a stroke. Returns true when the driver inks it; false when the touch events already ink this stroke
     * (its points and end are then to be ignored: see [onRawEnd]).
     */
    fun onRawBegin(nowMs: Long, touchStroke: Boolean): Boolean {
        trusted = true
        if (held != null) {
            drop()
            return true
        }
        if (touchStroke || (lastFallbackAt != 0L && nowMs - lastFallbackAt <= duplicateMs)) {
            lastFallbackAt = 0L
            ignoringRaw = true
            return false
        }
        prune(nowMs)
        earlyBegins.addLast(nowMs)
        return true
    }

    /** True while the driver's current stroke is being ignored ([onRawBegin] returned false). */
    val ignoringDriver: Boolean get() = ignoringRaw

    /** The driver ended a stroke. Returns false when that stroke was ignored. */
    fun onRawEnd(): Boolean {
        if (!ignoringRaw) return true
        ignoringRaw = false
        return false
    }

    private fun prune(nowMs: Long) {
        while (earlyBegins.isNotEmpty() && nowMs - earlyBegins.first() > holdMs) earlyBegins.removeFirst()
    }

    private fun release(): List<PenEvent> {
        val h = held ?: return emptyList()
        drop()
        return h
    }

    private fun drop() {
        held = null
        deadline = null
    }

    companion object {
        /** The driver's begin usually comes within a few ms of the touch pen-down; both go through the main thread. */
        const val HOLD_MS = 200L
        const val DUPLICATE_MS = 1000L
        private const val MAX_HELD = 4096
    }
}
