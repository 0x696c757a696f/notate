package com.alexdremov.notate.ui.epd

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import android.view.View
import android.view.ViewTreeObserver
import com.alexdremov.notate.util.Logger
import com.onyx.android.sdk.api.device.EpdDeviceManager
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode
import java.lang.reflect.Method

/** [EpdPort] for Onyx panels. Every call is guarded, so other devices simply get normal Android rendering. */
object OnyxEpdPort : EpdPort<View> {
    private const val TAG = "OnyxEpdPort"

    /** Longest wait for a view's frame before refreshing the panel anyway. */
    private const val DRAW_WAIT_MAX_MS = 150L

    private val main = Handler(Looper.getMainLooper())
    private var applyTransientUpdateMethod: Method? = null
    private var clearTransientUpdateMethod: Method? = null
    private var setEpdTurboMethod: Method? = null
    private var initialized = false

    private fun initReflection() {
        if (initialized) return
        initialized = true
        val clazz = EpdController::class.java
        applyTransientUpdateMethod = runCatching { clazz.getMethod("applyTransientUpdate", UpdateMode::class.java) }.getOrNull()
        clearTransientUpdateMethod = runCatching { clazz.getMethod("clearTransientUpdate", Boolean::class.javaPrimitiveType) }.getOrNull()
        setEpdTurboMethod = runCatching { clazz.getMethod("setEpdTurbo", Boolean::class.javaPrimitiveType) }.getOrNull()
    }

    override fun enterFast() {
        initReflection()
        try {
            setEpdTurboMethod?.invoke(null, true)
            applyTransientUpdateMethod?.invoke(null, UpdateMode.ANIMATION)
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to enter fast mode", e)
        }
    }

    override fun exitFast() {
        initReflection()
        try {
            // 'true' is what the native app uses on idle timeout.
            clearTransientUpdateMethod?.invoke(null, true)
            setEpdTurboMethod?.invoke(null, false)
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to exit fast mode", e)
        }
    }

    override fun enterAnimation() {
        runCatching { EpdDeviceManager.enterAnimationUpdate(true) }
    }

    override fun exitAnimation() {
        runCatching { EpdDeviceManager.exitAnimationUpdate(true) }
    }

    override fun invalidate(
        view: View,
        rect: IntArray?,
        mode: EpdMode,
    ) {
        val m =
            when (mode) {
                EpdMode.GC -> UpdateMode.GC
                EpdMode.GU -> UpdateMode.GU
                EpdMode.DU -> UpdateMode.DU
                EpdMode.ANIMATION -> UpdateMode.ANIMATION
            }
        try {
            if (rect == null) {
                EpdController.invalidate(view, m)
            } else {
                EpdController.invalidate(view, rect[0], rect[1], rect[2], rect[3], m)
            }
        } catch (t: Throwable) {
            Logger.d(TAG, "EPD invalidate unavailable: ${t.message}")
        }
    }

    override fun afterDraw(
        view: View,
        action: () -> Unit,
    ) {
        // A surface draws synchronously (lockCanvas/post), and a detached view never will.
        if (view is SurfaceView || !view.isAttachedToWindow || !view.viewTreeObserver.isAlive) {
            action()
            return
        }
        var done = false

        fun run() {
            if (!done) {
                done = true
                action()
            }
        }
        val listener =
            object : ViewTreeObserver.OnDrawListener {
                override fun onDraw() {
                    // The listener cannot remove itself from inside the draw pass; do it on the next loop iteration.
                    view.post {
                        runCatching { view.viewTreeObserver.removeOnDrawListener(this) }
                        run()
                    }
                }
            }
        view.viewTreeObserver.addOnDrawListener(listener)
        view.invalidate()
        view.postDelayed({
            runCatching { view.viewTreeObserver.removeOnDrawListener(listener) }
            run()
        }, DRAW_WAIT_MAX_MS)
    }

    override fun postDelayed(
        delayMs: Long,
        action: () -> Unit,
    ): Any {
        val r = Runnable { action() }
        main.postDelayed(r, delayMs)
        return r
    }

    override fun cancel(handle: Any) {
        main.removeCallbacks(handle as Runnable)
    }
}

fun toEpdMode(mode: UpdateMode): EpdMode =
    when (mode) {
        UpdateMode.GC -> EpdMode.GC
        UpdateMode.GU -> EpdMode.GU
        UpdateMode.ANIMATION -> EpdMode.ANIMATION
        else -> EpdMode.DU
    }

/** The app's scheduler. */
object Epd {
    val scheduler = EpdScheduler(OnyxEpdPort)

    fun rect(r: Rect) = intArrayOf(r.left, r.top, r.right, r.bottom)
}
