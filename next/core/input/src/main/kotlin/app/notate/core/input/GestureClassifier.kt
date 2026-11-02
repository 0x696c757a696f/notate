package app.notate.core.input

import kotlin.math.abs
import kotlin.math.hypot

sealed interface FingerGesture {
    data class PanStart(
        val x: Float,
        val y: Float,
    ) : FingerGesture

    /** Movement since the previous [Pan] (the first one: since the touch-down point). */
    data class Pan(
        val dx: Float,
        val dy: Float,
    ) : FingerGesture

    /** Release velocity in px/s. */
    data class PanEnd(
        val vx: Float,
        val vy: Float,
    ) : FingerGesture

    data class PinchStart(
        val fx: Float,
        val fy: Float,
    ) : FingerGesture

    /** [scale] is the factor since the previous [Pinch]; [dx]/[dy] the focus movement since then. */
    data class Pinch(
        val scale: Float,
        val fx: Float,
        val fy: Float,
        val dx: Float,
        val dy: Float,
    ) : FingerGesture

    data object PinchEnd : FingerGesture

    data class Tap(
        val fingers: Int,
        val x: Float,
        val y: Float,
    ) : FingerGesture

    data class LongPress(
        val x: Float,
        val y: Float,
    ) : FingerGesture

    data object Cancel : FingerGesture
}

/**
 * Pure finger-gesture state machine. Thresholds are in px (the Android layer converts from dp).
 * Feed finger samples of one gesture (first DOWN to last UP) to [onSample]; call [onTimer] now and
 * then (every ~50 ms) so a long press fires while the finger rests.
 */
class GestureClassifier(
    private val touchSlopPx: Float,
    private val longPressMs: Long = 500,
    private val multiTapMaxMs: Long = 250,
    private val multiTapSlopPx: Float,
) {
    companion object {
        /** All fingers must be up within this long after the first one landed for a tap. */
        const val TAP_MAX_MS = 300L
        private const val VELOCITY_WINDOW_MS = 100L
    }

    private enum class State { IDLE, PENDING, PAN, PINCH, DONE }

    private class Finger(
        val id: Int,
        val startX: Float,
        val startY: Float,
    ) {
        var x = startX
        var y = startY
    }

    private val fingers = ArrayList<Finger>()
    private var state = State.IDLE
    private var firstDownMs = 0L
    private var maxFingers = 0
    private var tapCandidate = false
    private var tapCx = 0f
    private var tapCy = 0f
    private var panX = 0f
    private var panY = 0f
    private var prevDist = 1f
    private var prevMx = 0f
    private var prevMy = 0f
    private val trail = ArrayList<Triple<Long, Float, Float>>()

    val isIdle: Boolean get() = state == State.IDLE

    fun onSample(s: PointerSample): List<FingerGesture> {
        val out = ArrayList<FingerGesture>(2)
        when (s.action) {
            PointerAction.DOWN -> {
                reset()
                fingers.add(Finger(s.id, s.x, s.y))
                state = State.PENDING
                firstDownMs = s.timeMs
                maxFingers = 1
                tapCandidate = true
                tapCx = s.x
                tapCy = s.y
            }
            PointerAction.POINTER_DOWN -> pointerDown(s, out)
            PointerAction.MOVE -> move(s, out)
            PointerAction.POINTER_UP, PointerAction.UP -> up(s, out)
            PointerAction.CANCEL -> out += cancel()
            else -> Unit
        }
        return out
    }

    fun onTimer(nowMs: Long): List<FingerGesture> {
        if (state != State.PENDING || fingers.size != 1 || !tapCandidate) return emptyList()
        if (nowMs - firstDownMs < longPressMs) return emptyList()
        val f = fingers[0]
        tapCandidate = false
        state = State.DONE
        return listOf(FingerGesture.LongPress(f.x, f.y))
    }

    fun cancel(): List<FingerGesture> {
        val started = state == State.PAN || state == State.PINCH
        reset()
        return if (started) listOf(FingerGesture.Cancel) else emptyList()
    }

    private fun reset() {
        fingers.clear()
        trail.clear()
        state = State.IDLE
        tapCandidate = false
        maxFingers = 0
    }

    private fun pointerDown(
        s: PointerSample,
        out: MutableList<FingerGesture>,
    ) {
        if (state == State.IDLE || fingers.any { it.id == s.id }) return
        fingers.add(Finger(s.id, s.x, s.y))
        maxFingers = maxOf(maxFingers, fingers.size)
        if (s.timeMs - firstDownMs > multiTapMaxMs) tapCandidate = false
        when (state) {
            State.PENDING -> {
                var sx = 0f
                var sy = 0f
                fingers.forEach {
                    sx += it.startX
                    sy += it.startY
                }
                tapCx = sx / fingers.size
                tapCy = sy / fingers.size
            }
            State.PAN -> {
                out += FingerGesture.PanEnd(0f, 0f)
                startPinch(out, fromStart = false)
            }
            else -> Unit
        }
    }

    private fun move(
        s: PointerSample,
        out: MutableList<FingerGesture>,
    ) {
        val f = fingers.firstOrNull { it.id == s.id } ?: return
        f.x = s.x
        f.y = s.y
        when (state) {
            State.PENDING ->
                if (fingers.size == 1) {
                    if (hypot(f.x - f.startX, f.y - f.startY) > touchSlopPx) {
                        tapCandidate = false
                        state = State.PAN
                        panX = f.startX
                        panY = f.startY
                        trail.clear()
                        trail.add(Triple(s.timeMs, f.x, f.y))
                        out += FingerGesture.PanStart(f.startX, f.startY)
                        pan(f, out)
                    }
                } else if (hypot(f.x - f.startX, f.y - f.startY) > multiTapSlopPx) {
                    tapCandidate = false
                    if (fingers.size == 2) {
                        startPinch(out, fromStart = true)
                        pinch(out)
                    } else {
                        state = State.DONE
                    }
                }
            State.PAN -> {
                trail.add(Triple(s.timeMs, f.x, f.y))
                while (trail.size > 1 && s.timeMs - trail[0].first > VELOCITY_WINDOW_MS) trail.removeAt(0)
                pan(f, out)
            }
            State.PINCH -> if (fingers.indexOf(f) < 2) pinch(out)
            else -> Unit
        }
    }

    private fun up(
        s: PointerSample,
        out: MutableList<FingerGesture>,
    ) {
        val f = fingers.firstOrNull { it.id == s.id } ?: return
        f.x = s.x
        f.y = s.y
        val idx = fingers.indexOf(f)
        fingers.remove(f)
        when (state) {
            State.PENDING ->
                if (fingers.isEmpty()) {
                    if (tapCandidate && s.timeMs - firstDownMs <= TAP_MAX_MS) {
                        out += FingerGesture.Tap(maxFingers, tapCx, tapCy)
                    }
                    reset()
                }
            State.PAN -> {
                trail.add(Triple(s.timeMs, f.x, f.y))
                out += FingerGesture.PanEnd(velocity(s.timeMs).first, velocity(s.timeMs).second)
                reset()
            }
            State.PINCH -> {
                if (idx < 2) {
                    out += FingerGesture.PinchEnd
                    state = State.DONE
                }
                if (fingers.isEmpty()) reset()
            }
            else -> if (fingers.isEmpty()) reset()
        }
    }

    private fun pan(
        f: Finger,
        out: MutableList<FingerGesture>,
    ) {
        val dx = f.x - panX
        val dy = f.y - panY
        if (dx == 0f && dy == 0f) return
        panX = f.x
        panY = f.y
        out += FingerGesture.Pan(dx, dy)
    }

    private fun velocity(now: Long): Pair<Float, Float> {
        val first = trail.firstOrNull() ?: return 0f to 0f
        val last = trail.last()
        val dt = last.first - first.first
        if (dt <= 0L || now - last.first > VELOCITY_WINDOW_MS) return 0f to 0f
        return (last.second - first.second) * 1000f / dt to (last.third - first.third) * 1000f / dt
    }

    private fun startPinch(
        out: MutableList<FingerGesture>,
        fromStart: Boolean,
    ) {
        state = State.PINCH
        val a = fingers[0]
        val b = fingers[1]
        val ax = if (fromStart) a.startX else a.x
        val ay = if (fromStart) a.startY else a.y
        val bx = if (fromStart) b.startX else b.x
        val by = if (fromStart) b.startY else b.y
        prevDist = maxOf(hypot(ax - bx, ay - by), 1f)
        prevMx = (ax + bx) / 2f
        prevMy = (ay + by) / 2f
        out += FingerGesture.PinchStart(prevMx, prevMy)
    }

    private fun pinch(out: MutableList<FingerGesture>) {
        val a = fingers[0]
        val b = fingers[1]
        val dist = maxOf(hypot(a.x - b.x, a.y - b.y), 1f)
        val mx = (a.x + b.x) / 2f
        val my = (a.y + b.y) / 2f
        val scale = dist / prevDist
        val dx = mx - prevMx
        val dy = my - prevMy
        if (abs(scale - 1f) < 1e-6f && dx == 0f && dy == 0f) return
        prevDist = dist
        prevMx = mx
        prevMy = my
        out += FingerGesture.Pinch(scale, mx, my, dx, dy)
    }
}
