package app.notate.core.model

import app.notate.core.geometry.Affine
import app.notate.core.geometry.Rect
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ItemTransformTest {
    private val style = InkStyle(PenType.BALLPOINT, 0xFF000000.toInt(), 2f)
    private fun stroke(xy: FloatArray): Stroke {
        val p = StrokePoints(xy, floatArrayOf(0.5f, 0.6f), intArrayOf(0, 10))
        return Stroke(ItemId(1), 1, style, p, ItemBounds.strokeBounds(p, style))
    }

    @Test fun strokeBoundsGrowByHalfWidthPlusAa() {
        val s = stroke(floatArrayOf(0f, 0f, 10f, 20f))
        assertThat(s.bounds).isEqualTo(Rect(-1.5f, -1.5f, 11.5f, 21.5f))
    }

    @Test fun translateMovesPointsAndBounds() {
        val t = stroke(floatArrayOf(0f, 0f, 10f, 20f)).transformed(Affine.translate(5f, -5f)) as Stroke
        assertThat(t.points.xy.toList()).containsExactly(5f, -5f, 15f, 15f).inOrder()
        assertThat(t.bounds).isEqualTo(Rect(3.5f, -6.5f, 16.5f, 16.5f))
        assertThat(t.style.widthPt).isEqualTo(2f)
        assertThat(t.points.pressure).isEqualTo(floatArrayOf(0.5f, 0.6f))
    }

    @Test fun scaleScalesStrokeWidth() {
        val t = stroke(floatArrayOf(0f, 0f, 10f, 20f)).transformed(Affine.scale(2f, 2f)) as Stroke
        assertThat(t.style.widthPt).isWithin(1e-5f).of(4f)
        assertThat(t.bounds.r).isWithin(1e-4f).of(20f + 2.5f)
    }

    @Test fun rotateStrokeBounds() {
        val t = stroke(floatArrayOf(0f, 0f, 10f, 0f)).transformed(Affine.rotate(90f)) as Stroke
        assertThat(t.points.xy[2]).isWithin(1e-4f).of(0f)
        assertThat(t.points.xy[3]).isWithin(1e-4f).of(10f)
        assertThat(t.bounds.height).isWithin(1e-3f).of(10f + 3f)
    }

    @Test fun shapeLineAndEllipse() {
        val line = Shape(ItemId(2), 2, style, ShapeKind.LINE, floatArrayOf(0f, 0f, 10f, 0f), null,
            ItemBounds.shapeBounds(ShapeKind.LINE, floatArrayOf(0f, 0f, 10f, 0f), style))
        val l = line.transformed(Affine.scale(3f, 3f)) as Shape
        assertThat(l.params.toList()).containsExactly(0f, 0f, 30f, 0f).inOrder()
        assertThat(l.style.widthPt).isWithin(1e-5f).of(6f)

        val ep = floatArrayOf(50f, 50f, 20f, 10f, 0f)
        val e = Shape(ItemId(3), 3, style, ShapeKind.ELLIPSE, ep, null, ItemBounds.shapeBounds(ShapeKind.ELLIPSE, ep, style))
        assertThat(e.bounds).isEqualTo(Rect(28.5f, 38.5f, 71.5f, 61.5f))
        val r = e.transformed(Affine.rotate(90f, 50f, 50f)) as Shape
        assertThat(r.params[4]).isWithin(1e-3f).of(90f)
        assertThat(r.bounds.width).isWithin(1e-2f).of(23f)    // ry*2 + width/2*2 + aa*2 = 20 + 2 + 1
        assertThat(r.bounds.height).isWithin(1e-2f).of(43f)
    }

    @Test fun textBoxFrameCentreSizeAndRotation() {
        val frame = Rect(0f, 0f, 100f, 20f)
        val tb = TextBox(ItemId(4), 4, frame, 0f, "hi", 12f, 0, false, TextAlign.LEFT, frame)
        val moved = tb.transformed(Affine.translate(10f, 10f)) as TextBox
        assertThat(moved.frame).isEqualTo(Rect(10f, 10f, 110f, 30f))
        val scaled = tb.transformed(Affine.scale(2f, 2f, 50f, 10f)) as TextBox
        assertThat(scaled.frame).isEqualTo(Rect(-50f, -10f, 150f, 30f))
        assertThat(scaled.fontSizePt).isWithin(1e-5f).of(24f)
        val rot = tb.transformed(Affine.rotate(90f, 50f, 10f)) as TextBox
        assertThat(rot.rotationDeg).isWithin(1e-3f).of(90f)
        assertThat(rot.frame.center.x).isWithin(1e-3f).of(50f)
        assertThat(rot.bounds.width).isWithin(1e-3f).of(20f)
        assertThat(rot.bounds.height).isWithin(1e-3f).of(100f)
        val twice = rot.transformed(Affine.rotate(90f, 50f, 10f)) as TextBox
        assertThat(twice.rotationDeg).isWithin(1e-3f).of(180f)
    }

    @Test fun imageAndLinkTransform() {
        val frame = Rect(0f, 0f, 40f, 40f)
        val img = ImageItem(ItemId(5), 5, AssetId("x"), frame, 10f, 1f, false, ItemBounds.frameBounds(frame, 10f))
        val m = img.transformed(Affine.translate(100f, 0f)) as ImageItem
        assertThat(m.frame).isEqualTo(Rect(100f, 0f, 140f, 40f))
        assertThat(m.rotationDeg).isWithin(1e-4f).of(10f)
        val link = LinkItem(ItemId(6), 6, frame, "l", LinkTarget.Url("u"), frame)
        assertThat((link.transformed(Affine.scale(2f, 2f)) as LinkItem).bounds).isEqualTo(Rect(0f, 0f, 80f, 80f))
    }

    @Test fun shapeEqualityUsesParamContent() {
        val a = Shape(ItemId(1), 1, style, ShapeKind.LINE, floatArrayOf(1f, 2f, 3f, 4f), null, Rect.EMPTY)
        val b = Shape(ItemId(1), 1, style, ShapeKind.LINE, floatArrayOf(1f, 2f, 3f, 4f), null, Rect.EMPTY)
        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
        assertThat(StrokePoints(floatArrayOf(1f, 2f), null, null)).isEqualTo(StrokePoints(floatArrayOf(1f, 2f), null, null))
    }

    @Test fun layers() {
        assertThat(stroke(floatArrayOf(0f, 0f)).layer).isEqualTo(Layer.INK)
        val hp = StrokePoints(floatArrayOf(0f, 0f), null, null)
        val hs = InkStyle(PenType.HIGHLIGHTER, 0, 5f)
        assertThat(Stroke(ItemId(1), 1, hs, hp, Rect.EMPTY).layer).isEqualTo(Layer.HIGHLIGHT)
    }
}
