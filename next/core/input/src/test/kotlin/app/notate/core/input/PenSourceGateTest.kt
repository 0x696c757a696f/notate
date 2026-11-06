package app.notate.core.input

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PenSourceGateTest {
    private fun down(switched: Boolean = false) = PenEvent.Down(1f, 1f, 0.5f, 0f, 0f, 0L, eraser = false, focusSwitched = switched)
    private val move = PenEvent.Move(2f, 2f, 0.5f, 0f, 0f, 1L)
    private val up = PenEvent.Up(3f, 3f, 2L)
    private val cancel = PenEvent.Cancel(3f, 3f, 2L)

    /** A gate whose driver has delivered a stroke already. */
    private fun trustedGate(): PenSourceGate = PenSourceGate().also {
        it.onRawBegin(0L, touchStroke = false)
        it.onRawEnd()
    }

    @Test fun withoutADriverTouchEventsInk() {
        val g = PenSourceGate()
        assertThat(g.onTouch(down(), false, false, false, 0L)).containsExactly(down())
        assertThat(g.onTouch(move, false, true, false, 1L)).containsExactly(move)
    }

    @Test fun anUntrustedDriverLetsTouchInkAtOnce() {
        val g = PenSourceGate()
        assertThat(g.onTouch(down(), true, false, false, 0L)).containsExactly(down())
        assertThat(g.deadline).isNull()
    }

    @Test fun aDriverBeginDuringATouchStrokeIsIgnoredButEarnsTrust() {
        val g = PenSourceGate()
        g.onTouch(down(), true, false, false, 0L)
        assertThat(g.onRawBegin(5L, touchStroke = true)).isFalse()
        assertThat(g.ignoringDriver).isTrue()
        assertThat(g.onRawEnd()).isFalse()
        assertThat(g.trusted).isTrue()
    }

    @Test fun aTrustedDriverThatBeginsInTimeTakesTheStroke() {
        val g = trustedGate()
        assertThat(g.onTouch(down(), true, false, false, 1000L)).isEmpty()
        assertThat(g.deadline).isEqualTo(1000L + PenSourceGate.HOLD_MS)
        assertThat(g.onTouch(move, true, false, false, 1010L)).isEmpty()
        assertThat(g.onRawBegin(1020L, touchStroke = false)).isTrue()
        assertThat(g.deadline).isNull()
        assertThat(g.onTimeout(1300L)).isEmpty()
        // The rest of the stroke belongs to the driver.
        assertThat(g.onTouch(move, true, false, true, 1030L)).isEmpty()
        assertThat(g.onTouch(up, true, false, true, 1040L)).isEmpty()
        assertThat(g.onRawEnd()).isTrue()
        assertThat(g.fallbacks).isEqualTo(0)
    }

    @Test fun aSilentDriverHandsTheHeldStrokeToTouchAndLosesTrust() {
        val g = trustedGate()
        g.onTouch(down(), true, false, false, 1000L)
        g.onTouch(move, true, false, false, 1050L)
        g.onTouch(up, true, false, false, 1100L)
        assertThat(g.onTimeout(1100L)).isEmpty()      // too early
        assertThat(g.onTimeout(1200L)).containsExactly(down(), move, up).inOrder()
        assertThat(g.trusted).isFalse()
        assertThat(g.fallbacks).isEqualTo(1)
        // The next stroke inks from touch at once.
        assertThat(g.onTouch(down(), true, false, false, 2000L)).containsExactly(down())
    }

    @Test fun aLateDriverBeginForAFallbackStrokeIsIgnored() {
        val g = trustedGate()
        g.onTouch(down(), true, false, false, 1000L)
        g.onTouch(up, true, false, false, 1050L)
        g.onTimeout(1200L)
        assertThat(g.onRawBegin(1300L, touchStroke = false)).isFalse()
        assertThat(g.onRawEnd()).isFalse()
        // A begin long afterwards is a new stroke.
        assertThat(g.onRawBegin(5000L, touchStroke = false)).isTrue()
    }

    @Test fun aDriverBeginBeforeTheTouchDownOwnsThatDown() {
        val g = trustedGate()
        assertThat(g.onRawBegin(1000L, touchStroke = false)).isTrue()
        assertThat(g.onRawEnd()).isTrue()          // a dot: the driver stroke ended before the touch down arrived
        assertThat(g.onTouch(down(), true, false, false, 1010L)).isEmpty()
        assertThat(g.deadline).isNull()
        // An old early begin does not swallow a later stroke.
        g.onRawBegin(2000L, touchStroke = false); g.onRawEnd()
        assertThat(g.onTouch(down(), true, false, false, 2000L + PenSourceGate.HOLD_MS + 1)).isEmpty()
        assertThat(g.deadline).isNotNull()
    }

    @Test fun aTouchDownDuringADriverStrokeIsTheDriversOwn() {
        val g = trustedGate()
        g.onRawBegin(500L, touchStroke = false)
        assertThat(g.onTouch(down(), true, false, true, 1000L)).isEmpty()
        assertThat(g.deadline).isNull()
    }

    @Test fun theFirstStrokeAfterAFocusSwitchInksFromTouch() {
        val g = trustedGate()
        assertThat(g.onTouch(down(switched = true), true, false, false, 0L)).containsExactly(down(switched = true))
    }

    @Test fun aCancelWhileHeldDropsTheStroke() {
        val g = trustedGate()
        g.onTouch(down(), true, false, false, 1000L)
        assertThat(g.onTouch(cancel, true, false, false, 1010L)).isEmpty()
        assertThat(g.deadline).isNull()
        assertThat(g.trusted).isTrue()
    }

    @Test fun theDriverGoingAwayReleasesTheHeldEvents() {
        val g = trustedGate()
        g.onTouch(down(), true, false, false, 1000L)
        assertThat(g.onTouch(move, false, false, false, 1010L)).containsExactly(down(), move).inOrder()
        assertThat(g.trusted).isTrue()
    }

    @Test fun aNewStrokeWhileOneIsHeldFallsBackForTheFirst() {
        val g = trustedGate()
        g.onTouch(down(), true, false, false, 1000L)
        g.onTouch(up, true, false, false, 1030L)
        val d2 = down().copy(x = 9f)
        assertThat(g.onTouch(d2, true, false, false, 1080L)).containsExactly(down(), up, d2).inOrder()
        assertThat(g.fallbacks).isEqualTo(1)
    }
}
