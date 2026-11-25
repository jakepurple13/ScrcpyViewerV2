package com.programmersbox.scrcpyviewer.database

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException

/**
 * The computer-side copy of one app database. Every change runs on `work.db` at once and is logged in run order;
 * [discard] puts `pristine.db` (the database as pulled) back. [prepareForWrite] gives the file to copy to the
 * device and [markWritten] makes it the new pristine copy. Not thread-safe: call it from one thread at a time.
 */
class LocalDatabase private constructor(private val workDir: Path) : AutoCloseable {
    private val work: Path = workDir.resolve(WORK)
    private val pristine: Path = workDir.resolve(PRISTINE)
    private var connection: Connection = connect(work)
    private val staged = mutableListOf<String>()

    /** The staged SQL in run order, with its values written in. */
    val log: List<String> get() = staged.toList()

    fun tables(): List<TableInfo> = query("SELECT name, type, sql FROM sqlite_master WHERE type IN ('table', 'view') ORDER BY name") { rs ->
        val name = rs.getString(1)
        val view = rs.getString(2) == "view"
        TableInfo(
            name = name,
            rows = count(name, null),
            system = name.startsWith("sqlite_") || name in SYSTEM_TABLES,
            view = view,
            withoutRowid = !view && WITHOUT_ROWID.containsMatchIn(rs.getString(3).orEmpty()),
        )
    }

    fun columns(table: String): List<ColumnInfo> = query("PRAGMA table_info(${sqlIdentifier(table)})") { rs ->
        ColumnInfo(rs.getString("name"), rs.getString("type").orEmpty(), rs.getInt("pk"), rs.getInt("notnull") != 0)
    }

    /** Up to [limit] rows of [table] from [offset], filtered by a WHERE clause body; a bad [where] throws [SQLException]. */
    fun page(table: String, where: String? = null, limit: Int = PAGE_SIZE, offset: Long = 0): Page {
        val info = tables().firstOrNull { it.name == table } ?: throw SQLException("no such table: $table")
        val columns = columns(table)
        val keyColumns = columns.filter { it.pk > 0 }.sortedBy { it.pk }
        val byRowid = !info.view && !info.withoutRowid
        val select = (if (byRowid) "SELECT rowid AS $ROWID_LABEL, * " else "SELECT * ") +
            "FROM ${sqlIdentifier(table)}${filter(where)} LIMIT $limit OFFSET $offset"
        val rows = query(select) { rs ->
            val shift = if (byRowid) 1 else 0
            val values = columns.indices.map { value(rs, it + 1 + shift) }
            val key = when {
                info.view -> null
                byRowid -> RowKey.Rowid(rs.getLong(1))
                else -> RowKey.Pk(keyColumns.associate { c -> c.name to values[columns.indexOf(c)] })
            }
            Row(key, values)
        }
        return Page(columns, rows, count(table, where), editable = !info.view)
    }

    fun setCell(table: String, key: RowKey, column: String, value: SqlValue) {
        val current = query("SELECT typeof(${sqlIdentifier(column)}) FROM ${sqlIdentifier(table)} WHERE ${keyClause(key)}") { it.getString(1) }
        require(value !is SqlValue.Blob && current.firstOrNull() != "blob") { "BLOB cells can't be edited" }
        run("UPDATE ${sqlIdentifier(table)} SET ${sqlIdentifier(column)} = ${sqlLiteral(value)} WHERE ${keyClause(key)};")
    }

    /** Adds a row of default values and returns its key. */
    fun insertRow(table: String): RowKey {
        run("INSERT INTO ${sqlIdentifier(table)} DEFAULT VALUES;")
        return RowKey.Rowid(query("SELECT last_insert_rowid()") { it.getLong(1) }.single())
    }

    fun deleteRow(table: String, key: RowKey) = run("DELETE FROM ${sqlIdentifier(table)} WHERE ${keyClause(key)};")

    /** Runs any statement. Queries return up to [MAX_QUERY_ROWS] rows; everything else is staged in [log]. */
    fun execute(sql: String): SqlOutcome {
        val text = sql.trim().removeSuffix(";").trim()
        val start = System.nanoTime()
        return try {
            connection.createStatement().use { statement ->
                if (statement.execute(text)) {
                    statement.resultSet.use { rs ->
                        val meta = rs.metaData
                        val names = (1..meta.columnCount).map { meta.getColumnLabel(it) }
                        val rows = buildList { while (size < MAX_QUERY_ROWS && rs.next()) add(names.indices.map { value(rs, it + 1) }) }
                        SqlOutcome.Rows(names, rows, millisSince(start))
                    }
                } else {
                    staged += "$text;"
                    SqlOutcome.Changed(statement.updateCount.coerceAtLeast(0), millisSince(start))
                }
            }
        } catch (e: SQLException) {
            SqlOutcome.Failed(e.message ?: e.toString())
        }
    }

    fun ddl(table: String): String =
        query("SELECT sql FROM sqlite_master WHERE name = ${sqlLiteral(SqlValue.Text(table))}") { it.getString(1).orEmpty() }.firstOrNull().orEmpty()

    fun indexes(table: String): List<String> =
        query("SELECT sql FROM sqlite_master WHERE type = 'index' AND sql IS NOT NULL AND tbl_name = ${sqlLiteral(SqlValue.Text(table))}") { it.getString(1) }

    fun journalMode(): String = query("PRAGMA journal_mode") { it.getString(1) }.single()

    /** Drops every staged change: the pulled copy comes back. */
    fun discard() {
        connection.close()
        copyOver(pristine, work)
        staged.clear()
        connection = connect(work)
    }

    /** Folds the WAL into `work.db` and closes it; returns the file to copy to the device. Follow with [markWritten] or [reopen]. */
    fun prepareForWrite(): Path {
        // A BEGIN typed in the Query view would otherwise be rolled back by the close below, losing staged changes.
        try {
            connection.createStatement().use { it.execute("COMMIT") }
        } catch (_: SQLException) {
            // No transaction was open.
        }
        query("PRAGMA wal_checkpoint(TRUNCATE)") { }
        connection.close()
        return work
    }

    /** After a successful write: the written copy becomes the pristine one and the log clears. */
    fun markWritten() {
        copyOver(work, pristine)
        staged.clear()
        connection = connect(work)
    }

    /** After a failed write: opens `work.db` again with the log kept. */
    fun reopen() {
        if (connection.isClosed) connection = connect(work)
    }

    override fun close() = connection.close()

    private fun run(statement: String) {
        connection.createStatement().use { it.executeUpdate(statement) }
        staged += statement
    }

    private fun count(table: String, where: String?): Long =
        query("SELECT COUNT(*) FROM ${sqlIdentifier(table)}${filter(where)}") { it.getLong(1) }.single()

    private fun filter(where: String?) = if (where.isNullOrBlank()) "" else " WHERE $where"

    private fun keyClause(key: RowKey): String = when (key) {
        is RowKey.Rowid -> "rowid = ${key.id}"
        is RowKey.Pk -> key.values.entries.joinToString(" AND ") { (c, v) ->
            if (v == SqlValue.Null) "${sqlIdentifier(c)} IS NULL" else "${sqlIdentifier(c)} = ${sqlLiteral(v)}"
        }
    }

    private fun <T> query(sql: String, read: (ResultSet) -> T): List<T> =
        connection.createStatement().use { s -> s.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(read(rs)) } } }

    companion object {
        const val PAGE_SIZE = 500
        const val MAX_QUERY_ROWS = 1_000
        private const val WORK = "work.db"
        private const val PRISTINE = "pristine.db"
        private const val ROWID_LABEL = "\"__scrcpyvwr_rowid\""
        private val SYSTEM_TABLES = setOf("android_metadata", "room_master_table")
        private val WITHOUT_ROWID = Regex("""WITHOUT\s+ROWID""", RegexOption.IGNORE_CASE)

        /**
         * Folds [pulled]'s WAL (if pulled with it) into the file, then copies it to `work.db` and `pristine.db` in
         * [workDir] and opens the work copy.
         */
        fun open(pulled: Path, workDir: Path): LocalDatabase {
            connect(pulled).use { c -> c.createStatement().use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") } }
            Files.createDirectories(workDir)
            copyOver(pulled, workDir.resolve(WORK))
            copyOver(pulled, workDir.resolve(PRISTINE))
            return LocalDatabase(workDir)
        }

        private fun connect(file: Path): Connection = DriverManager.getConnection("jdbc:sqlite:$file")

        /** Copies [from] over [to], dropping any stale WAL and shared-memory files beside [to]. */
        private fun copyOver(from: Path, to: Path) {
            for (suffix in listOf("-wal", "-shm")) Files.deleteIfExists(to.resolveSibling(to.fileName.toString() + suffix))
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING)
        }

        private fun value(rs: ResultSet, column: Int): SqlValue = when (val v = rs.getObject(column)) {
            null -> SqlValue.Null
            is Int -> SqlValue.Integer(v.toLong())
            is Long -> SqlValue.Integer(v)
            is Float -> SqlValue.Real(v.toDouble())
            is Double -> SqlValue.Real(v)
            is ByteArray -> SqlValue.Blob(v.size)
            else -> SqlValue.Text(v.toString())
        }

        private fun millisSince(start: Long) = (System.nanoTime() - start) / 1_000_000
    }
}
