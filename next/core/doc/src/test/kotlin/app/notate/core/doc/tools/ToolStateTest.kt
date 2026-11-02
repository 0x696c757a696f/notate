package app.notate.core.doc.tools

import app.notate.core.doc.settings.Settings
import app.notate.core.model.Defaults
import app.notate.core.model.PenType
import app.notate.core.model.ToolKind
import app.notate.core.model.Units
import app.notate.core.testing.InMemoryKeyValueStore
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class ToolStateTest {
    @Test fun defaultsMatchSpec() {
        val s = ToolState.defaults()
        assertThat(s.active).isEqualTo(ToolKind.PEN)
        assertThat(s.pen.pen).isEqualTo(PenType.BALLPOINT)
        assertThat(s.pen.colors).containsExactly(0xFF000000.toInt(), 0xFF1A3D8F.toInt(), 0xFFB3261E.toInt()).inOrder()
        assertThat(s.pen.widthsPt).containsExactly(Units.mm(0.25f), Units.mm(0.4f), Units.mm(0.7f)).inOrder()
        assertThat(s.pen.width).isEqualTo(1)
        assertThat(s.highlighter.colors).containsExactly(0x80FFE14D.toInt(), 0x8077DD77.toInt(), 0x80A0A0A0.toInt()).inOrder()
        assertThat(s.highlighter.widthsPt).containsExactly(Units.mm(3f), Units.mm(5f), Units.mm(8f)).inOrder()
        assertThat(s.eraserSizesDp).isEqualTo(Defaults.ERASER_SIZES_DP)
        val p = s.preset()
        assertThat(p.kind).isEqualTo(ToolKind.PEN)
        assertThat(p.widthPt).isEqualTo(Units.mm(0.4f))
        assertThat(p.color).isEqualTo(0xFF000000.toInt())
    }

    @Test fun jsonRoundTrip() {
        val s = ToolState.defaults().copy(active = ToolKind.HIGHLIGHTER, eraserSize = 2, snapToGrid = true)
        val json = Json { encodeDefaults = true }
        assertThat(json.decodeFromString(ToolState.serializer(), json.encodeToString(ToolState.serializer(), s))).isEqualTo(s)
    }

    @Test fun controllerPersistsAndReloads() {
        val kv = InMemoryKeyValueStore()
        val c = ToolController(Settings(kv))
        c.select(ToolKind.ERASER)
        c.update { it.copy(eraserSize = 2) }
        val again = ToolController(Settings(kv))
        assertThat(again.state.value.active).isEqualTo(ToolKind.ERASER)
        assertThat(again.state.value.eraserSize).isEqualTo(2)
        assertThat(again.state.value.preset().eraserRadiusDp).isEqualTo(Defaults.ERASER_SIZES_DP[2] / 2f)
    }

    @Test fun corruptJsonFallsBackToDefaults() {
        val kv = InMemoryKeyValueStore().also { it.putString("tool_state", "{not json") }
        assertThat(ToolController(Settings(kv)).state.value).isEqualTo(ToolState.defaults())
    }

    @Test fun temporaryEraserIsNotPersisted() {
        val kv = InMemoryKeyValueStore()
        val c = ToolController(Settings(kv))
        c.select(ToolKind.HIGHLIGHTER)
        val saved = kv.map["tool_state"]
        c.temporaryEraser(true)
        assertThat(c.state.value.active).isEqualTo(ToolKind.ERASER)
        assertThat(kv.map["tool_state"]).isEqualTo(saved)
        assertThat(ToolController(Settings(kv)).state.value.active).isEqualTo(ToolKind.HIGHLIGHTER)
        c.temporaryEraser(false)
        assertThat(c.state.value.active).isEqualTo(ToolKind.HIGHLIGHTER)
    }

    @Test fun temporaryLassoIsNotPersistedAndKeepsBaseTool() {
        val kv = InMemoryKeyValueStore()
        val c = ToolController(Settings(kv))
        c.select(ToolKind.PEN)
        c.temporaryTool(ToolKind.LASSO)
        assertThat(c.state.value.active).isEqualTo(ToolKind.LASSO)
        assertThat(c.baseState().active).isEqualTo(ToolKind.PEN)
        c.temporaryTool(null)
        assertThat(c.state.value.active).isEqualTo(ToolKind.PEN)
    }

    @Test fun pressureDefaultsAndOldJsonStillLoads() {
        assertThat(ToolState.defaults().pen.pressure).isEqualTo(0.7f)
        assertThat(ToolState.defaults().preset().pressure).isEqualTo(0.7f)
        val old = """{"pen":{"pen":"FOUNTAIN","colors":[-16777216],"widthsPt":[1.0]},""" +
            """"highlighter":{"pen":"HIGHLIGHTER","colors":[-1],"widthsPt":[8.0]},"eraserSizesDp":[10.0]}"""
        val kv = InMemoryKeyValueStore().also { it.putString("tool_state", old) }
        val s = ToolController(Settings(kv)).state.value
        assertThat(s.pen.pen).isEqualTo(PenType.FOUNTAIN)
        assertThat(s.pen.pressure).isEqualTo(0.7f)
    }
}
