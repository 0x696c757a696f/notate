package app.notate.platform.ink

import android.graphics.Rect
import app.notate.core.ink.driver.DriverInk
import app.notate.core.input.DriverPort

/** One raw pen sample in view-local pixels; [pressure] is normalised to 0..1. */
data class RawPoint(val x: Float, val y: Float, val pressure: Float, val tiltX: Float, val tiltY: Float, val tMs: Long)

/** Receives driver strokes; implemented by the panel and always called on the main thread, in order. */
interface RawPenSink {
    fun onRawBegin(p: RawPoint, eraser: Boolean)
    fun onRawMove(batch: List<RawPoint>)
    fun onRawEnd(p: RawPoint, eraser: Boolean)
}

/** A [DriverPort] that can also be configured; implemented by [OnyxDriverPort] and [NoDriverPort]. */
interface ConfigurableDriverPort : DriverPort {
    /** The look the driver draws with while the pen is down; null = the driver draws nothing. */
    fun setInk(ink: DriverInk?)

    /** Area the driver reacts in plus areas it ignores (toolbars, popups), in view-local pixels. */
    fun setLimits(limit: Rect, exclude: List<Rect>)
}

/** For devices without the Onyx driver: every call does nothing, so panels always use software ink. */
object NoDriverPort : ConfigurableDriverPort {
    override fun open() {}
    override fun close() {}
    override fun setActive(on: Boolean) {}
    override fun setInk(ink: DriverInk?) {}
    override fun setLimits(limit: Rect, exclude: List<Rect>) {}
}
