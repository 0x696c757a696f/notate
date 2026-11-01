package app.notate.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Random

class UlidTest {
    private class FixedClock(var t: Long) : Clock { override fun now() = t }
    private val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toSet()

    @Test fun lengthAndAlphabet() {
        val id = DocId.new(FixedClock(1_700_000_000_000L), Random(1)).value
        assertThat(id).hasLength(26)
        assertThat(id.all { it in alphabet }).isTrue()
    }

    @Test fun sameMillisecondIsStrictlyIncreasing() {
        val clock = FixedClock(1_800_000_000_000L)
        val rnd = Random(42)
        val ids = List(50) { DocId.new(clock, rnd).value }
        assertThat(ids).isInOrder()
        assertThat(ids.toSet()).hasSize(50)
    }

    @Test fun laterTimeSortsLater() {
        val rnd = Random(7)
        val a = DocId.new(FixedClock(1_900_000_000_000L), rnd).value
        val b = DocId.new(FixedClock(1_900_000_000_001L), rnd).value
        assertThat(b.substring(0, 10)).isGreaterThan(a.substring(0, 10))
    }

    @Test fun knownEncoding() {
        // time = 0 and zero randomness → all zeros; max value → "7ZZZ…Z"
        assertThat(Ulid.encode(0, 0, 0)).isEqualTo("0".repeat(26))
        assertThat(Ulid.encode(0xFFFF_FFFF_FFFFL, 0xFFFF, -1L)).isEqualTo("7" + "Z".repeat(25))
        assertThat(Ulid.encode(1, 0, 0).substring(0, 10)).isEqualTo("0000000001")
    }
}
