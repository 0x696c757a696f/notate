package app.notate.core.model

import kotlinx.serialization.Serializable
import java.util.Random

/** Thrown when persisted data contains a value this build cannot interpret (unknown code, bad encoding). */
class CorruptDataException(message: String) : RuntimeException(message)

/** Time source so tests control "now". */
interface Clock { fun now(): Long }

/** [Clock] backed by `System.currentTimeMillis()`. */
object SystemClock : Clock { override fun now(): Long = System.currentTimeMillis() }

/** Document id: 26-character ULID (time-sortable). */
@Serializable @JvmInline
value class DocId(val value: String) {
    companion object {
        /** Creates a new ULID. Ids created within the same millisecond (process-wide) are strictly increasing. */
        fun new(clock: Clock, random: Random): DocId = DocId(Ulid.generate(clock.now(), random))
    }
}

/** Page id, unique within a document. */
@Serializable @JvmInline value class PageId(val value: Long)

/** Item id, unique within a document. */
@Serializable @JvmInline value class ItemId(val value: Long)

/** First 32 hex chars of the SHA-256 of the asset's bytes. */
@Serializable @JvmInline value class AssetId(val value: String)

/** ULID generator: 48-bit ms timestamp + 80-bit randomness in Crockford base32; monotonic within one millisecond. */
internal object Ulid {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private var lastMs = -1L
    private var lastHi = 0L   // top 16 bits of the randomness
    private var lastLo = 0L   // low 64 bits

    @Synchronized
    fun generate(ms: Long, random: Random): String {
        if (ms == lastMs) {
            lastLo += 1
            if (lastLo == 0L) lastHi = (lastHi + 1) and 0xFFFF
        } else {
            lastMs = ms
            lastHi = random.nextInt(0x10000).toLong()
            lastLo = random.nextLong()
        }
        return encode(ms and 0xFFFF_FFFF_FFFFL, lastHi, lastLo)
    }

    /** Encodes the 128-bit value `(time << 80) | (hi << 64) | lo` as 26 base32 characters. */
    fun encode(time: Long, hi: Long, lo: Long): String {
        val high = (time shl 16) or hi
        val sb = StringBuilder(26)
        for (k in 0 until 26) {
            val shift = 125 - 5 * k
            val v = when {
                shift >= 64 -> (high ushr (shift - 64)) and 31
                shift == 0 -> lo and 31
                else -> ((lo ushr shift) or (high shl (64 - shift))) and 31
            }
            sb.append(ALPHABET[v.toInt()])
        }
        return sb.toString()
    }
}
