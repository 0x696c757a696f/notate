package app.notate.platform

import androidx.test.core.app.ApplicationProvider
import app.notate.platform.device.DeviceInfo
import app.notate.platform.fs.AppPaths
import app.notate.platform.log.AndroidLogger
import app.notate.platform.log.CrashHandler
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LogAndCrashTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val paths = AppPaths(ctx)
    private val device = DeviceInfo(isOnyx = false, model = "Test", xdpi = 160f, maxPressure = 4096f, hasRawDrawing = false)
    private var saved: Thread.UncaughtExceptionHandler? = null

    @Before fun saveHandler() { saved = Thread.getDefaultUncaughtExceptionHandler() }
    @After fun restoreHandler() { Thread.setDefaultUncaughtExceptionHandler(saved) }

    @Test fun ringBufferKeepsLastLinesAndWritesDailyFile() {
        val log = AndroidLogger(paths.logDir, now = { 1_700_000_000_000L })
        repeat(2100) { log.i("T", "line $it") }
        val recent = log.recent()
        assertThat(recent).hasSize(2000)
        assertThat(recent.last()).endsWith("line 2099")
        assertThat(recent.first()).endsWith("line 100")
        val export = log.exportLogs()
        assertThat(export.readText()).contains("line 2099")
        assertThat(paths.logDir.listFiles()!!.map { it.name }.filter { it.matches(Regex("""\d{4}-\d{2}-\d{2}\.txt""")) }).isNotEmpty()
    }

    @Test fun crashWritesStackAndRecentLogThenCallsPrevious() {
        val log = AndroidLogger(paths.logDir)
        log.e("Boot", "before the crash")
        var previousCalled = false
        CrashHandler.install(paths, log, device, Thread.UncaughtExceptionHandler { _, _ -> previousCalled = true })
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), IllegalStateException("kaboom"))
        val reports = CrashHandler.pendingCrashReports(paths)
        assertThat(reports).hasSize(1)
        val text = reports.single().readText()
        assertThat(text).contains("IllegalStateException: kaboom")
        assertThat(text).contains("LogAndCrashTest")
        assertThat(text).contains("before the crash")
        assertThat(text).contains("isOnyx=false")
        assertThat(previousCalled).isTrue()
    }
}
