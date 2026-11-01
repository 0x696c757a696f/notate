package app.notate

import android.app.Application

/** Process entry point. Builds [AppGraph] once; main thread only. */
class NotateApp : Application() {
    override fun onCreate() {
        super.onCreate()
        graph = AppGraph()
    }

    companion object {
        lateinit var graph: AppGraph
            private set
    }
}
