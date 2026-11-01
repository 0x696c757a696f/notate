package app.notate.core.geometry

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.hypot

class GeoTest {
    @Test fun distPointSegment() {
        assertThat(Geo.distPointSegment(5f, 3f, 0f, 0f, 10f, 0f)).isWithin(1e-5f).of(3f)      // interior projection
        assertThat(Geo.distPointSegment(-3f, 4f, 0f, 0f, 10f, 0f)).isWithin(1e-5f).of(5f)     // before start
        assertThat(Geo.distPointSegment(13f, 4f, 0f, 0f, 10f, 0f)).isWithin(1e-5f).of(5f)     // after end
        assertThat(Geo.distPointSegment(3f, 4f, 0f, 0f, 0f, 0f)).isWithin(1e-5f).of(5f)       // degenerate
    }

    @Test fun distPointPolyline() {
        val line = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f)
        assertThat(Geo.distPointPolyline(12f, 5f, line)).isWithin(1e-5f).of(2f)
        assertThat(Geo.distPointPolyline(1f, 1f, floatArrayOf(4f, 5f))).isWithin(1e-5f).of(5f)
        assertThat(Geo.distPointPolyline(0f, 0f, FloatArray(0))).isPositiveInfinity()
    }

    @Test fun segmentsIntersect() {
        assertThat(Geo.segmentsIntersect(0f, 0f, 10f, 10f, 0f, 10f, 10f, 0f)).isTrue()
        assertThat(Geo.segmentsIntersect(0f, 0f, 1f, 1f, 2f, 2f, 3f, 3f)).isFalse()        // collinear apart
        assertThat(Geo.segmentsIntersect(0f, 0f, 2f, 2f, 1f, 1f, 3f, 3f)).isTrue()         // collinear overlap
        assertThat(Geo.segmentsIntersect(0f, 0f, 5f, 0f, 5f, 0f, 5f, 5f)).isTrue()         // touching end
        assertThat(Geo.segmentsIntersect(0f, 0f, 5f, 0f, 0f, 1f, 5f, 1f)).isFalse()        // parallel
    }

    // Concave "U": outer 0..10, notch cut out between x 3..7 from the top down to y 6.
    private val concave = floatArrayOf(0f, 0f, 3f, 0f, 3f, 6f, 7f, 6f, 7f, 0f, 10f, 0f, 10f, 10f, 0f, 10f)

    @Test fun pointInPolygonConcave() {
        assertThat(Geo.pointInPolygon(1f, 5f, concave)).isTrue()
        assertThat(Geo.pointInPolygon(5f, 8f, concave)).isTrue()
        assertThat(Geo.pointInPolygon(5f, 3f, concave)).isFalse()   // in the notch
        assertThat(Geo.pointInPolygon(11f, 5f, concave)).isFalse()
        assertThat(Geo.pointInPolygon(1f, 1f, floatArrayOf(0f, 0f, 1f, 1f))).isFalse()
    }

    @Test fun fractionInside() {
        val sq = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f)
        assertThat(Geo.fractionInside(floatArrayOf(1f, 1f, 2f, 2f, 20f, 20f, 30f, 30f), sq)).isWithin(1e-6f).of(0.5f)
        assertThat(Geo.fractionInside(FloatArray(0), sq)).isEqualTo(0f)
    }

    @Test fun rdpRemovesCollinearKeepsCorner() {
        val straight = floatArrayOf(0f, 0f, 1f, 0.001f, 2f, 0f, 3f, 0f)
        assertThat(Geo.rdp(straight, 0.05f).toList()).containsExactly(0, 3).inOrder()
        val corner = floatArrayOf(0f, 0f, 5f, 0f, 10f, 0f, 10f, 5f, 10f, 10f)
        assertThat(Geo.rdp(corner, 0.05f).toList()).containsExactly(0, 2, 4).inOrder()
        assertThat(Geo.rdp(floatArrayOf(0f, 0f, 1f, 1f), 1f).toList()).containsExactly(0, 1).inOrder()
    }

    @Test fun rdpHonoursKeepFlags() {
        val straight = floatArrayOf(0f, 0f, 1f, 0f, 2f, 0f, 3f, 0f)
        val keep = booleanArrayOf(false, false, true, false)
        assertThat(Geo.rdp(straight, 0.05f, keep).toList()).containsExactly(0, 2, 3).inOrder()
    }

    @Test fun resampleSpacingWithinOnePercent() {
        val poly = floatArrayOf(0f, 0f, 30f, 0f, 30f, 40f, 100f, 40f)
        val out = Geo.resample(poly, 2.5f)
        // interior points (all but the closing one) are spaced along the path; check straight-run spacing
        var maxErr = 0f
        for (i in 0 until out.size / 2 - 2) {
            val d = hypot(out[2 * i + 2] - out[2 * i], out[2 * i + 3] - out[2 * i + 1])
            if (d > 2.4f) maxErr = maxOf(maxErr, kotlin.math.abs(d - 2.5f) / 2.5f)
        }
        assertThat(maxErr).isLessThan(0.01f)
        assertThat(out[out.size - 2]).isEqualTo(100f)
        assertThat(out[out.size - 1]).isEqualTo(40f)
        // arc-length based count: 140 pt / 2.5 = 56 intervals
        assertThat(out.size / 2).isAtLeast(57)
    }

    @Test fun polylineLength() {
        assertThat(Geo.polylineLength(floatArrayOf(0f, 0f, 3f, 4f, 3f, 10f))).isWithin(1e-5f).of(11f)
        assertThat(Geo.polylineLength(floatArrayOf(1f, 1f))).isEqualTo(0f)
    }
}
