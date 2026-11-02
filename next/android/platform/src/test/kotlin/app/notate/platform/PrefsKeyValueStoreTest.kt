package app.notate.platform

import app.notate.platform.prefs.PrefsKeyValueStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PrefsKeyValueStoreTest {
    private val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun writeReadAndRemove() {
        val kv = PrefsKeyValueStore(ctx, "t1")
        assertThat(kv.getString("a")).isNull()
        kv.putString("a", "1")
        assertThat(kv.getString("a")).isEqualTo("1")
        assertThat(PrefsKeyValueStore(ctx, "t1").getString("a")).isEqualTo("1")
        kv.putString("a", null)
        assertThat(kv.getString("a")).isNull()
    }

    @Test fun changesEmitKeys() = runBlocking<Unit> {
        val kv = PrefsKeyValueStore(ctx, "t2")
        val first = async(start = CoroutineStart.UNDISPATCHED, context = Dispatchers.Unconfined) { kv.changes.first() }
        kv.putString("theme", "dark")
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle() // apply() notifies listeners on the main looper
        assertThat(first.await()).isEqualTo("theme")
    }
}
