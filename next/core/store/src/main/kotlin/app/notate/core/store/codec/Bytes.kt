package app.notate.core.store.codec

import app.notate.core.model.CorruptDataException

/** Unsigned LEB128 and zigzag helpers. */
object VarInt {
    fun zigzag(v: Long): Long = (v shl 1) xor (v shr 63)
    fun unzigzag(v: Long): Long = (v ushr 1) xor -(v and 1)
}

/** Growable little-endian byte sink. */
class ByteWriter(initial: Int = 64) {
    private var buf = ByteArray(maxOf(initial, 16))
    var size = 0
        private set

    private fun ensure(extra: Int) {
        if (size + extra > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + extra))
    }

    fun u8(v: Int) { ensure(1); buf[size++] = v.toByte() }
    fun u32(v: Int) { for (i in 0 until 4) u8(v ushr (8 * i)) }
    fun f32(v: Float) = u32(v.toRawBits())

    /** Unsigned LEB128. */
    fun varint(value: Long) {
        var v = value
        while ((v and 0x7FL.inv()) != 0L) { u8(((v and 0x7F) or 0x80).toInt()); v = v ushr 7 }
        u8(v.toInt())
    }

    fun zigzag(v: Long) = varint(VarInt.zigzag(v))
    fun bytes(src: ByteArray) { ensure(src.size); System.arraycopy(src, 0, buf, size, src.size); size += src.size }
    fun toByteArray(): ByteArray = buf.copyOf(size)
}

/** Little-endian reader; any read past the end throws [CorruptDataException]. */
class ByteReader(private val buf: ByteArray, private var pos: Int = 0) {
    fun remaining(): Int = buf.size - pos

    fun u8(): Int {
        if (pos >= buf.size) throw CorruptDataException("Unexpected end of data")
        return buf[pos++].toInt() and 0xFF
    }

    fun u32(): Int { var v = 0; for (i in 0 until 4) v = v or (u8() shl (8 * i)); return v }
    fun f32(): Float = Float.fromBits(u32())

    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = u8()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 63) throw CorruptDataException("Varint too long")
        }
    }

    fun zigzag(): Long = VarInt.unzigzag(varint())
}
