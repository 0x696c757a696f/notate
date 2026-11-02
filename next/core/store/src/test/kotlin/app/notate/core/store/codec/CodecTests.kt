package app.notate.core.store.codec

import app.notate.core.geometry.Rect
import app.notate.core.model.AssetId
import app.notate.core.model.CorruptDataException
import app.notate.core.model.DocId
import app.notate.core.model.ImageItem
import app.notate.core.model.InkStyle
import app.notate.core.model.Item
import app.notate.core.model.ItemBounds
import app.notate.core.model.ItemId
import app.notate.core.model.LinkItem
import app.notate.core.model.LinkTarget
import app.notate.core.model.PageId
import app.notate.core.model.PenType
import app.notate.core.model.Shape
import app.notate.core.model.ShapeKind
import app.notate.core.model.Stroke
import app.notate.core.model.StrokePoints
import app.notate.core.model.TextAlign
import app.notate.core.model.TextBox
import app.notate.core.testing.stroke
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Random
import kotlin.math.abs

class VarIntTest {
    @Test fun roundTripUnsigned() {
        val values = longArrayOf(0, 1, 127, 128, 300, 16383, 16384, Int.MAX_VALUE.toLong(), Long.MAX_VALUE, -1L)
        val w = ByteWriter()
        values.forEach { w.varint(it) }
        val r = ByteReader(w.toByteArray())
        values.forEach { assertThat(r.varint()).isEqualTo(it) }
        assertThat(r.remaining()).isEqualTo(0)
    }

    @Test fun smallValuesTakeOneByte() {
        val w = ByteWriter(); w.varint(127); assertThat(w.size).isEqualTo(1)
        val w2 = ByteWriter(); w2.varint(128); assertThat(w2.size).isEqualTo(2)
    }

    @Test fun zigzagRoundTrip() {
        val w = ByteWriter()
        val values = longArrayOf(0, -1, 1, -64, 63, -65, 64, Long.MIN_VALUE, Long.MAX_VALUE)
        values.forEach { w.zigzag(it) }
        val r = ByteReader(w.toByteArray())
        values.forEach { assertThat(r.zigzag()).isEqualTo(it) }
        assertThat(VarInt.zigzag(-1)).isEqualTo(1L)
        assertThat(VarInt.zigzag(1)).isEqualTo(2L)
    }

    @Test fun fixedWidthLittleEndian() {
        val w = ByteWriter(); w.u32(0x01020304); w.f32(1.5f)
        val b = w.toByteArray()
        assertThat(b.take(4)).containsExactly(4.toByte(), 3.toByte(), 2.toByte(), 1.toByte()).inOrder()
        val r = ByteReader(b)
        assertThat(r.u32()).isEqualTo(0x01020304)
        assertThat(r.f32()).isEqualTo(1.5f)
    }

    @Test fun readerThrowsPastEnd() {
        assertThrows(CorruptDataException::class.java) { ByteReader(ByteArray(0)).u8() }
        assertThrows(CorruptDataException::class.java) { ByteReader(byteArrayOf(1, 2)).u32() }
        assertThrows(CorruptDataException::class.java) { ByteReader(byteArrayOf(0x80.toByte())).varint() }
        assertThrows(CorruptDataException::class.java) { ByteReader(ByteArray(11) { 0x80.toByte() }).varint() }
    }
}

class StrokeCodecTest {
    private fun roundTrip(s: Stroke): Stroke = StrokeCodec.decode(s.id, s.order, StrokeCodec.encode(s), s.bounds)

    private fun assertClose(a: FloatArray, b: FloatArray) {
        assertThat(b.size).isEqualTo(a.size)
        for (i in a.indices) assertThat(abs(a[i] - b[i])).isAtMost(1f / 128f + 1e-4f)
    }

    private fun make(xy: FloatArray, pressure: FloatArray? = null, t: IntArray? = null): Stroke {
        val style = InkStyle(PenType.FOUNTAIN, 0xFF112233.toInt(), 1.5f)
        val p = StrokePoints(xy, pressure, t)
        return Stroke(ItemId(7), 9, style, p, ItemBounds.strokeBounds(p, style))
    }

    @Test fun roundTripWithinHalfStep() {
        val rnd = Random(1)
        val xy = FloatArray(400) { rnd.nextFloat() * 600f }
        val s = make(xy)
        val d = roundTrip(s)
        assertClose(xy, d.points.xy)
        assertThat(d.style).isEqualTo(s.style)
        assertThat(d.id).isEqualTo(s.id)
        assertThat(d.order).isEqualTo(9L)
        assertThat(d.points.pressure).isNull()
        assertThat(d.points.tMs).isNull()
    }

    @Test fun emptyAndSinglePoint() {
        assertThat(roundTrip(make(FloatArray(0))).points.size).isEqualTo(0)
        val one = roundTrip(make(floatArrayOf(12.5f, -3.25f)))
        assertThat(one.points.xy).usingTolerance(1e-4).containsExactly(12.5f, -3.25f)
    }

    @Test fun negativeCoordinates() {
        val xy = floatArrayOf(-500.3f, -0.01f, -10f, 20f, 5f, -700.77f)
        assertClose(xy, roundTrip(make(xy)).points.xy)
    }

    @Test fun pressureAndTime() {
        val xy = floatArrayOf(0f, 0f, 1f, 1f, 2f, 3f)
        val d = roundTrip(make(xy, floatArrayOf(0f, 0.5f, 1f), intArrayOf(0, 8, 20)))
        assertThat(d.points.pressure!![0]).isEqualTo(0f)
        assertThat(d.points.pressure!![1]).isWithin(1f / 255f).of(0.5f)
        assertThat(d.points.pressure!![2]).isEqualTo(1f)
        assertThat(d.points.tMs!!.toList()).containsExactly(0, 8, 20).inOrder()
    }

    @Test fun hundredThousandPoints() {
        val rnd = Random(2)
        val n = 100_000
        val xy = FloatArray(n * 2)
        var x = 0f; var y = 0f
        for (i in 0 until n) { x += rnd.nextFloat() - 0.5f; y += rnd.nextFloat() - 0.5f; xy[2 * i] = x; xy[2 * i + 1] = y }
        val s = make(xy, FloatArray(n) { rnd.nextFloat() }, IntArray(n) { it * 4 })
        val bytes = StrokeCodec.encode(s)
        val d = StrokeCodec.decode(s.id, s.order, bytes, s.bounds)
        assertClose(xy, d.points.xy)
        assertThat(d.points.size).isEqualTo(n)
        println("StrokeCodec 100k points: ${bytes.size} bytes (${bytes.size / n.toFloat()} B/pt)")
    }

    @Test fun truncatedDataThrows() {
        val bytes = StrokeCodec.encode(make(FloatArray(60) { it * 1.7f }, FloatArray(30) { 0.4f }, IntArray(30) { it * 5 }))
        for (cut in listOf(0, 1, 5, 9, 10, 11, 20, bytes.size / 2, bytes.size - 1)) {
            assertThrows("cut=$cut", CorruptDataException::class.java) {
                StrokeCodec.decode(ItemId(1), 1, bytes.copyOf(cut), Rect.EMPTY)
            }
        }
    }

    @Test fun unknownVersionAndPenThrow() {
        val bytes = StrokeCodec.encode(stroke(1))
        val badVersion = bytes.copyOf().also { it[0] = 9 }
        assertThrows(CorruptDataException::class.java) { StrokeCodec.decode(ItemId(1), 1, badVersion, Rect.EMPTY) }
        val badPen = bytes.copyOf().also { it[1] = 99 }
        assertThrows(CorruptDataException::class.java) { StrokeCodec.decode(ItemId(1), 1, badPen, Rect.EMPTY) }
    }

    @Test fun absurdPointCountThrows() {
        val w = ByteWriter()
        w.u8(1); w.u8(0); w.u32(0); w.f32(1f); w.u8(0); w.varint(1_000_000_000L)
        assertThrows(CorruptDataException::class.java) { StrokeCodec.decode(ItemId(1), 1, w.toByteArray(), Rect.EMPTY) }
    }
}

class ItemCodecTest {
    private val style = InkStyle(PenType.PENCIL, 0xFFAA0000.toInt(), 2f)
    private val r = Rect(10f, 20f, 110f, 220f)

    private fun rt(item: Item): Item =
        ItemCodec.decode(ItemCodec.kind(item), item.id, item.layer.code, item.order, item.bounds, ItemCodec.encode(item))

    @Test fun shapeRoundTrip() {
        val s = Shape(ItemId(1), 5, style, ShapeKind.ELLIPSE, floatArrayOf(50f, 60f, 10f, 20f, 15f), 0x80FF0000.toInt(), r)
        assertThat(rt(s)).isEqualTo(s)
        val noFill = Shape(ItemId(2), 6, style, ShapeKind.LINE, floatArrayOf(0f, 0f, 1f, 1f), null, r)
        assertThat(rt(noFill)).isEqualTo(noFill)
    }

    @Test fun textRoundTrip() {
        val t = TextBox(ItemId(3), 7, r, 12.5f, "Hello 积分 ∫x dx", 14f, 0xFF000000.toInt(), true, TextAlign.CENTER, r)
        assertThat(rt(t)).isEqualTo(t)
    }

    @Test fun imageRoundTrip() {
        val i = ImageItem(ItemId(4), 8, AssetId("0123456789abcdef0123456789abcdef"), r, 90f, 0.5f, true, r)
        assertThat(rt(i)).isEqualTo(i)
        assertThat(ItemCodec.imageAsset(ItemCodec.encode(i))).isEqualTo(i.asset)
    }

    @Test fun linkRoundTrip() {
        val a = LinkItem(ItemId(5), 9, r, "Next", LinkTarget.Page(DocId("01TESTDOC000000000000000A"), PageId(42)), r)
        assertThat(rt(a)).isEqualTo(a)
        val b = LinkItem(ItemId(6), 10, r, "Web", LinkTarget.Url("https://example.com"), r)
        assertThat(rt(b)).isEqualTo(b)
    }

    @Test fun strokeRoundTripAndKinds() {
        val s = stroke(1)
        val d = rt(s) as Stroke
        assertThat(d.style).isEqualTo(s.style)
        assertThat(ItemCodec.kind(s)).isEqualTo(0)
        assertThat(ItemCodec.kind(Shape(ItemId(1), 1, style, ShapeKind.LINE, FloatArray(4), null, r))).isEqualTo(1)
    }

    @Test fun firstByteIsVersion() {
        val i = ImageItem(ItemId(4), 8, AssetId("x"), r, 0f, 1f, false, r)
        assertThat(ItemCodec.encode(i)[0]).isEqualTo(1.toByte())
    }

    @Test fun unknownCodecVersionThrows() {
        val i = ImageItem(ItemId(4), 8, AssetId("x"), r, 0f, 1f, false, r)
        val bytes = ItemCodec.encode(i).also { it[0] = 2 }
        assertThrows(CorruptDataException::class.java) { ItemCodec.decode(3, ItemId(4), 0, 8, r, bytes) }
        assertThrows(CorruptDataException::class.java) { ItemCodec.decode(3, ItemId(4), 0, 8, r, ByteArray(0)) }
    }

    @Test fun garbageThrowsCorrupt() {
        assertThrows(CorruptDataException::class.java) {
            ItemCodec.decode(2, ItemId(4), 3, 8, r, byteArrayOf(1, 0x7F, 0x7F, 0x7F))
        }
        assertThrows(CorruptDataException::class.java) { ItemCodec.decode(77, ItemId(4), 0, 8, r, byteArrayOf(1)) }
    }
}
