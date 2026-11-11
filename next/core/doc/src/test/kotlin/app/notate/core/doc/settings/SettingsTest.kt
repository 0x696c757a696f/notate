package app.notate.core.doc.settings

import app.notate.core.model.PageLayoutMode
import app.notate.core.model.PageSize
import app.notate.core.model.PageTemplate
import app.notate.core.testing.InMemoryKeyValueStore
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SettingsTest {
    @Test fun defaults() {
        val s = Settings(InMemoryKeyValueStore())
        assertThat(s.fingerMode.value).isEqualTo(FingerMode.SCROLL)
        assertThat(s.handedness.value).isEqualTo(Handedness.RIGHT)
        assertThat(s.tap2.value).isEqualTo(Tap2Action.UNDO)
        assertThat(s.tap3.value).isEqualTo(Tap3Action.REDO)
        assertThat(s.penButton.value).isEqualTo(PenButton.ERASER)
        assertThat(s.holdShapeMs.value).isEqualTo(600)
        assertThat(s.driverInk.value).isTrue()
        assertThat(s.webPenSurface.value).isFalse()
        assertThat(s.pageLayout.value).isEqualTo(PageLayoutMode.CONTINUOUS)
        assertThat(s.gcEvery.value).isEqualTo(0)
        assertThat(s.toolbarPos.value).isEqualTo(ToolbarPos.TOP)
        assertThat(s.toolbarSize.value).isEqualTo(ToolbarSize.REGULAR)
        assertThat(s.toolbarShow.values.map { it.value }.toSet()).containsExactly(true)
        assertThat(s.defaultTemplate.value).isEqualTo(PageTemplate.DEFAULT)
        assertThat(s.webNetworkDefault.value).isFalse()
        assertThat(s.webJsDefault.value).isTrue()
        assertThat(s.ocrEnabled.value).isTrue()
        assertThat(s.backupUri.value).isNull()
        assertThat(s.backupFreq.value).isEqualTo(BackupFreq.OFF)
        assertThat(s.debugHud.value).isFalse()
        assertThat(s.inkDiagnostics.value).isFalse()
    }

    @Test fun writeUpdatesFlowAndPersists() {
        val kv = InMemoryKeyValueStore()
        val s = Settings(kv)
        s.handedness.set(Handedness.LEFT)
        s.holdShapeMs.set(900)
        s.backupUri.set("content://x")
        s.defaultTemplate.set(PageTemplate.DEFAULT.copy(size = PageSize.A5))
        assertThat(s.handedness.value).isEqualTo(Handedness.LEFT)
        assertThat(kv.map["handedness"]).isEqualTo("LEFT")
        val s2 = Settings(kv)
        assertThat(s2.handedness.value).isEqualTo(Handedness.LEFT)
        assertThat(s2.holdShapeMs.value).isEqualTo(900)
        assertThat(s2.backupUri.value).isEqualTo("content://x")
        assertThat(s2.defaultTemplate.value.size).isEqualTo(PageSize.A5)
        s2.backupUri.set(null)
        assertThat(kv.map).doesNotContainKey("backup_uri")
    }

    @Test fun unknownValuesFallBackToDefault() {
        val kv = InMemoryKeyValueStore()
        kv.putString("handedness", "AMBIDEXTROUS")
        kv.putString("hold_shape_ms", "123")
        kv.putString("driver_ink", "maybe")
        kv.putString("default_template", "{not json")
        val s = Settings(kv)
        assertThat(s.handedness.value).isEqualTo(Handedness.RIGHT)
        assertThat(s.holdShapeMs.value).isEqualTo(600)
        assertThat(s.driverInk.value).isTrue()
        assertThat(s.defaultTemplate.value).isEqualTo(PageTemplate.DEFAULT)
    }

    @Test fun toolStateStoreRoundTrips() {
        val t = ToolStateStore(InMemoryKeyValueStore())
        assertThat(t.getJson("pen")).isNull()
        t.putJson("pen", "{\"w\":1}")
        assertThat(t.getJson("pen")).isEqualTo("{\"w\":1}")
    }
}

class ToolbarLookTest {
    @Test fun regularMatchesTheOriginal52And64Dp() {
        assertThat(ToolbarSize.REGULAR.buttonDp).isEqualTo(48)
        assertThat(ToolbarSize.REGULAR.barDp).isEqualTo(52)
        assertThat(ToolbarSize.REGULAR.verticalBarDp).isEqualTo(64)
    }

    @Test fun sizesGrowAndTheBarAlwaysFitsTheOptionButtons() {
        val s = ToolbarSize.entries
        assertThat(s.map { it.buttonDp }).isInOrder()
        assertThat(s.all { it.barDp > it.buttonDp && it.barDp >= 52 }).isTrue()
    }

    @Test fun hiddenToolsAndSizePersist() {
        val kv = InMemoryKeyValueStore()
        val a = Settings(kv)
        a.toolbarShow.getValue(ToolbarTool.HAND).set(false)
        a.toolbarSize.set(ToolbarSize.LARGE)
        val b = Settings(kv)
        assertThat(b.toolbarShow.getValue(ToolbarTool.HAND).value).isFalse()
        assertThat(b.toolbarShow.getValue(ToolbarTool.LASSO).value).isTrue()
        assertThat(b.toolbarSize.value).isEqualTo(ToolbarSize.LARGE)
    }

    @Test fun penAndEraserCannotBeHidden() {
        assertThat(ToolbarTool.entries.map { it.id }).containsNoneOf("pen", "eraser")
    }
}
