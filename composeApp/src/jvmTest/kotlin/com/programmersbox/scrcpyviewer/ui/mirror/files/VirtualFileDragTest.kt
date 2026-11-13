package com.programmersbox.scrcpyviewer.ui.mirror.files

import com.programmersbox.scrcpyviewer.files.DeviceEntry
import com.programmersbox.scrcpyviewer.files.JobState
import com.programmersbox.scrcpyviewer.files.TreeEntry
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VirtualFileDragTest {
    private class Job(val row: String, val total: Long, val onCancel: () -> Unit) : RowTransfer {
        val bytes = mutableListOf<Long>()
        var state: JobState? = null
        override fun progress(bytes: Long) {
            this.bytes += bytes
        }

        override fun finish(state: JobState) {
            check(this.state == null) { "finished twice" }
            this.state = state
        }
    }

    private val jobs = mutableListOf<Job>()
    private val trees = mutableListOf<Pair<String, List<String>>>()
    private val opened = mutableListOf<String>()
    private var content: (String) -> InputStream = { ByteArrayInputStream(ByteArray(0)) }
    private var tree: List<TreeEntry> = emptyList()

    private fun builder(windows: Boolean = true) = VirtualDragBuilder(
        tree = { dir, names -> trees += dir to names; tree },
        openStream = { path -> opened += path; content(path) },
        track = { row, total, onCancel -> Job(row.name, total, onCancel).also { jobs += it } },
        windows = windows,
    )

    private fun file(name: String, size: Long) = DeviceEntry(name, false, size, 0)
    private fun folder(name: String) = DeviceEntry(name, true, 3452, 0)
    private fun bytes(n: Int) = ByteArrayInputStream(ByteArray(n) { it.toByte() })
    private fun VirtualDrag.file(path: String) = files.single { it.path == path }
    private fun InputStream.drain(): Int = use { it.readBytes().size }

    @Test
    fun `files only need no listing and keep their sizes`() = runBlocking {
        val drag = builder().build("/sdcard/Download", listOf(file("a.mp4", 10), file("b.txt", 3)))!!
        assertTrue(trees.isEmpty())
        assertEquals(listOf("a.mp4" to 10L, "b.txt" to 3L), drag.files.map { it.path to it.size })
        assertTrue(drag.files.none { it.isDirectory })
        assertTrue(opened.isEmpty(), "nothing read before the drop target asks")
        assertTrue(jobs.isEmpty(), "no tray job before a read")
    }

    @Test
    fun `folders are listed in one call and offered with their files`() = runBlocking {
        tree = listOf(
            TreeEntry("Camera", true, 0),
            TreeEntry("Camera/1.jpg", false, 5),
            TreeEntry("Camera/Sub", true, 0),
            TreeEntry("Camera/Sub/2.jpg", false, 7),
            TreeEntry("Empty", true, 0),
        )
        val drag = builder().build("/sdcard/DCIM", listOf(folder("Camera"), file("x.txt", 1), folder("Empty")))!!
        assertEquals(listOf("/sdcard/DCIM" to listOf("Camera", "Empty")), trees)
        assertEquals(
            listOf("Camera", "Camera/1.jpg", "Camera/Sub", "Camera/Sub/2.jpg", "x.txt", "Empty"),
            drag.files.map { it.path },
        )
        assertEquals(listOf(true, false, true, false, false, true), drag.files.map { it.isDirectory })
        content = { bytes(7) }
        drag.file("Camera/Sub/2.jpg").open!!().drain()
        assertEquals(listOf("/sdcard/DCIM/Camera/Sub/2.jpg"), opened)
    }

    @Test
    fun `names Windows refuses become safe, only when on Windows`() = runBlocking {
        tree = listOf(TreeEntry("a:b", true, 0), TreeEntry("a:b/c?.txt", false, 1))
        val rows = listOf(folder("a:b"))
        assertEquals(listOf("a_b", "a_b/c_.txt"), builder(windows = true).build("/d", rows)!!.files.map { it.path })
        assertEquals(listOf("a:b", "a:b/c?.txt"), builder(windows = false).build("/d", rows)!!.files.map { it.path })
    }

    @Test
    fun `a path too long for the descriptor refuses the virtual drag`() = runBlocking {
        val long = "x".repeat(VIRTUAL_PATH_MAX + 1)
        assertNull(builder().build("/d", listOf(file(long, 1))))
        assertTrue(builder().build("/d", listOf(file("x".repeat(VIRTUAL_PATH_MAX), 1))) != null)
    }

    @Test
    fun `one tray job per row, made at its first read, done when every file was read`() = runBlocking {
        tree = listOf(TreeEntry("Camera", true, 0), TreeEntry("Camera/1.jpg", false, 5), TreeEntry("Camera/2.jpg", false, 7))
        val drag = builder().build("/d", listOf(folder("Camera")))!!
        content = { path -> if (path.endsWith("1.jpg")) bytes(5) else bytes(7) }
        assertEquals(5, drag.file("Camera/1.jpg").open!!().drain())
        val job = jobs.single()
        assertEquals("Camera" to 12L, job.row to job.total)
        assertNull(job.state, "one file of two")
        assertEquals(7, drag.file("Camera/2.jpg").open!!().drain())
        assertEquals(JobState.Done, job.state)
        assertEquals(12L, job.bytes.last())
        drag.readsEnded()
        assertEquals(JobState.Done, job.state)
    }

    @Test
    fun `a row never read gets no job, one partly read ends cancelled when the target is done`() = runBlocking {
        val drag = builder().build("/d", listOf(file("a.bin", 10), file("b.bin", 10)))!!
        content = { bytes(10) }
        drag.file("a.bin").open!!().use { it.read(ByteArray(4)) }
        drag.readsEnded()
        assertEquals(listOf("a.bin" to JobState.Cancelled), jobs.map { it.row to it.state })
    }

    @Test
    fun `a file shorter than listed fails the row`() = runBlocking {
        val drag = builder().build("/d", listOf(file("a.bin", 10)))!!
        content = { bytes(6) }
        assertFailsWith<IOException> { drag.file("a.bin").open!!().drain() }
        assertTrue(jobs.single().state is JobState.Failed)
    }

    @Test
    fun `a failing read fails the row`() = runBlocking {
        val drag = builder().build("/d", listOf(file("a.bin", 10)))!!
        content = { object : InputStream() { override fun read(): Int = throw IOException("device gone") } }
        assertFailsWith<IOException> { drag.file("a.bin").open!!().read(ByteArray(4)) }
        assertEquals(JobState.Failed("a.bin: device gone"), jobs.single().state)
    }

    @Test
    fun `cancelling from the tray closes open reads and refuses new ones`() = runBlocking {
        tree = listOf(TreeEntry("F", true, 0), TreeEntry("F/a.bin", false, 10), TreeEntry("F/b.bin", false, 10))
        val drag = builder().build("/d", listOf(folder("F"), file("other.bin", 10)))!!
        var closed = false
        content = { object : ByteArrayInputStream(ByteArray(10)) { override fun close() { closed = true } } }
        val a = drag.file("F/a.bin").open!!()
        a.read(ByteArray(2))
        jobs.single().onCancel()
        assertTrue(closed)
        // The row's other files stay unreadable; another row is not touched.
        assertFailsWith<IOException> { drag.file("F/b.bin").open!!() }
        assertEquals(10, drag.file("other.bin").open!!().drain())
        assertEquals(JobState.Done, jobs.last().state)
        drag.readsEnded()
        assertNull(jobs.first().state, "the queue marked it cancelled itself; nothing more is reported")
    }

    @Test
    fun `empty files do not hold a row open`() = runBlocking {
        tree = listOf(TreeEntry("F", true, 0), TreeEntry("F/empty", false, 0), TreeEntry("F/a", false, 3))
        val drag = builder().build("/d", listOf(folder("F")))!!
        content = { path -> if (path.endsWith("/a")) bytes(3) else bytes(0) }
        drag.file("F/a").open!!().drain()
        assertEquals(JobState.Done, jobs.single().state, "the empty file need not be read")
    }
}
