package app.notate.render

import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.android.asCoroutineDispatcher

/** The dedicated `notate-render` thread (tile rasterization, thumbnails) exposed as a coroutine dispatcher. */
class RenderThread {
    private val thread = HandlerThread("notate-render").also { it.start() }
    val dispatcher: CoroutineDispatcher = Handler(thread.looper).asCoroutineDispatcher("notate-render")

    fun quit() { thread.quitSafely() }
}
