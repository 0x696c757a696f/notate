package app.notate.core.testing

import app.notate.core.store.sql.SqlDriver
import app.notate.core.store.sql.SqlDriverFactory
import app.notate.core.store.sql.SqlRow
import org.sqlite.SQLiteConfig
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet

/** [SqlDriver] over a sqlite-jdbc connection, for JVM tests. */
class JdbcSqlDriver(private val conn: Connection) : SqlDriver {
    private var depth = 0

    @Synchronized
    override fun execute(sql: String, vararg args: Any?) {
        conn.prepareStatement(sql).use { st -> bind(st, args); st.execute() }
    }

    @Synchronized
    override fun <T> query(sql: String, vararg args: Any?, map: (SqlRow) -> T): List<T> =
        conn.prepareStatement(sql).use { st ->
            bind(st, args)
            st.executeQuery().use { rs ->
                val row = Row(rs)
                val out = ArrayList<T>()
                while (rs.next()) out.add(map(row))
                out
            }
        }

    @Synchronized
    override fun <T> transaction(block: () -> T): T {
        if (depth > 0) return block()
        conn.autoCommit = false
        depth = 1
        try {
            val r = block()
            conn.commit()
            return r
        } catch (t: Throwable) {
            conn.rollback()
            throw t
        } finally {
            depth = 0
            conn.autoCommit = true
        }
    }

    override var userVersion: Int
        @Synchronized get() = query("PRAGMA user_version") { it.long(0).toInt() }.single()
        @Synchronized set(v) = execute("PRAGMA user_version = $v")

    @Synchronized
    override fun close() = conn.close()

    private fun bind(st: PreparedStatement, args: Array<out Any?>) {
        args.forEachIndexed { i, a ->
            val k = i + 1
            when (a) {
                null -> st.setNull(k, java.sql.Types.NULL)
                is Long -> st.setLong(k, a)
                is Int -> st.setLong(k, a.toLong())
                is Double -> st.setDouble(k, a)
                is Float -> st.setDouble(k, a.toDouble())
                is Boolean -> st.setLong(k, if (a) 1 else 0)
                is String -> st.setString(k, a)
                is ByteArray -> st.setBytes(k, a)
                else -> throw IllegalArgumentException("Unsupported SQL argument type ${a::class}")
            }
        }
    }

    private class Row(private val rs: ResultSet) : SqlRow {
        override fun long(i: Int) = rs.getLong(i + 1)
        override fun double(i: Int) = rs.getDouble(i + 1)
        override fun string(i: Int): String? = rs.getString(i + 1)
        override fun blob(i: Int): ByteArray? = rs.getBytes(i + 1)
        override fun isNull(i: Int) = rs.getObject(i + 1) == null
    }
}

/** Opens files with `jdbc:sqlite:<path>`; memory databases use `jdbc:sqlite::memory:`. */
class JdbcSqlDriverFactory : SqlDriverFactory {
    override fun open(file: File, readOnly: Boolean): SqlDriver {
        val cfg = SQLiteConfig().apply { setReadOnly(readOnly) }
        return JdbcSqlDriver(DriverManager.getConnection("jdbc:sqlite:${file.path}", cfg.toProperties()))
    }

    override fun openMemory(): SqlDriver = JdbcSqlDriver(DriverManager.getConnection("jdbc:sqlite::memory:"))
}
