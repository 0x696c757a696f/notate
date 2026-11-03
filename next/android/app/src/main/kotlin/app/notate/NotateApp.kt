package app.notate

import android.app.Application
import android.content.pm.ApplicationInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import app.notate.platform.device.DeviceProbe
import app.notate.platform.device.OnyxRuntime
import app.notate.platform.fs.AppPaths
import app.notate.platform.log.AndroidLogger
import app.notate.platform.log.CrashHandler
import app.notate.platform.thread.ThreadChecks
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** Process entry point. Builds [AppGraph] once; main thread only. */
class NotateApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // First, before anything touches the Onyx SDK (DeviceProbe reads EpdController), as the legacy app does: the SDK's
        // device layer looks up the firmware's hidden methods on first use. Looked up before the hidden-API exemption, they
        // can stay missing for the life of the process; the pen capture region is then not mapped to digitizer units and the
        // raw pen driver opens without error but reports no point (the HUD's `raw=` value shows the mapping).
        if (DeviceProbe.isOnyxBuild()) OnyxRuntime.init(this)
        ThreadChecks.enabled = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val paths = AppPaths(this)
        val logger = AndroidLogger(paths.logDir)
        val device = DeviceProbe.probe(this)
        CrashHandler.install(paths, logger, device)
        logger.i("NotateApp", "Started on ${device.model}; isOnyx=${device.isOnyx} hasRawDrawing=${device.hasRawDrawing}")
        graph = AppGraph(this, paths, logger, device)
        // Everything the app has queued reaches the disk when the last screen goes away.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) graph.appScope.launch { graph.registry.flushAll() }
            },
        )
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            TrashPurgeWorker.UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<TrashPurgeWorker>(1, TimeUnit.DAYS).build(),
        )
    }

    companion object {
        lateinit var graph: AppGraph
            private set
    }
}
