package app.notate

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.notate.library.NotateTheme
import app.notate.library.settings.SettingsHost
import app.notate.library.settings.SettingsScreen
import app.notate.platform.log.ShareLogs

/** Settings (04 §9), opened from the bookshelf sidebar and from the editor's More menu; Back returns to where it came from. */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = NotateApp.graph
        val host = object : SettingsHost {
            override val versionName: String = packageManager.getPackageInfo(packageName, 0).versionName ?: ""
            override val isDebugBuild: Boolean = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
            override fun shareLogs() {
                startActivity(ShareLogs.logsIntent(this@SettingsActivity, graph.paths, graph.logger, getString(R.string.share_logs_subject)))
            }

            override fun importFromOldNotate() {
                startActivity(LegacyImportActivity.intent(this@SettingsActivity))
            }

            override fun diagnosticLines(): List<String> = buildList {
                add("device=${graph.device}")
                add("--- recent log ---")
                addAll(graph.logger.recent(DIAG_LINES))
            }
        }
        setContent { NotateTheme { SettingsScreen(graph.settings, host, onBack = { finish() }) } }
    }

    companion object {
        private const val DIAG_LINES = 150

        fun intent(context: Context): Intent = Intent(context, SettingsActivity::class.java)
    }
}
