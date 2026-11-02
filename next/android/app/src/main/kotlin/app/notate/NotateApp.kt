package app.notate

import android.app.Application
import android.content.pm.ApplicationInfo
import app.notate.platform.device.DeviceProbe
import app.notate.platform.device.OnyxRuntime
import app.notate.platform.fs.AppPaths
import app.notate.platform.log.AndroidLogger
import app.notate.platform.log.CrashHandler
import app.notate.platform.thread.ThreadChecks

/** Process entry point. Builds [AppGraph] once; main thread only. */
class NotateApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ThreadChecks.enabled = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val paths = AppPaths(this)
        val logger = AndroidLogger(paths.logDir)
        val device = DeviceProbe.probe(this)
        CrashHandler.install(paths, logger, device)
        if (device.isOnyx) OnyxRuntime.init(this)
        logger.i("NotateApp", "Started on ${device.model}; isOnyx=${device.isOnyx} hasRawDrawing=${device.hasRawDrawing}")
        graph = AppGraph(this, paths, logger, device)
    }

    companion object {
        lateinit var graph: AppGraph
            private set
    }
}
