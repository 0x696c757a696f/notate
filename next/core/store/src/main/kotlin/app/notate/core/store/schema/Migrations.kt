package app.notate.core.store.schema

import app.notate.core.store.sql.SqlDriver

/** The database was written by a newer app version than this build understands. */
class NewerFormatException(
    val found: Int,
    val supported: Int,
    message: String = "This notebook was created by a newer version of Notate.",
) : RuntimeException(message)

/** One schema step that brings the database to [toVersion]. */
class Migration(val toVersion: Int, val apply: (SqlDriver) -> Unit)

object Migrations {
    /** Upgrades [driver] from its `user_version` to [target] in one transaction. Throws [NewerFormatException] if it is newer. */
    fun apply(driver: SqlDriver, target: Int, steps: List<Migration>) {
        driver.transaction {
            val current = driver.userVersion
            if (current > target) throw NewerFormatException(current, target)
            for (step in steps.sortedBy { it.toVersion }) {
                if (step.toVersion > current && step.toVersion <= target) {
                    step.apply(driver)
                    driver.userVersion = step.toVersion
                }
            }
            driver.userVersion = target
        }
    }

    /** Throws [NewerFormatException] when the database is newer than [supported] (used for read-only opens). */
    fun requireNotNewer(driver: SqlDriver, supported: Int) {
        val v = driver.userVersion
        if (v > supported) throw NewerFormatException(v, supported)
    }
}
