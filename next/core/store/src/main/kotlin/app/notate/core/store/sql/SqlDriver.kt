package app.notate.core.store.sql

import java.io.File

/**
 * Minimal synchronous SQLite access used by the stores. Android and JDBC (tests) provide implementations.
 * Implementations must be safe to call from one thread at a time; the stores serialise access on a single IO dispatcher.
 */
interface SqlDriver : java.io.Closeable {
    /** Runs a statement. Argument types: Long, Int, Double, Float, String, ByteArray, Boolean (as 0/1) or null. */
    fun execute(sql: String, vararg args: Any?)

    /** Runs a query and maps every row; the [SqlRow] is only valid inside [map]. */
    fun <T> query(sql: String, vararg args: Any?, map: (SqlRow) -> T): List<T>

    /** Runs [block] in a transaction; a nested call joins the outer transaction. Any exception rolls the outermost one back. */
    fun <T> transaction(block: () -> T): T

    /** `PRAGMA user_version`. */
    var userVersion: Int
}

/** One result row; column indexes are 0-based. */
interface SqlRow {
    fun long(i: Int): Long
    fun double(i: Int): Double
    fun string(i: Int): String?
    fun blob(i: Int): ByteArray?
    fun isNull(i: Int): Boolean
}

interface SqlDriverFactory {
    fun open(file: File, readOnly: Boolean): SqlDriver
    fun openMemory(): SqlDriver
}

/** PRAGMAs every store applies right after opening a database. */
object SqlPragmas {
    fun apply(driver: SqlDriver, readOnly: Boolean) {
        driver.execute("PRAGMA busy_timeout = 3000")
        driver.execute("PRAGMA foreign_keys = ON")
        if (!readOnly) {
            driver.execute("PRAGMA journal_mode = WAL")
            driver.execute("PRAGMA synchronous = NORMAL")
        }
    }
}
