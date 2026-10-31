package com.alexdremov.notate.input

enum class TouchAction { DOWN, MOVE, UP, CANCEL, POINTER_DOWN, POINTER_UP }

/** One finger event reduced to what palm rejection needs; coordinates are screen (raw) pixels. */
class TouchSample(
    val action: TouchAction,
    val x: Float,
    val y: Float,
    val majorsPx: FloatArray,
    val time: Long,
)

enum class Handedness { RIGHT, LEFT }

/**
 * Notability-style palm rejection (the only palm logic in the app): fingers scroll and zoom, the pen writes, and the hand that holds
 * the pen never moves the page.
 *
 * A finger touch is the writing hand when the pen touches the screen or left it less than [graceMs]
 * ago, when it lands in the wrist zone of a hovering pen (below and to the side of the pen tip, the
 * way a resting hand sits), or when its contact is as large as a palm ([palmMinSizePx], only trusted
 * once [TouchSizeCalibrator] has seen the panel report plausible finger sizes). A finger far from the
 * hovering pen, such as the other hand, always scrolls.
 *
 * One instance serves a whole window (every pane in it): the pen reports through [onHover]/[onPenDown]/
 * [onPenUp]/[reset], and each pane feeds its finger events through [onTouch], which decides per gesture.
 *
 * Pen calls may come from the pen service thread; times are `SystemClock.uptimeMillis()`, positions are
 * screen (raw) coordinates.
 */
class PalmRejector(
    private val palmMinSizePx: Float,
    private val graceMs: Long = GRACE_MS,
    private val pxPerCm: Float = 100f,
    private val calibrator: TouchSizeCalibrator = TouchSizeCalibrator(pxPerCm / 10f),
) {
    companion object {
        /** How long after the pen leaves the screen (or hover range) a touch still counts as the palm. */
        const val GRACE_MS = 300L

        /** A stroke that never reported its end must not disable fingers for good. */
        const val MAX_STROKE_MS = 2_000L

        /** Same for a hover whose exit was never delivered: a hovering pen keeps sending moves. */
        private const val MAX_HOVER_SILENCE_MS = 3_000L

        /** Contact size above which a touch is a palm or the side of the hand, not a fingertip. */
        const val PALM_MIN_SIZE_MM = 30f

        /** The wrist zone starts this far before the pen tip (towards the writing hand's side). */
        const val WRIST_BACK_CM = 2f

        /** The wrist zone ends this far from the pen tip. */
        const val WRIST_RADIUS_CM = 12f
    }

    @Volatile
    var handedness = Handedness.RIGHT

    @Volatile
    private var hovering = false

    @Volatile
    private var lastHoverAt = Long.MIN_VALUE / 2

    @Volatile
    private var hoverX = 0f

    @Volatile
    private var hoverY = 0f

    @Volatile
    private var penDownAt = -1L

    @Volatile
    private var penLeftAt = Long.MIN_VALUE / 2

    /** Hover enter or move at screen position ([x], [y]). */
    fun onHover(
        now: Long,
        x: Float,
        y: Float,
    ) {
        hovering = true
        lastHoverAt = now
        hoverX = x
        hoverY = y
    }

    fun onHoverExit(now: Long) {
        hovering = false
        penLeftAt = now
    }

    fun onPenDown(now: Long) {
        penDownAt = now
    }

    fun onPenUp(now: Long) {
        penDownAt = -1L
        penLeftAt = now
    }

    /** The pen's stroke or hover was cut off without an end event (pane switch, ink gate): forget it. */
    fun reset() {
        hovering = false
        penDownAt = -1L
        penLeftAt = Long.MIN_VALUE / 2
        // A finger gesture already being rejected stays rejected until it ends.
    }

    /** The pen is on the screen, or left it moments ago. */
    fun isPenContact(now: Long): Boolean {
        val down = penDownAt
        if (down >= 0 && now - down < MAX_STROKE_MS) return true
        return now - penLeftAt < graceMs
    }

    /** Whether ([x], [y]) is where the writing hand rests, given the hovering pen. */
    fun isInWristZone(
        x: Float,
        y: Float,
    ): Boolean {
        val back = WRIST_BACK_CM * pxPerCm
        val radius = WRIST_RADIUS_CM * pxPerCm
        val dx = x - hoverX
        val dy = y - hoverY
        if (dy < -back) return false
        val inside = if (handedness == Handedness.RIGHT) dx >= -back else dx <= back
        return inside && dx * dx + dy * dy <= radius * radius
    }

    /** Whether a finger that landed at ([x], [y]) belongs to the writing hand. */
    fun shouldReject(
        now: Long,
        x: Float,
        y: Float,
    ): Boolean {
        if (isPenContact(now)) return true
        return hovering && now - lastHoverAt < MAX_HOVER_SILENCE_MS && isInWristZone(x, y)
    }

    // --- Per-gesture decision (main thread) ---

    private var rejecting = false
    private var gestureDownX = 0f
    private var gestureDownY = 0f

    /** The current (or last) finger gesture had a palm-sized contact: it must not even tap. */
    var palmSizedGesture = false
        private set

    /** What to do with a finger touch event. */
    enum class Decision {
        /** Let the pane handle it. */
        ACCEPT,

        /** Swallow it. */
        REJECT,

        /** The gesture just turned out to be the writing hand mid-way: cancel what it started, then swallow. */
        CANCEL_ONGOING,
    }

    /**
     * Classifies one finger event. The verdict is made once per gesture, from where it landed, and
     * re-checked until the first rejection (the pen may arrive after the finger).
     *
     * @param forceReject the pane wants this gesture dropped regardless (finger mode Off, pen drawing here)
     * @param canReject false while the pane owns the gesture already (e.g. dragging a selection) or the
     *   touch is outside the page, so a rejection must not start
     */
    fun onTouch(
        s: TouchSample,
        forceReject: Boolean = false,
        canReject: Boolean = true,
    ): Decision {
        if (s.action == TouchAction.DOWN) {
            rejecting = false
            palmSizedGesture = false
            gestureDownX = s.x
            gestureDownY = s.y
            observeFingerDown(s.majorsPx.firstOrNull() ?: 0f)
        }
        var started = false
        if (!rejecting && canReject) {
            if (!palmSizedGesture) palmSizedGesture = isPalmSized(s.majorsPx)
            if (forceReject || palmSizedGesture || shouldReject(s.time, gestureDownX, gestureDownY)) {
                rejecting = true
                started = s.action != TouchAction.DOWN
            }
        }
        if (!rejecting) return Decision.ACCEPT
        if (s.action == TouchAction.UP || s.action == TouchAction.CANCEL) rejecting = false
        return if (started) Decision.CANCEL_ONGOING else Decision.REJECT
    }

    /** A single-finger DOWN: lets the calibrator learn what a fingertip looks like on this panel. */
    fun observeFingerDown(touchMajorPx: Float) = calibrator.add(touchMajorPx)

    /** [touchMajors]: the contact sizes of the touch's pointers, in pixels (0 if the panel doesn't report them). */
    fun isPalmSized(touchMajors: FloatArray): Boolean = calibrator.enabled && touchMajors.any { it >= palmMinSizePx }
}
