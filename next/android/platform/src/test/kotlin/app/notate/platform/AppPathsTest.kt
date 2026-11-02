package app.notate.platform

import androidx.test.core.app.ApplicationProvider
import app.notate.core.model.DocId
import app.notate.platform.fs.AppPaths
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppPathsTest {
    @Test fun layoutFollowsDataModel() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val p = AppPaths(ctx)
        assertThat(p.root).isEqualTo(java.io.File(ctx.filesDir, "notate"))
        assertThat(p.libraryDb.name).isEqualTo("library.db")
        assertThat(p.libraryDb.parentFile).isEqualTo(p.root)
        assertThat(p.docDir(DocId("01ABC")).path).endsWith("notate/docs/01ABC")
        assertThat(p.crashDir.isDirectory).isTrue()
        assertThat(p.logDir.isDirectory).isTrue()
        assertThat(p.cacheDir.isDirectory).isTrue()
    }
}
