package com.alexdremov.notate.doc.note

import com.alexdremov.notate.data.CanvasSession
import com.alexdremov.notate.doc.Capability
import com.alexdremov.notate.doc.DocKind
import com.alexdremov.notate.doc.DocRef
import com.alexdremov.notate.doc.DocumentSession
import com.alexdremov.notate.doc.SaveState
import com.alexdremov.notate.doc.UndoController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A handwritten note as a [DocumentSession]. The canvas session, region storage and file format are
 * unchanged: [save] is the existing repository save, [undoOp]/[redoOp] the model's history.
 */
class NoteSession(
    path: String,
    title: String,
    val canvas: CanvasSession,
    private val save: suspend () -> Unit,
    private val undoOp: suspend () -> Boolean,
    private val redoOp: suspend () -> Boolean,
    private val canUndoNow: () -> Boolean,
    private val canRedoNow: () -> Boolean,
    private val closeOp: suspend () -> Unit = {},
) : DocumentSession {
    override val ref = DocRef(DocKind.NOTE, path, title)
    override val capabilities: Set<Capability> = Capability.NOTE

    private val changeCounter = MutableStateFlow(0)
    private val _saveState = MutableStateFlow<SaveState>(SaveState.Clean)
    override val saveState: StateFlow<SaveState> = _saveState.asStateFlow()
    private val saveMutex = Mutex()

    @Volatile private var dirtyGeneration = 0L

    @Volatile private var savedGeneration = 0L

    override val undo =
        object : UndoController {
            override val canUndo get() = canUndoNow()
            override val canRedo get() = canRedoNow()
            override val changes: StateFlow<Int> = changeCounter.asStateFlow()

            override suspend fun undo(): Boolean = undoOp().also { if (it) onEdited() }

            override suspend fun redo(): Boolean = redoOp().also { if (it) onEdited() }
        }

    /** Call after the user changed the note (stroke added, erased, moved...). */
    fun onEdited() {
        dirtyGeneration++
        changeCounter.value++
        if (_saveState.value !is SaveState.Saving) _saveState.value = SaveState.Dirty
    }

    override suspend fun flush() {
        saveMutex.withLock {
            val target = dirtyGeneration
            if (target == savedGeneration && _saveState.value !is SaveState.Error) return
            _saveState.value = SaveState.Saving
            try {
                save()
                savedGeneration = target
                _saveState.value = if (dirtyGeneration > target) SaveState.Dirty else SaveState.Clean
            } catch (e: Exception) {
                _saveState.value = SaveState.Error(e.message ?: e.javaClass.simpleName)
                throw e
            }
        }
    }

    override suspend fun close() {
        flush()
        closeOp()
    }
}
