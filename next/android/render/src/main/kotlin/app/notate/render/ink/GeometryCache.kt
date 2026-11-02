package app.notate.render.ink

import android.graphics.Path
import app.notate.core.ink.outline.InkGeometry
import app.notate.core.ink.outline.Outlines
import app.notate.core.ink.shape.ShapeOutline
import app.notate.core.model.Item
import app.notate.core.model.Shape
import app.notate.core.model.ShapeKind
import app.notate.core.model.Stroke

/** One stroked polyline of a [CachedPath]. */
class StrokePart(val path: Path, val width: Float, val dash: FloatArray?)

/** Filled circles in page coordinates. */
class DotsPart(val centers: FloatArray, val radii: FloatArray)

/**
 * Android geometry of one ink item, in page coordinates (pt), independent of zoom.
 * [fill] is filled with the winding rule, then [strokes] are stroked, then [dots] are drawn.
 */
class CachedPath(val fill: Path?, val strokes: List<StrokePart>, val dots: DotsPart?)

/**
 * LRU of [CachedPath] keyed by item **object identity** (items are immutable: a changed item is a new object; hashing a
 * large point array would be too slow). Thread-safe.
 */
class GeometryCache(private val maxEntries: Int = 4000) {
    private class Key(val item: Item) {
        private val hash = System.identityHashCode(item)
        override fun hashCode() = hash
        override fun equals(other: Any?) = other is Key && other.item === item
    }

    private val map = object : LinkedHashMap<Key, CachedPath>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, CachedPath>?) = size > maxEntries
    }

    val size: Int @Synchronized get() = map.size

    /** Returns the cached geometry of [item], building it on a miss. Only [Stroke] and [Shape] are supported. */
    @Synchronized
    fun pathFor(item: Item): CachedPath {
        val key = Key(item)
        map[key]?.let { return it }
        val built = build(item)
        map[key] = built
        return built
    }

    private fun build(item: Item): CachedPath = when (item) {
        is Stroke -> fromGeometry(Outlines.forPen(item.style.pen).outline(item.points, item.style))
        is Shape -> shape(item)
        else -> throw IllegalArgumentException("No ink geometry for ${item::class.java.simpleName}")
    }

    private fun shape(s: Shape): CachedPath {
        val lines = ShapeOutline.of(s.kind, s.params, s.style.widthPt)
        val strokes = lines.map { StrokePart(polylinePath(it.points), it.width, it.dash) }
        val closed = s.kind == ShapeKind.RECT || s.kind == ShapeKind.TRIANGLE || s.kind == ShapeKind.POLYGON || s.kind == ShapeKind.ELLIPSE
        val fill = if (s.fill != null && closed && lines.isNotEmpty()) polylinePath(lines[0].points).also { it.close() } else null
        return CachedPath(fill, strokes, null)
    }

    private fun fromGeometry(g: InkGeometry): CachedPath = when (g) {
        is InkGeometry.Fill -> CachedPath(polygonPath(g.polygon), emptyList(), null)
        is InkGeometry.Polyline -> CachedPath(null, listOf(StrokePart(polylinePath(g.points), g.width, g.dash)), null)
        is InkGeometry.Dots -> CachedPath(null, emptyList(), DotsPart(g.centers, g.radii))
    }

    private fun polylinePath(xy: FloatArray): Path {
        val p = Path()
        if (xy.size >= 2) p.moveTo(xy[0], xy[1])
        var i = 2
        while (i + 1 < xy.size) { p.lineTo(xy[i], xy[i + 1]); i += 2 }
        return p
    }

    private fun polygonPath(xy: FloatArray): Path = polylinePath(xy).also {
        it.close()
        it.fillType = Path.FillType.WINDING
    }
}
