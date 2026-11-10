package app.notate.core.doc.settings

import app.notate.core.model.PageLayoutMode
import app.notate.core.model.PageTemplate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json

/** String key-value persistence (SharedPreferences on Android). */
interface KeyValueStore {
    fun getString(k: String): String?
    fun putString(k: String, v: String?)
    val changes: Flow<String>
}

enum class FingerMode { SCROLL, OFF }
enum class Handedness { RIGHT, LEFT }
enum class Tap2Action { UNDO, REDO, OFF }
enum class Tap3Action { REDO, FOCUS_MODE, REFRESH, OFF }
enum class PenButton { ERASER, LASSO, NONE }
enum class ToolbarPos { TOP, LEFT }
enum class BackupFreq { OFF, DAILY, WEEKLY }

/** One setting: a [StateFlow] whose [set] persists the new value. */
class Pref<T> private constructor(
    private val state: MutableStateFlow<T>,
    private val write: (T) -> Unit,
) : StateFlow<T> by state {
    internal constructor(kv: KeyValueStore, key: String, default: T, parse: (String) -> T?, format: (T) -> String?) :
        this(MutableStateFlow(kv.getString(key)?.let(parse) ?: default), { kv.putString(key, format(it)) })

    fun set(value: T) {
        state.value = value
        write(value)
    }
}

/** App settings; keys and defaults follow 04-ux-spec §9. Unknown or invalid stored values fall back to the default. */
class Settings(private val kv: KeyValueStore) {
    private inline fun <reified E : Enum<E>> enumPref(key: String, default: E) =
        Pref(kv, key, default, { s -> enumValues<E>().firstOrNull { it.name == s } }, { it.name })

    private fun boolPref(key: String, default: Boolean) =
        Pref(kv, key, default, { it.toBooleanStrictOrNull() }, { it.toString() })

    private fun intPref(key: String, default: Int, allowed: Set<Int>) =
        Pref(kv, key, default, { it.toIntOrNull()?.takeIf { v -> v in allowed } }, { it.toString() })

    val fingerMode = enumPref("finger_mode", FingerMode.SCROLL)
    val handedness = enumPref("handedness", Handedness.RIGHT)
    val tap2 = enumPref("tap2", Tap2Action.UNDO)
    val tap3 = enumPref("tap3", Tap3Action.REDO)
    val penButton = enumPref("pen_button", PenButton.ERASER)
    /** 0 = off. */
    val holdShapeMs = intPref("hold_shape_ms", 600, setOf(0, 400, 600, 900))
    val driverInk = boolPref("driver_ink", true)
    /**
     * Web pages run the pen driver on a transparent SurfaceView, like notebooks; false = on the ink overlay view. Off by
     * default until a device test shows it helps: with it on, web panes scrolled slowly and stopped updating (2026-11-10).
     */
    val webPenSurface = boolPref("web_pen_surface", false)
    /**
     * Freeze web pages (timers and animations) in Write mode once they have gone quiet. Off by default: it helps pen
     * latency on animated pages, but frozen pages looked hung on the device (2026-11-10); stability first.
     */
    val webFreeze = boolPref("web_freeze", false)
    val pageLayout = enumPref("page_layout", PageLayoutMode.CONTINUOUS)
    /** Full refresh every N page turns; 0 = off. */
    val gcEvery = intPref("gc_every", 0, setOf(0, 5, 10, 20))
    val toolbarPos = enumPref("toolbar_pos", ToolbarPos.TOP)
    val defaultTemplate = Pref(
        kv, "default_template", PageTemplate.DEFAULT,
        { runCatching { json.decodeFromString(PageTemplate.serializer(), it) }.getOrNull() },
        { json.encodeToString(PageTemplate.serializer(), it) },
    )
    val webNetworkDefault = boolPref("web_network_default", false)
    val webJsDefault = boolPref("web_js_default", true)
    val ocrEnabled = boolPref("ocr_enabled", true)
    val backupUri = Pref<String?>(kv, "backup_uri", null, { it }, { it })
    val backupFreq = enumPref("backup_freq", BackupFreq.OFF)
    val debugHud = boolPref("debug_hud", false)
    val inkDiagnostics = boolPref("ink_diagnostics", false)
    /** Toolbar state as JSON (see `ToolState`); null = defaults. */
    val toolStateJson = Pref<String?>(kv, "tool_state", null, { it }, { it })

    private companion object { val json = Json { ignoreUnknownKeys = true } }
}

/** Tool preset storage; the JSON structure is defined by the tool UI (R1-3), this only moves strings. */
class ToolStateStore(private val kv: KeyValueStore) {
    fun getJson(key: String): String? = kv.getString(PREFIX + key)
    fun putJson(key: String, json: String?) = kv.putString(PREFIX + key, json)

    private companion object { const val PREFIX = "tools." }
}
