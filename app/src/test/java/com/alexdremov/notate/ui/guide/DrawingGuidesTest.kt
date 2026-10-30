package com.alexdremov.notate.ui.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DrawingGuidesTest {
    private fun ruler() = Ruler().apply { place(1000, 1400, 1f) } // centre (500, 700), width 64

    @Test
    fun `stroke next to a ruler edge follows that edge`() {
        val r = ruler()
        val top = r.snapAt(300f, 700f - 32f - 5f, 20f)!!
        val line = GuidePath.line(top, 300f, 663f, 600f, 690f)
        assertEquals(668f, line[0].y, 0.01f)
        assertEquals(668f, line[1].y, 0.01f)
        assertEquals(300f, line[0].x, 0.01f)
        assertEquals(600f, line[1].x, 0.01f)

        val bottom = r.snapAt(300f, 700f + 40f, 20f)!!
        assertEquals(732f, GuidePath.project(bottom, 10f, 900f).y, 0.01f)

        assertNull(r.snapAt(300f, 700f, 20f)) // middle of the ruler body
        assertNull(r.snapAt(300f, 500f, 20f)) // far from it
    }

    @Test
    fun `rotated ruler draws along its angle and sticks to 45 degrees`() {
        val r = ruler()
        r.setRotation((PI / 4 + 0.02).toFloat())
        assertEquals((PI / 4).toFloat(), r.angle, 1e-5f)
        assertEquals(135, r.degrees) // screen y is down: a 45° clockwise turn reads as 135°
        r.setRotation(0.3f)
        assertEquals(0.3f, r.angle, 1e-6f)

        val edge = r.toScreen(0f, -32f)
        val snap = r.snapAt(edge.x, edge.y, 20f)!!
        val pts = GuidePath.line(snap, edge.x, edge.y, edge.x + 100f, edge.y + 300f)
        val a = atan2(pts[1].y - pts[0].y, pts[1].x - pts[0].x)
        assertEquals(0.3f, a, 1e-4f)
    }

    @Test
    fun `ruler contains its body only`() {
        val r = ruler()
        assertTrue(r.contains(500f, 700f))
        assertTrue(r.contains(100f, 720f))
        assertFalse(r.contains(500f, 800f))
    }

    private fun protractor() = Protractor().apply { place(1000, 1400, 1f) } // radius 300, centre (500, 850)

    @Test
    fun `protractor snaps to centre rays, its arc and its base`() {
        val p = protractor()
        assertEquals(300f, p.radius, 0.01f)
        assertTrue(p.snapAt(505f, 848f, 20f) is GuideSnap.Ray)
        assertTrue(p.snapAt(500f, 850f - 295f, 20f) is GuideSnap.Arc)
        assertTrue(p.snapAt(650f, 852f, 20f) is GuideSnap.Line)
        assertNull(p.snapAt(500f, 700f, 20f))
        assertNull(p.snapAt(500f, 850f + 295f, 20f)) // arc only exists on the upper half
    }

    @Test
    fun `rays from the centre land on whole degrees`() {
        val p = protractor()
        val ray = p.snapAt(500f, 850f, 20f) as GuideSnap.Ray
        // ~30.4 degrees counter-clockwise on screen (y up)
        val a = Math.toRadians(30.4)
        val x = 500f + 200f * kotlin.math.cos(a).toFloat()
        val y = 850f - 200f * kotlin.math.sin(a).toFloat()
        val pts = GuidePath.ray(ray, x, y)
        val deg = Math.toDegrees(atan2((850f - pts[1].y).toDouble(), (pts[1].x - 500f).toDouble()))
        assertEquals(30.0, deg, 1e-3)
        assertEquals(200f, hypot(pts[1].x - 500f, pts[1].y - 850f), 0.01f)
    }

    @Test
    fun `arc follows the pen sweep, past 180 degrees too`() {
        val g = GuideSnap.Arc(0f, 0f, 100f)
        var last = GuidePath.angleOf(g, 100f, 0f)
        var sweep = 0.0
        val start = last
        for (deg in 10..270 step 10) {
            val a = Math.toRadians(deg.toDouble())
            val now = GuidePath.angleOf(g, (100 * kotlin.math.cos(a)).toFloat(), (100 * kotlin.math.sin(a)).toFloat())
            sweep += GuidePath.angleDelta(last, now)
            last = now
        }
        assertEquals(Math.toRadians(270.0), sweep, 1e-6)
        val pts = GuidePath.arc(g, start, sweep)
        assertTrue(pts.all { kotlin.math.abs(hypot(it.x, it.y) - 100f) < 0.01f })
        assertEquals(0f, pts.last().x, 0.01f)
        assertEquals(-100f, pts.last().y, 0.01f)
    }

    @Test
    fun `protractor resizes within limits`() {
        val p = protractor()
        p.setRadius(10f)
        assertEquals(Protractor.MIN_RADIUS_DP, p.radius, 0.01f)
        p.setRadius(5000f)
        assertEquals(1400f, p.radius, 0.01f)
    }
}
