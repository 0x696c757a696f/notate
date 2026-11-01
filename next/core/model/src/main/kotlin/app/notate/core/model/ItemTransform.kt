package app.notate.core.model

import app.notate.core.geometry.Affine
import app.notate.core.geometry.Rect

// Implementations of Item.transformed. Widths scale by a.scaleX; frames keep their centre mapping, scale by scaleX/scaleY
// and accumulate the rotation of the transform.

internal fun transformStroke(s: Stroke, a: Affine): Stroke {
    val style = s.style.copy(widthPt = s.style.widthPt * a.scaleX)
    val pts = StrokePoints(a.mapPoints(s.points.xy), s.points.pressure, s.points.tMs)
    return s.copy(style = style, points = pts, bounds = ItemBounds.strokeBounds(pts, style))
}

internal fun transformShape(s: Shape, a: Affine): Shape {
    val style = s.style.copy(widthPt = s.style.widthPt * a.scaleX)
    val p = s.params
    val np = when (s.kind) {
        ShapeKind.ELLIPSE -> {
            val c = a.map(p[0], p[1])
            floatArrayOf(c.x, c.y, p[2] * a.scaleX, p[3] * a.scaleY, p[4] + a.rotationDeg)
        }
        ShapeKind.AXES_2D, ShapeKind.AXES_Q1 -> {
            val o = a.map(p[0], p[1])
            floatArrayOf(o.x, o.y, p[2] * a.scaleX, p[3] * a.scaleY, p[4] + a.rotationDeg, p[5] * a.scaleX)
        }
        else -> a.mapPoints(p)
    }
    return s.copy(style = style, params = np, bounds = ItemBounds.shapeBounds(s.kind, np, style))
}

/** Maps [frame] through [a]: new centre, size scaled per axis. */
private fun transformFrame(frame: Rect, a: Affine): Rect {
    val c = a.map(frame.center.x, frame.center.y)
    val hw = frame.width * a.scaleX / 2f
    val hh = frame.height * a.scaleY / 2f
    return Rect(c.x - hw, c.y - hh, c.x + hw, c.y + hh)
}

internal fun transformTextBox(t: TextBox, a: Affine): TextBox {
    val frame = transformFrame(t.frame, a)
    val rot = t.rotationDeg + a.rotationDeg
    return t.copy(frame = frame, rotationDeg = rot, fontSizePt = t.fontSizePt * a.scaleX, bounds = ItemBounds.frameBounds(frame, rot))
}

internal fun transformImage(i: ImageItem, a: Affine): ImageItem {
    val frame = transformFrame(i.frame, a)
    val rot = i.rotationDeg + a.rotationDeg
    return i.copy(frame = frame, rotationDeg = rot, bounds = ItemBounds.frameBounds(frame, rot))
}

internal fun transformLink(l: LinkItem, a: Affine): LinkItem {
    val frame = transformFrame(l.frame, a)
    return l.copy(frame = frame, bounds = ItemBounds.frameBounds(frame, 0f))
}
