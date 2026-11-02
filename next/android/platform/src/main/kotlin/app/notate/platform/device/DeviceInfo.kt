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

    /** Reads the device once at startup; never throws. Any thread. */
    fun probe(ctx: Context): DeviceInfo {
        val isOnyx = listOf(Build.MANUFACTURER, Build.BRAND, Build.DEVICE, Build.MODEL).any {
            it != null && (it.contains("onyx", ignoreCase = true) || it.contains("boox", ignoreCase = true))
        }
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
