package com.programmersbox.scrcpyviewer.database

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.ExperimentalPathApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalPathApi::class)
class LocalDatabaseTest {
    private val root: Path = Files.createTempDirectory("localdb")
    private val opened = mutableListOf<LocalDatabase>()

    @AfterTest
    fun cleanUp() {
        opened.forEach { it.close() }
        root.deleteRecursively()
    }

    private fun connect(file: Path): Connection = DriverManager.getConnection("jdbc:sqlite:$file")

    /** A database at pulled/<name> built by [setup]; closed before it's returned. */
    private fun fixture(name: String = "app.db", setup: Connection.() -> Unit): Path {
        val dir = root.resolve("pulled").createDirectories()
        val file = dir.resolve(name)
        connect(file).use { it.setup() }
        return file
    }

    private fun open(pulled: Path): LocalDatabase =
        LocalDatabase.open(pulled, root.resolve("work").createDirectories()).also { opened += it }

    private fun Connection.sql(vararg statements: String) = createStatement().use { s -> statements.forEach { s.execute(it) } }

    private fun notes(): Path = fixture {
        sql(
            "CREATE TABLE notes (id INTEGER PRIMARY KEY, title TEXT, folder_id INTEGER, pinned INTEGER, updated_at INTEGER)",
            "INSERT INTO notes VALUES (1,'Grocery run',1,1,300),(2,'Release',2,1,200),(3,'Gift ideas',3,0,100),(4,'Parking',NULL,1,50)",
            "CREATE TABLE android_metadata (locale TEXT)",
        )
    }

    @Test
    fun opensWalCopyAndSeesUncheckpointedRows() {
        val source = root.resolve("source").createDirectories().resolve("x.db")
        val writer = connect(source)
        writer.sql("PRAGMA journal_mode=WAL", "PRAGMA wal_autocheckpoint=0", "CREATE TABLE t (v TEXT)", "INSERT INTO t VALUES ('a')")
        // The writer stays open, so the row is still only in x.db-wal when the files are copied.
        val pulled = root.resolve("pulled").createDirectories()
        for (suffix in listOf("", "-wal", "-shm")) {
            val from = source.resolveSibling("x.db$suffix")
            if (from.exists()) Files.copy(from, pulled.resolve("x.db$suffix"))
        }
        writer.close()
        val db = open(pulled.resolve("x.db"))
        assertEquals(1L, db.tables().single { it.name == "t" }.rows)
    }

    @Test
    fun pageLimitsAndCountsTotal() {
        val file = fixture {
            sql("CREATE TABLE t (v INTEGER)")
            autoCommit = false
            prepareStatement("INSERT INTO t VALUES (?)").use { p -> repeat(120_000) { p.setInt(1, it); p.addBatch() }; p.executeBatch() }
            commit()
        }
        val page = open(file).page("t")
        assertEquals(500, page.rows.size)
        assertEquals(120_000L, page.total)
    }

    @Test
    fun setCellLogsUpdateAndDiscardRestores() {
        val db = open(notes())
        db.setCell("notes", RowKey.Rowid(3), "folder_id", SqlValue.Integer(1))
        assertEquals(listOf("UPDATE \"notes\" SET \"folder_id\" = 1 WHERE rowid = 3;"), db.log)
        assertEquals(SqlValue.Integer(1), db.page("notes", "id = 3").rows.single().values[2])
        db.discard()
        assertEquals(SqlValue.Integer(3), db.page("notes", "id = 3").rows.single().values[2])
        assertTrue(db.log.isEmpty())
    }

    @Test
    fun withoutRowidKeysByPrimaryKey() {
        val db = open(fixture { sql("CREATE TABLE p (a INTEGER, b TEXT, c TEXT, PRIMARY KEY (a, b)) WITHOUT ROWID", "INSERT INTO p VALUES (1,'x','old')") })
        val row = db.page("p").rows.single()
        assertIs<RowKey.Pk>(row.key)
        db.setCell("p", row.key!!, "c", SqlValue.Text("new"))
        assertEquals("UPDATE \"p\" SET \"c\" = 'new' WHERE \"a\" = 1 AND \"b\" = 'x';", db.log.single())
        assertEquals(SqlValue.Text("new"), db.page("p").rows.single().values[2])
    }

    @Test
    fun viewsAndBlobsAreReadOnly() {
        val db = open(fixture {
            sql("CREATE TABLE b (id INTEGER PRIMARY KEY, data BLOB)", "INSERT INTO b VALUES (1, x'0102')", "CREATE VIEW v AS SELECT id FROM b")
        })
        assertFalse(db.page("v").editable)
        assertTrue(db.tables().single { it.name == "v" }.view)
        assertEquals(SqlValue.Blob(2), db.page("b").rows.single().values[1])
        val e = assertFailsWith<IllegalArgumentException> { db.setCell("b", RowKey.Rowid(1), "data", SqlValue.Text("x")) }
        assertEquals("BLOB cells can't be edited", e.message)
    }

    @Test
    fun executeSelectReturnsRowsAndUpdateIsLogged() {
        val db = open(notes())
        val rows = db.execute("SELECT title FROM notes WHERE pinned = 1")
        assertIs<SqlOutcome.Rows>(rows)
        assertEquals(3, rows.rows.size)
        assertTrue(db.log.isEmpty())
        val changed = db.execute("UPDATE notes SET pinned = 0 WHERE pinned = 1")
        assertIs<SqlOutcome.Changed>(changed)
        assertEquals(3, changed.count)
        assertEquals("UPDATE notes SET pinned = 0 WHERE pinned = 1;", db.log.last())
    }

    @Test
    fun executeErrorIsFailedNotThrown() {
        val outcome = open(notes()).execute("SELECT * FROM nope")
        assertIs<SqlOutcome.Failed>(outcome)
        assertTrue("no such table: nope" in outcome.message, outcome.message)
    }

    @Test
    fun prepareForWriteLeavesNoWal() {
        val db = open(fixture { sql("PRAGMA journal_mode=WAL", "CREATE TABLE t (v TEXT)") })
        db.execute("INSERT INTO t VALUES ('a')")
        val file = db.prepareForWrite()
        val wal = file.resolveSibling(file.fileName.toString() + "-wal")
        assertTrue(!wal.exists() || wal.fileSize() == 0L)
        db.markWritten()
        assertEquals("wal", db.journalMode())
        assertEquals(1L, db.tables().single { it.name == "t" }.rows)
    }

    @Test
    fun quotesOddIdentifiers() {
        val db = open(fixture { sql("CREATE TABLE \"my \"\"t\"\"\" (\"it's\" TEXT)", "INSERT INTO \"my \"\"t\"\"\" VALUES ('a')") })
        val table = "my \"t\""
        db.setCell(table, db.page(table).rows.single().key!!, "it's", SqlValue.Text("o'k"))
        assertEquals(SqlValue.Text("o'k"), db.page(table).rows.single().values.single())
    }

    @Test
    fun aTruncatedCopyIsRefused() {
        val whole = fixture("whole.db") {
            sql("CREATE TABLE t (v TEXT)")
            autoCommit = false
            prepareStatement("INSERT INTO t VALUES (?)").use { p -> repeat(2_000) { p.setString(1, "row $it ".repeat(8)); p.addBatch() }; p.executeBatch() }
            commit()
        }
        val bytes = Files.readAllBytes(whole)
        val cut = Files.write(root.resolve("pulled").resolve("cut.db"), bytes.copyOf(bytes.size / 2))
        assertFailsWith<java.sql.SQLException> { open(cut) }
    }

    @Test
    fun aRawBeginIsCommittedBeforeWriting() {
        val db = open(notes())
        db.execute("BEGIN")
        db.execute("UPDATE notes SET title = 'kept' WHERE id = 1")
        db.prepareForWrite()
        db.markWritten()
        assertEquals(SqlValue.Text("kept"), db.page("notes", "id = 1").rows.single().values[1])
    }

    @Test
    fun systemTablesAreMarked() {
        val tables = open(notes()).tables().associateBy { it.name }
        assertTrue(tables.getValue("android_metadata").system)
        assertFalse(tables.getValue("notes").system)
    }
}
