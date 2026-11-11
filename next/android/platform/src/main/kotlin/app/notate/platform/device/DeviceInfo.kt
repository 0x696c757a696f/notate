package app.notate.platform.device

import android.content.Context
import android.os.Build
import android.util.Log
import com.onyx.android.sdk.api.device.epd.EpdController

/** What the app needs to know about the hardware. */
data class DeviceInfo(
    val isOnyx: Boolean,
    val model: String,
    val xdpi: Float,
    val maxPressure: Float,
    /** True if the Onyx raw pen driver can be used (Onyx device and the SDK's `TouchHelper` loads). */
    val hasRawDrawing: Boolean,
)

object DeviceProbe {
    private const val TAG = "DeviceProbe"
    private const val DEFAULT_MAX_PRESSURE = 4096f

    /** True on an Onyx / Boox device. Reads only [Build]: safe before [OnyxRuntime.init]. */
    fun isOnyxBuild(): Boolean = listOf(Build.MANUFACTURER, Build.BRAND, Build.DEVICE, Build.MODEL).any {
        it != null && (it.contains("onyx", ignoreCase = true) || it.contains("boox", ignoreCase = true))
    }

    /**
     * Reads the device once at startup; never throws. Main thread.
     *
     * On Onyx devices call it only after [OnyxRuntime.init]: it touches `EpdController`, and the SDK's device layer
     * looks up the firmware's hidden methods the first time it is used. Used before the hidden-API exemption, those
     * lookups can fail for the life of the process, silently.
     */
    fun probe(ctx: Context): DeviceInfo {
        val isOnyx = isOnyxBuild()
        val hasRaw = isOnyx && runCatching { Class.forName("com.onyx.android.sdk.pen.TouchHelper"); true }.getOrDefault(false)
        val maxPressure = if (isOnyx) {
            runCatching { EpdController.getMaxTouchPressure() }.getOrNull()?.takeIf { it > 0f } ?: DEFAULT_MAX_PRESSURE
        } else {
            DEFAULT_MAX_PRESSURE
        }
        val info = DeviceInfo(isOnyx, "${Build.MANUFACTURER} ${Build.MODEL}", ctx.resources.displayMetrics.xdpi, maxPressure, hasRaw)
        Log.i(TAG, "isOnyx=${info.isOnyx} hasRawDrawing=${info.hasRawDrawing} model=${info.model} xdpi=${info.xdpi} maxPressure=${info.maxPressure}")
        return info
    }
}
