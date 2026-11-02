package app.notate.platform.epd

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.View
import app.notate.core.input.EpdMode
import app.notate.core.input.EpdPort

/** [EpdPort] for non-Onyx devices: a refresh is a normal Android invalidate, the fast modes do nothing. Main thread only. */
object NoEpdPort : EpdPort<View> {
    private val main = Handler(Looper.getMainLooper())

    override fun enterFast() {}
    override fun exitFast() {}
    override fun enterAnimation() {}
    override fun exitAnimation() {}

    override fun invalidate(view: View, rect: IntArray?, mode: EpdMode) {
        if (rect == null) view.invalidate() else view.invalidate(Rect(rect[0], rect[1], rect[2], rect[3]))
    }

    override fun afterDraw(view: View, action: () -> Unit) {
        if (!view.isAttachedToWindow) {
            action()
            return
        }
        view.post(action)
    }

    override fun postDelayed(delayMs: Long, action: () -> Unit): Any {
        val r = Runnable { action() }
        main.postDelayed(r, delayMs)
        return r
    }

    override fun cancel(handle: Any) {
        main.removeCallbacks(handle as Runnable)
    }
}
