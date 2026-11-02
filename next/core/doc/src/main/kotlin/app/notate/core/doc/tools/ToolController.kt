package app.notate.core.doc.tools

import app.notate.core.doc.settings.Settings
import app.notate.core.model.ToolKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json

/**
 * Owns the toolbar state and persists it to `Settings.toolStateJson`. [state] is the effective state: while the pen side
 * button is held it shows the eraser, but only the underlying state is saved. Main thread only.
 */
class ToolController(private val settings: Settings) {
    private var base: ToolState = load()
    private var temp: ToolKind? = null
    private val _state = MutableStateFlow(effective())
    val state: StateFlow<ToolState> get() = _state

    /** The saved state, without the temporary eraser (what the toolbar shows, so it does not flicker while the pen button is held). */
    fun baseState(): ToolState = base

    fun select(kind: ToolKind) = update { it.copy(active = kind) }

    fun update(transform: (ToolState) -> ToolState) {
        base = transform(base)
        settings.toolStateJson.set(json.encodeToString(ToolState.serializer(), base))
        _state.value = effective()
    }

    /** Pen side button: down switches to the eraser, up restores the previous tool. Not persisted. */
    fun temporaryEraser(on: Boolean) = temporaryTool(if (on) ToolKind.ERASER else null)

    /** Switches to [kind] until called with null (side button set to Lasso). Not persisted. */
    fun temporaryTool(kind: ToolKind?) {
        temp = kind
        _state.value = effective()
    }

    private fun effective(): ToolState = temp?.let { base.copy(active = it) } ?: base

    private fun load(): ToolState {
        val s = settings.toolStateJson.value ?: return ToolState.defaults()
        return runCatching { json.decodeFromString(ToolState.serializer(), s) }.getOrNull() ?: ToolState.defaults()
    }

    private companion object { val json = Json { ignoreUnknownKeys = true; encodeDefaults = true } }
}
