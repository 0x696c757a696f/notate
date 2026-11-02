package app.notate.core.store.codec

import app.notate.core.geometry.Rect
import app.notate.core.model.CorruptDataException
import app.notate.core.model.InkStyle
import app.notate.core.model.ItemId
import app.notate.core.model.PenType
import app.notate.core.model.Stroke
import app.notate.core.model.StrokePoints
import kotlin.math.roundToInt

/** Compact binary stroke format (03-data-model §6). Coordinates are quantised to 1/64 pt. */
object StrokeCodec {
    const val VERSION = 1
    private const val SCALE = 64f
    private const val HAS_PRESSURE = 1
    private const val HAS_TIME = 2

    fun encode(stroke: Stroke): ByteArray {
        val p = stroke.points
        val n = p.size
        val pressure = p.pressure
        val times = p.tMs
        require(pressure == null || pressure.size == n) { "pressure size mismatch" }
        require(times == null || times.size == n) { "time size mismatch" }
        val w = ByteWriter(24 + n * 6)
        w.u8(VERSION)
        w.u8(stroke.style.pen.code)
        w.u32(stroke.style.color)
        w.f32(stroke.style.widthPt)
        w.u8((if (pressure != null) HAS_PRESSURE else 0) or (if (times != null) HAS_TIME else 0))
        w.varint(n.toLong())
        var px = 0L
        var py = 0L
        for (i in 0 until n) {
            val x = (p.xy[2 * i] * SCALE).roundToInt().toLong()
            val y = (p.xy[2 * i + 1] * SCALE).roundToInt().toLong()
            w.zigzag(x - px); w.zigzag(y - py)
            px = x; py = y
        }
        pressure?.forEach { w.u8((it.coerceIn(0f, 1f) * 255f).roundToInt()) }
        times?.let { t ->
            for (i in 0 until n) w.zigzag(if (i == 0) 0L else (t[i] - t[i - 1]).toLong())
        }
        return w.toByteArray()
    }

    /** Decodes a stroke; [bounds] come from the item row. Throws [CorruptDataException] on bad data. */
    fun decode(id: ItemId, order: Long, bytes: ByteArray, bounds: Rect): Stroke {
        val r = ByteReader(bytes)
        val version = r.u8()
        if (version != VERSION) throw CorruptDataException("Unsupported stroke codec version $version")
        val pen = PenType.fromCode(r.u8())
        val color = r.u32()
        val width = r.f32()
        val flags = r.u8()
        val nLong = r.varint()
        if (nLong < 0 || nLong > r.remaining() / 2) throw CorruptDataException("Bad stroke point count $nLong")
        val n = nLong.toInt()
        val xy = FloatArray(n * 2)
        var px = 0L
        var py = 0L
        for (i in 0 until n) {
            px += r.zigzag(); py += r.zigzag()
            xy[2 * i] = px / SCALE
            xy[2 * i + 1] = py / SCALE
        }
        val pressure = if (flags and HAS_PRESSURE != 0) FloatArray(n) { r.u8() / 255f } else null
        val tMs = if (flags and HAS_TIME != 0) {
            val t = IntArray(n)
            var acc = 0L
            for (i in 0 until n) { acc += r.zigzag(); t[i] = acc.toInt() }
            t
        } else null
        return Stroke(id, order, InkStyle(pen, color, width), StrokePoints(xy, pressure, tMs), bounds)
    }
}
