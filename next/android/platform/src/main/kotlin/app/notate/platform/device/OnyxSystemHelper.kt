package app.notate.platform.device

import android.util.Log
import android.view.View

/** Hidden Onyx framework methods reached by reflection; harmless on other devices. */
object OnyxSystemHelper {
    private const val TAG = "OnyxSystemHelper"

    /** Stops the system from handling the stylus side button (selection popup) so the app gets the raw events. */
    fun ignoreSystemSideButton(view: View) {
        try {
            View::class.java.getMethod("setEnablePenSideButton", Boolean::class.javaPrimitiveType).invoke(view, false)
        } catch (e: NoSuchMethodException) {
            Log.d(TAG, "setEnablePenSideButton not available (not an Onyx framework)")
        } catch (e: Exception) {
            Log.e(TAG, "setEnablePenSideButton failed", e)
        }
    }
}
