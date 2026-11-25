package com.programmersbox.scrcpyviewer.database

/** One SQLite cell value, by storage class. A BLOB keeps only its size: the Data tab shows it and never edits it. */
sealed interface SqlValue {
    data object Null : SqlValue
    data class Integer(val v: Long) : SqlValue
    data class Real(val v: Double) : SqlValue
    data class Text(val v: String) : SqlValue
    data class Blob(val size: Int) : SqlValue
}

/** [v] as an SQL literal: NULL, 42, 1.5 or 'it''s'. A BLOB has no literal here, since only its size is known. */
fun sqlLiteral(v: SqlValue): String = when (v) {
    SqlValue.Null -> "NULL"
    is SqlValue.Integer -> v.v.toString()
    is SqlValue.Real -> v.v.toString()
    is SqlValue.Text -> "'" + v.v.replace("'", "''") + "'"
    is SqlValue.Blob -> throw IllegalArgumentException("BLOB cells can't be edited")
}

/** [name] as a quoted SQL identifier: "my ""t""". */
fun sqlIdentifier(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

/** How an edit finds its row: by rowid, or by primary key for a WITHOUT ROWID table. */
sealed interface RowKey {
    data class Rowid(val id: Long) : RowKey
    data class Pk(val values: Map<String, SqlValue>) : RowKey
}

/** A table or view in the copy. [system] marks SQLite's and Android's bookkeeping tables. */
data class TableInfo(val name: String, val rows: Long, val system: Boolean, val view: Boolean, val withoutRowid: Boolean)

/** One column from `PRAGMA table_info`; [pk] is its place in the primary key, 0 when it isn't part of it. */
data class ColumnInfo(val name: String, val type: String, val pk: Int, val notNull: Boolean)

/** One row of a [Page]; [key] is null for a view, which can't be edited. */
data class Row(val key: RowKey?, val values: List<SqlValue>)

/** Up to a page of a table's rows; [total] counts every row that matches the filter. */
data class Page(val columns: List<ColumnInfo>, val rows: List<Row>, val total: Long, val editable: Boolean)

/** What [LocalDatabase.execute] did: rows for a query, a change count for anything else, or SQLite's error. */
sealed interface SqlOutcome {
    data class Rows(val columns: List<String>, val rows: List<List<SqlValue>>, val millis: Long) : SqlOutcome
    data class Changed(val count: Int, val millis: Long) : SqlOutcome
    data class Failed(val message: String) : SqlOutcome
}
