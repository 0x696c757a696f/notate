package app.notate.core.doc.tools

import app.notate.core.doc.settings.Settings
import app.notate.core.model.ToolKind
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json

/**
 * Owns the toolbar state and persists it to `Settings.toolStateJson`. [state] is the effective state: while the pen side
 * button is held it shows the eraser, but only the underlying state is saved. Main thread only.
 */
class ToolController(private val settings: Settings) {
    private var base: ToolState = load()
    private var tempTool: ToolKind? = null
    private val _state = MutableStateFlow(effective())
    val state: StateFlow<ToolState> get() = _state

    /** The saved state, without the temporary eraser (what the toolbar shows, so it does not flicker while the pen button is held). */
    fun baseState(): ToolState = base

    private val _selections = MutableSharedFlow<ToolKind>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Every tool the user picked, including picking the tool that is already active (which leaves [state] unchanged). */
    val selections: SharedFlow<ToolKind> get() = _selections

    fun select(kind: ToolKind) {
        update { it.copy(active = kind) }
        _selections.tryEmit(kind)
    }

    fun update(transform: (ToolState) -> ToolState) {
        base = transform(base)
        settings.toolStateJson.set(json.encodeToString(ToolState.serializer(), base))
        _state.value = effective()
    }

    /** Pen side button: down switches to the eraser, up restores the previous tool. Not persisted. */
    fun temporaryEraser(on: Boolean) = temporaryTool(if (on) ToolKind.ERASER else null)

    /** Pen side button set to another tool (Settings → Pen button): [kind] is active while it is held, null restores. Not persisted. */
    fun temporaryTool(kind: ToolKind?) {
        tempTool = kind
        _state.value = effective()
    }

    private fun effective(): ToolState = tempTool?.let { base.copy(active = it) } ?: base

    private fun load(): ToolState {
        val s = settings.toolStateJson.value ?: return ToolState.defaults()
        val loaded = runCatching { json.decodeFromString(ToolState.serializer(), s) }.getOrNull() ?: return ToolState.defaults()
        // The zoom window is a toggle now, not a tool; a state saved while it was selectable falls back to the pen.
        return if (loaded.active == ToolKind.ZOOM_WINDOW) loaded.copy(active = ToolKind.PEN) else loaded
    }

    private companion object { val json = Json { ignoreUnknownKeys = true; encodeDefaults = true } }
}
