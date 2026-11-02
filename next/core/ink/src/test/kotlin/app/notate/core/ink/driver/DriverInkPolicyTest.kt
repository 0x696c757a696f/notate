package app.notate.core.ink.driver

import app.notate.core.model.InkStyle
import app.notate.core.model.PenType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DriverInkPolicyTest {
    private fun ink(pen: PenType, color: Int = 0xFF000000.toInt(), w: Float = 1f, px: Float = 3f) =
        DriverInkPolicy.forPen(InkStyle(pen, color, w), px)

    @Test fun strokeMapping() {
        assertThat(ink(PenType.BALLPOINT).stroke).isEqualTo(DriverStroke.FOUNTAIN)
        assertThat(ink(PenType.FOUNTAIN).stroke).isEqualTo(DriverStroke.FOUNTAIN)
        assertThat(ink(PenType.FINELINER).stroke).isEqualTo(DriverStroke.PENCIL)
        assertThat(ink(PenType.PENCIL).stroke).isEqualTo(DriverStroke.PENCIL)
        assertThat(ink(PenType.HIGHLIGHTER).stroke).isEqualTo(DriverStroke.MARKER)
        assertThat(ink(PenType.DASHED).stroke).isEqualTo(DriverStroke.DASH)
        assertThat(ink(PenType.DOTTED).stroke).isEqualTo(DriverStroke.DASH)
    }

    @Test fun colorsTurnGray() {
        assertThat(ink(PenType.BALLPOINT, 0xFF1A3D8F.toInt()).color).isEqualTo(0xFF000000.toInt())
        assertThat(ink(PenType.BALLPOINT, 0xFFFFE14D.toInt()).color).isEqualTo(0xFF555555.toInt())
        assertThat(ink(PenType.HIGHLIGHTER, 0x80FFE14D.toInt()).color).isEqualTo(0xFFBBBBBB.toInt())
    }

    @Test fun widthScalesAndHasFloor() {
        assertThat(ink(PenType.BALLPOINT, w = 1.2f, px = 3f).widthPx).isWithin(1e-5f).of(3.6f)
        assertThat(ink(PenType.BALLPOINT, w = 0.1f, px = 1f).widthPx).isEqualTo(1f)
    }
}
