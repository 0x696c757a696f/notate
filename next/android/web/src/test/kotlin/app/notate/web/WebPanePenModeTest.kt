package app.notate.web

import app.notate.core.input.InputPenMode
import app.notate.core.model.PenMode
import app.notate.core.model.ToolKind
import app.notate.core.model.WebDocSettings
import app.notate.core.model.edit.Edit
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WebPanePenModeTest {
    private fun setMode(h: WebPaneHarness, mode: PenMode) {
        val meta = h.handle.editor.meta.value
        h.handle.editor.apply(Edit.SetMeta(meta, meta.copy(web = (meta.web ?: WebDocSettings()).copy(penMode = mode))), undoable = false)
    }

    @Test fun writeModeKeepsThePenAndLeavesFingersToThePage() = runBlocking {
        val h = WebPaneHarness()
        val pane = h.open()
        assertThat(pane.penMode).isEqualTo(InputPenMode.WRITE)
        assertThat(pane.consumesFingers).isFalse()
        h.close()
    }

    @Test fun interactModeHandsThePenToThePage() = runBlocking {
        val h = WebPaneHarness()
        val pane = h.open()
        setMode(h, PenMode.INTERACT)
        assertThat(pane.penMode).isEqualTo(InputPenMode.INTERACT)
        assertThat(h.arbiter.isBlockedBy(pane, app.notate.core.input.BlockReason.INTERACT_MODE)).isTrue()
        h.close()
    }

    @Test fun pickingAPenToolSwitchesBackToWrite() = runBlocking {
        val h = WebPaneHarness()
        val pane = h.open()
        setMode(h, PenMode.INTERACT)
        h.toolController.select(ToolKind.PEN)
        delay(100)
        assertThat(h.handle.editor.meta.value.web!!.penMode).isEqualTo(PenMode.WRITE)
        assertThat(pane.penMode).isEqualTo(InputPenMode.WRITE)
        h.close()
    }

    @Test fun theModeIsRememberedWithTheDocument() = runBlocking {
        val h = WebPaneHarness()
        h.open(WebDocSettings(penMode = PenMode.INTERACT))
        assertThat(h.pane.penMode).isEqualTo(InputPenMode.INTERACT)
        h.close()
    }
}
