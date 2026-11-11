package app.notate.core.ink.guide

import app.notate.core.model.Units
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GuideBodyTest {
    @Test fun twistStopsAtNotchesAndLeavesThem() {
        val b = GuideBody(GuideKind.RULER, 0f, 0f)
        b.rotateAbout(0f, 0f, 29f)
        assertThat(b.angleDeg).isEqualTo(30f)
        b.rotateAbout(0f, 0f, 2f)           // raw 31 → still within 3° of 30
        assertThat(b.angleDeg).isEqualTo(30f)
        b.rotateAbout(0f, 0f, 5f)           // raw 36 → free
        assertThat(b.angleDeg).isWithin(1e-3f).of(36f)
    }

    @Test fun twistOrbitsTheFocus() {
        val b = GuideBody(GuideKind.RULER, 100f, 0f)
        b.rotateAbout(0f, 0f, 90f)
        assertThat(b.cx).isWithin(1e-3f).of(0f)
        assertThat(b.cy).isWithin(1e-3f).of(100f)
    }

    @Test fun rulerHitTestUsesItsRotatedRectangle() {
        val b = GuideBody(GuideKind.RULER, 0f, 0f)
        assertThat(b.contains(Units.mm(70f), 0f, 0f)).isTrue()
        assertThat(b.contains(Units.mm(80f), 0f, 0f)).isFalse()
        assertThat(b.contains(0f, Units.mm(12f), Units.mm(3f))).isTrue()
        b.rotateAbout(0f, 0f, 90f)
        assertThat(b.contains(0f, Units.mm(70f), 0f)).isTrue()
        assertThat(b.contains(Units.mm(70f), 0f, 0f)).isFalse()
    }

    @Test fun protractorHandleRotatesWithTheFinger() {
        val b = GuideBody(GuideKind.PROTRACTOR, 0f, 0f)
        val h = b.handle()
        assertThat(h.x).isWithin(1e-3f).of(0f)
        assertThat(h.y).isWithin(1e-3f).of(-b.protractor.radiusPt)
        assertThat(b.nearHandle(h.x + 2f, h.y, 10f)).isTrue()
        b.beginHandleDrag(h.x, h.y)
        b.dragHandle(b.protractor.radiusPt, 0f)           // finger moves to the right: 90° clockwise
        assertThat(b.angleDeg).isWithin(1e-2f).of(90f)
        assertThat(b.handle().x).isWithin(0.1f).of(b.protractor.radiusPt)
    }

    @Test fun protractorResizeIsClamped() {
        val b = GuideBody(GuideKind.PROTRACTOR, 0f, 0f)
        b.resizeBy(100f)
        assertThat(b.protractor.radiusPt).isWithin(1e-3f).of(Units.mm(120f))
        b.resizeBy(0.0001f)
        assertThat(b.protractor.radiusPt).isWithin(1e-3f).of(Units.mm(30f))
    }

    @Test fun protractorContainsOnlyTheHalfDisc() {
        val b = GuideBody(GuideKind.PROTRACTOR, 0f, 0f)
        assertThat(b.contains(0f, -Units.mm(30f), 0f)).isTrue()
        assertThat(b.contains(0f, Units.mm(30f), 0f)).isFalse()
    }
}
