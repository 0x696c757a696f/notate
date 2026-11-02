package app.notate.core.workspace

import app.notate.core.model.DocId
import app.notate.core.model.DocKind
import app.notate.core.store.library.DocumentEntry
import app.notate.core.store.library.SqliteLibraryStore
import app.notate.core.testing.FakeClock
import app.notate.core.testing.JdbcSqlDriverFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.Random

class WorkspaceStoreTest {
    private val library = SqliteLibraryStore.open(JdbcSqlDriverFactory().openMemory(), Dispatchers.IO, FakeClock(1_000), Random(1))
    private val store = WorkspaceStore(library)

    private fun entry(id: String, trashed: Long? = null) =
        DocumentEntry(DocId(id), "Title $id", DocKind.NOTEBOOK, null, 1, 1, null, 1, 0, false, trashed, emptySet())

    private fun tab(id: String) = Tab(DocId(id), DocKind.NOTEBOOK, "Title $id")

    @Test fun emptyWhenNothingStored() = runBlocking<Unit> {
        assertThat(store.load()).isEqualTo(Workspace())
    }

    @Test fun roundTripKeepsTabsPanesAndView() = runBlocking<Unit> {
        listOf("a", "b").forEach { library.upsert(entry(it)) }
        val view = PaneViewStateDto(2, 1.25f, 4f, 99f)
        val ws = Workspace(
            listOf(tab("a"), tab("b")), PaneSlot(0, view), PaneSlot(1), focusRight = true,
            split = SplitSpec(Orientation.VERTICAL, 0.4f),
        )
        store.save(ws)
        assertThat(store.load()).isEqualTo(ws)
    }

    @Test fun unknownFieldsAreIgnored() = runBlocking<Unit> {
        library.upsert(entry("a"))
        library.putKv(
            WorkspaceStore.KEY,
            """{"tabs":[{"doc":"a","kind":"NOTEBOOK","title":"T","future":1}],"left":{"tab":0,"extra":true},"later":[1,2]}""",
        )
        val ws = store.load()
        assertThat(ws.tabs).hasSize(1)
        assertThat(ws.left!!.tab).isEqualTo(0)
    }

    @Test fun deletedAndTrashedDocumentsAreDroppedOnLoad() = runBlocking<Unit> {
        library.upsert(entry("a"))
        library.upsert(entry("b", trashed = 5))
        // "c" is not in the library at all.
        store.save(Workspace(listOf(tab("a"), tab("b"), tab("c")), PaneSlot(2)))
        val ws = store.load()
        assertThat(ws.tabs.map { it.doc.value }).containsExactly("a")
        assertThat(ws.left!!.tab).isEqualTo(0)
    }

    @Test fun garbageLoadsAsEmpty() = runBlocking<Unit> {
        library.putKv(WorkspaceStore.KEY, "not json")
        assertThat(store.load()).isEqualTo(Workspace())
    }

    @Test fun savingAnEmptyWorkspaceClearsTheKey() = runBlocking<Unit> {
        library.upsert(entry("a"))
        store.save(Workspace(listOf(tab("a")), PaneSlot(0)))
        store.save(Workspace())
        assertThat(library.getKv(WorkspaceStore.KEY)).isNull()
    }

    @Test fun viewStateConvertsBothWays() {
        val vs = app.notate.core.model.ViewState(app.notate.core.model.PageId(5), 2f, 1f, 2f, app.notate.core.model.PageLayoutMode.SINGLE)
        assertThat(PaneViewStateDto.from(vs).toViewState()).isEqualTo(vs)
    }
}
