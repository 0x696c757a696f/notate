package app.notate.core.input

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PenRoutingTest {
    private fun down(switched: Boolean) = PenEvent.Down(1f, 1f, 0.5f, 0f, 0f, 0L, eraser = false, focusSwitched = switched)
    private val move = PenEvent.Move(1f, 1f, 0.5f, 0f, 0f, 1L)

    @Test fun withoutADriverTouchEventsInk() {
        assertThat(PenRouting.inkFromTouch(false, false, down(false))).isTrue()
        assertThat(PenRouting.inkFromTouch(false, false, move)).isTrue()
    }

    @Test fun anActiveDriverOwnsTheStroke() {
        assertThat(PenRouting.inkFromTouch(true, false, down(false))).isFalse()
        assertThat(PenRouting.inkFromTouch(true, false, move)).isFalse()
    }

    @Test fun theFirstStrokeAfterAFocusSwitchIsInkedFromTouchAndKeptThatWay() {
        assertThat(PenRouting.inkFromTouch(true, false, down(true))).isTrue()
        assertThat(PenRouting.inkFromTouch(true, true, move)).isTrue()
        assertThat(PenRouting.inkFromTouch(true, true, PenEvent.Up(1f, 1f, 2L))).isTrue()
    }
}
