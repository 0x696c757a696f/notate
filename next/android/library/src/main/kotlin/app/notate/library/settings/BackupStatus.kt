package app.notate.library.settings

/** State of the backup job shown in Settings → Backup. [lastAt] is the time of the last fully successful run. */
data class BackupStatus(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val lastAt: Long? = null,
    val lastError: String? = null,
)
