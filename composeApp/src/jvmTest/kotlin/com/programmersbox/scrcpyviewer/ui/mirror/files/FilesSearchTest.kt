package com.programmersbox.scrcpyviewer.ui.mirror.files

import com.programmersbox.scrcpyviewer.files.DeviceEntry
import com.programmersbox.scrcpyviewer.files.FileKind
import com.programmersbox.scrcpyviewer.files.SearchHit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun hit(dir: String, name: String, isDir: Boolean = false) = SearchHit(dir, DeviceEntry(name, isDir, 10, 1000))

class FilesSearchTest {
    private val device = listOf(
        hit("/sdcard/DCIM/Screenshots", "Screenshot_2.png"),
        hit("/sdcard/DCIM/Screenshots", "screenshot_1.png"),
        hit("/sdcard/DCIM", "Screenshots", isDir = true),
        hit("/sdcard/Movies", "screen-rec.mp4"),
        hit("/sdcard/Download", "screen-tool.apk"),
    )
    private val runs = mutableListOf<Pair<String, Boolean>>()
    private var hidden = false

    /** When set, the search waits for it after the first batch. */
    private var gate: CompletableDeferred<Unit>? = null
    private var failWith: String? = null

    private fun run(query: String, includeHidden: Boolean): Flow<List<SearchHit>> = flow {
        runs += query to includeHidden
        failWith?.let { throw IOException(it) }
        val matches = device.filter { it.entry.name.contains(query, ignoreCase = true) }
        emit(matches.take(2))
        gate?.await()
        if (matches.size > 2) emit(matches.drop(2))
    }

    private fun TestScope.search() = FilesSearch(backgroundScope, ::run, { hidden }, now = { testScheduler.currentTime })

    private fun TestScope.settle() {
        advanceTimeBy(5_000)
        runCurrent()
    }

    @Test
    fun `typing waits for a pause, then streams matches grouped by folder`() = runTest {
        val s = search()
        s.open()
        s.setQuery("scr")
        s.setQuery("screen")
        advanceTimeBy(SEARCH_DEBOUNCE_MILLIS - 1)
        runCurrent()
        assertEquals(emptyList(), runs, "nothing runs while typing")
        assertTrue(s.running)
        settle()
        assertEquals(listOf("screen" to false), runs)
        assertFalse(s.running)
        assertEquals(
            listOf("/sdcard/DCIM", "/sdcard/DCIM/Screenshots", "/sdcard/Download", "/sdcard/Movies"),
            s.groups.map { it.dir },
        )
        assertEquals(listOf("screenshot_1.png", "Screenshot_2.png"), s.groups[1].hits.map { it.entry.name }, "names in order, ignoring case")
        assertEquals(5, s.hits.size)
        assertEquals(mapOf(FileKind.Folder to 1, FileKind.Image to 2, FileKind.Video to 1, FileKind.Apk to 1), s.counts)
    }

    @Test
    fun `matches show while the search still runs, and Stop keeps them`() = runTest {
        gate = CompletableDeferred()
        val s = search()
        s.setQuery("screen")
        settle()
        assertTrue(s.running)
        assertEquals(2, s.hits.size)
        s.stop()
        assertFalse(s.running)
        assertTrue(s.stopped)
        assertEquals(2, s.hits.size)
        assertEquals("Stopped · 2 matches so far", searchStatusText(s))
    }

    @Test
    fun `chips narrow the matches while counts stay over everything found`() = runTest {
        val s = search()
        s.setQuery("screen")
        settle()
        s.toggleKind(FileKind.Image)
        s.toggleKind(FileKind.Apk)
        assertEquals(listOf("screenshot_1.png", "Screenshot_2.png", "screen-tool.apk"), s.shown.map { it.entry.name })
        assertEquals(2, s.counts[FileKind.Image])
        assertEquals(1, s.counts[FileKind.Folder])
        assertEquals("3 of 5 matches in 2 folders", searchStatusText(s))
        s.toggleKind(FileKind.Image)
        s.toggleKind(FileKind.Apk)
        assertEquals(5, s.shown.size)
    }

    @Test
    fun `the cursor walks the shown matches and leaves when a chip hides its match`() = runTest {
        val s = search()
        s.setQuery("screen")
        settle()
        s.moveCursor(1)
        assertEquals("/sdcard/DCIM/Screenshots", s.cursor)
        s.moveCursor(1)
        s.moveCursor(1)
        assertEquals("/sdcard/DCIM/Screenshots/Screenshot_2.png", s.cursor)
        s.moveCursor(-10)
        assertEquals("/sdcard/DCIM/Screenshots", s.cursor, "stops at the first")
        s.toggleKind(FileKind.Image)
        assertNull(s.cursor)
    }

    @Test
    fun `stops at the cap and says so`() = runTest {
        val many = (0 until MAX_SEARCH_HITS + 50).map { hit("/sdcard/x", "f$it.txt") }
        val s = FilesSearch(backgroundScope, { _, _ -> flow { many.chunked(100).forEach { emit(it) } } }, { false })
        s.setQuery("f")
        settle()
        assertEquals(MAX_SEARCH_HITS, s.hits.size)
        assertTrue(s.truncated)
        assertFalse(s.running)
        assertEquals("First 1,000 matches · type more to narrow it", searchStatusText(s))
    }

    @Test
    fun `a failure shows its message and clearing the query clears everything`() = runTest {
        failWith = "find: not found"
        val s = search()
        s.setQuery("screen")
        settle()
        assertEquals("find: not found", s.error)
        failWith = null
        s.rerun()
        settle()
        assertNull(s.error)
        assertEquals(5, s.hits.size)
        s.setQuery("")
        assertEquals(emptyList(), s.hits)
        assertFalse(s.running)
    }

    @Test
    fun `closing stops a running search but keeps the query for next time`() = runTest {
        gate = CompletableDeferred()
        hidden = true
        val s = search()
        s.open()
        s.setQuery("screen")
        settle()
        assertEquals(listOf("screen" to true), runs, "hidden files follow the folder view")
        s.close()
        assertFalse(s.active)
        assertFalse(s.running)
        assertEquals("screen", s.query)
    }
}
