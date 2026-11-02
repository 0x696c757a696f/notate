package app.notate

import android.app.Application
import android.content.pm.ApplicationInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import android.content.ComponentCallbacks2
import app.notate.pdf.PdfSupport
import app.notate.platform.device.DeviceProbe
import app.notate.platform.device.OnyxRuntime
import app.notate.platform.fs.AppPaths
import app.notate.platform.log.AndroidLogger
import app.notate.platform.log.CrashHandler
import app.notate.platform.thread.ThreadChecks
import kotlinx.coroutines.launch

/** Process entry point. Builds [AppGraph] once; main thread only. */
class NotateApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ThreadChecks.enabled = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        PdfSupport.init(this)
        val paths = AppPaths(this)
        val logger = AndroidLogger(paths.logDir)
        val device = DeviceProbe.probe(this)
        CrashHandler.install(paths, logger, device)
        if (device.isOnyx) OnyxRuntime.init(this)
        logger.i("NotateApp", "Started on ${device.model}; isOnyx=${device.isOnyx} hasRawDrawing=${device.hasRawDrawing}")
        graph = AppGraph(this, paths, logger, device)
        // Everything the app has queued reaches the disk when the last screen goes away.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) graph.appScope.launch { graph.registry.flushAll() }
            },
        )
    }

    /** Low memory: drop the PDF background tiles (they are re-rendered when scrolled to). */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && ::graph.isInitialized) graph.pdfBackgrounds.trimMemory()
    }

    companion object {
        lateinit var graph: AppGraph
            private set
    }
}
