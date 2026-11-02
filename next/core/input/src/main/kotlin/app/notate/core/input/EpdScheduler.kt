package app.notate.core.input

/** Panel waveform to refresh with. */
enum class EpdMode { GC, GU, DU, ANIMATION }

/** The panel's refresh and fast-mode switches; `V` is the view type (android.view.View on device). Main thread only. */
interface EpdPort<V : Any> {
    fun enterFast()
    fun exitFast()
    fun enterAnimation()
    fun exitAnimation()

    /** Refreshes [rect] (view coordinates `[l, t, r, b]`; null = the whole view) with the named waveform. */
    fun invalidate(view: V, rect: IntArray?, mode: EpdMode)

    /** Runs [action] once [view] has really been drawn (at once for a surface that draws synchronously). */
    fun afterDraw(view: V, action: () -> Unit)

    fun postDelayed(delayMs: Long, action: () -> Unit): Any
    fun cancel(handle: Any)
}
