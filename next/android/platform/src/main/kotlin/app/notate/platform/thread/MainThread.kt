package app.notate.platform.thread

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.android.asCoroutineDispatcher
import java.util.concurrent.Executors

/** Switch for [checkMainThread]; the app sets it to `BuildConfig.DEBUG` at startup, so release builds skip the check. */
object ThreadChecks {
    @Volatile
    var enabled: Boolean = true
}

/** Debug builds assert the caller is on the main thread; release builds do nothing. */
fun checkMainThread() {
    if (ThreadChecks.enabled) check(Looper.myLooper() == Looper.getMainLooper()) { "Must be called on the main thread" }
}

/** The app's named threads. */
class DispatcherSet(
    val main: CoroutineDispatcher,
    val render: CoroutineDispatcher,
    val pdf: CoroutineDispatcher,
    val io: CoroutineDispatcher,
) {
    companion object {
        /** Creates the production set; starts the render thread and the PDF thread. */
        fun create(): DispatcherSet {
            val renderThread = HandlerThread("notate-render").also { it.start() }
            val pdf = Executors.newSingleThreadExecutor { r -> Thread(r, "notate-pdf") }.asCoroutineDispatcher()
            return DispatcherSet(
                main = Dispatchers.Main.immediate,
                render = Handler(renderThread.looper).asCoroutineDispatcher("notate-render"),
                pdf = pdf,
                io = Dispatchers.IO,
            )
        }
    }
}
