package app.notate.platform.device

import android.app.Activity
import android.app.Application
import android.os.Build
import android.util.Log
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode
import com.onyx.android.sdk.rx.RxBaseAction
import com.onyx.android.sdk.utils.ResManager
import org.lsposed.hiddenapibypass.HiddenApiBypass

/** One-time Onyx SDK setup, kept here so no other module touches the SDK. */
object OnyxRuntime {
    private const val TAG = "OnyxRuntime"

    /** Call from `Application.onCreate` on Onyx devices (same sequence as the legacy app). Main thread only. */
    fun init(app: Application) {
        runCatching { ResManager.init(app) }.onFailure { Log.w(TAG, "ResManager.init failed", it) }
        runCatching { RxBaseAction.init(app) }.onFailure { Log.w(TAG, "RxBaseAction.init failed", it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                HiddenApiBypass.addHiddenApiExemptions("")
            } catch (t: Throwable) {
                Log.e(TAG, "HiddenApiBypass failed", t)
            }
        }
    }

    /**
     * Puts the panel in the scribble display scheme with DU as the window's default update mode, as the legacy
     * `CanvasActivity.onCreate` does before it inflates its layout. The pen driver relies on it. Main thread only.
     */
    fun enterScribbleScheme(activity: Activity) {
        runCatching {
            EpdController.setViewDefaultUpdateMode(activity.window.decorView, UpdateMode.DU)
            EpdController.setDisplayScheme(EpdController.SCHEME_SCRIBBLE)
        }.onFailure { Log.w(TAG, "enterScribbleScheme failed", it) }
    }
}
