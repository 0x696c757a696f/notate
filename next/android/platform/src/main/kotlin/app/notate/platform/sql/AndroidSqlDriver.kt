package app.notate.platform.sql

import android.database.Cursor
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteQuery
import app.notate.core.store.sql.SqlDriver
import app.notate.core.store.sql.SqlDriverFactory
import app.notate.core.store.sql.SqlRow
import java.io.File

/**
 * [SqlDriver] over [SQLiteDatabase]. The stores call it from one IO thread at a time; the platform
 * database object is thread-safe anyway. Behaviour matches the JDBC driver used by core tests.
 */
class AndroidSqlDriver(private val db: SQLiteDatabase, private val readOnly: Boolean = false) : SqlDriver {
    private var depth = 0

    init {
        if (!readOnly) db.enableWriteAheadLogging()
    }

    override fun execute(sql: String, vararg args: Any?) {
        if (isPragma(sql)) {
            // Several PRAGMAs return a row, which execSQL rejects.
            query(sql, *args) { }
        } else {
            db.execSQL(sql, Array(args.size) { checkArg(args[it]) })
        }
    }

    override fun <T> query(sql: String, vararg args: Any?, map: (SqlRow) -> T): List<T> {
        val factory = SQLiteDatabase.CursorFactory { _, driver, editTable, q ->
            args.forEachIndexed { i, a -> bind(q, i + 1, a) }
            SQLiteCursor(driver, editTable, q)
        }
        db.rawQueryWithFactory(factory, sql, null, null).use { c ->
            val row = CursorRow(c)
            val out = ArrayList<T>()
            while (c.moveToNext()) out.add(map(row))
            return out
        }
    }

    override fun <T> transaction(block: () -> T): T {
        if (depth > 0) return block()
        db.beginTransactionNonExclusive()
        depth = 1
        try {
            val r = block()
            db.setTransactionSuccessful()
            return r
        } finally {
            depth = 0
            db.endTransaction() // rolls back unless marked successful
        }
    }

    override var userVersion: Int
        get() = db.version
        set(v) { db.version = v }

    override fun close() = db.close()

    private fun isPragma(sql: String) = sql.trimStart().startsWith("PRAGMA", ignoreCase = true)

    private fun checkArg(a: Any?): Any? = when (a) {
        null, is Long, is Double, is String, is ByteArray -> a
        is Int -> a.toLong()
        is Float -> a.toDouble()
        is Boolean -> if (a) 1L else 0L
        else -> throw IllegalArgumentException("Unsupported SQL argument type ${a::class}")
    }

    private fun bind(q: SQLiteQuery, index: Int, a: Any?) {
        when (val v = checkArg(a)) {
            null -> q.bindNull(index)
            is Long -> q.bindLong(index, v)
            is Double -> q.bindDouble(index, v)
            is String -> q.bindString(index, v)
            is ByteArray -> q.bindBlob(index, v)
        }
    }

    private class CursorRow(private val c: Cursor) : SqlRow {
        override fun long(i: Int) = c.getLong(i)
        override fun double(i: Int) = c.getDouble(i)
        override fun string(i: Int): String? = c.getString(i)
        override fun blob(i: Int): ByteArray? = c.getBlob(i)
        override fun isNull(i: Int) = c.isNull(i)
    }
}

class AndroidSqlDriverFactory : SqlDriverFactory {
    override fun open(file: File, readOnly: Boolean): SqlDriver {
        val flags = if (readOnly) SQLiteDatabase.OPEN_READONLY else SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY
        return AndroidSqlDriver(SQLiteDatabase.openDatabase(file.path, null, flags), readOnly)
    }

    override fun openMemory(): SqlDriver = AndroidSqlDriver(SQLiteDatabase.create(null))
}
