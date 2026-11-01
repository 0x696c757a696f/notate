package app.notate.core.doc.history

import app.notate.core.model.edit.Edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Undo/redo stacks of [Edit]s. Main thread only. The history never touches the model: [undo] hands back the inverse and the
 * caller applies it with `undoable = false`.
 */
class History(private val limit: Int = 200) {
    private val undoStack = ArrayDeque<Edit>()
    private val redoStack = ArrayDeque<Edit>()
    private var groupDepth = 0
    private val group = ArrayList<Edit>()

    private val _canUndo = MutableStateFlow(false)
    private val _canRedo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> get() = _canUndo
    val canRedo: StateFlow<Boolean> get() = _canRedo

    /** Records [edit] and clears redo. Inside a group it is collected and recorded as one [Edit.Batch] at [endGroup]. */
    fun record(edit: Edit) {
        if (groupDepth > 0) {
            group.add(edit)
            return
        }
        push(edit)
    }

    /** Starts collecting edits into one undo step (nestable). */
    fun beginGroup() { groupDepth++ }

    /** Ends the innermost group; the outermost one records a single edit (or a Batch when there were several). */
    fun endGroup() {
        check(groupDepth > 0) { "endGroup without beginGroup" }
        if (--groupDepth > 0) return
        val edits = group.toList()
        group.clear()
        when (edits.size) {
            0 -> Unit
            1 -> push(edits[0])
            else -> push(Edit.Batch(edits))
        }
    }

    /** Pops the latest edit, moves it to redo and returns its inverse, or null when nothing can be undone. */
    fun undo(): Edit? {
        val e = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(e)
        publish()
        return e.inverse()
    }

    /** Pops the latest undone edit, moves it back to undo and returns it, or null. */
    fun redo(): Edit? {
        val e = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(e)
        publish()
        return e
    }

    fun clear() {
        undoStack.clear(); redoStack.clear(); group.clear(); groupDepth = 0
        publish()
    }

    private fun push(edit: Edit) {
        redoStack.clear()
        undoStack.addLast(edit)
        while (undoStack.size > limit) undoStack.removeFirst()
        publish()
    }

    private fun publish() {
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
    }
}
