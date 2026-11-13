package com.programmersbox.scrcpyviewer.links

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkStoreTest {
    private val file = Files.createTempDirectory("links").resolve("links.json")
    private fun entry(link: String, pkg: String? = null, at: Long = 1) =
        LinkEntry(link, pkg, null, ok = true, detail = "ok", at = at)

    @Test
    fun `records per device newest first`() {
        val s = LinkStore(file)
        s.record("d1", entry("a"))
        s.record("d1", entry("b"))
        s.record("d2", entry("c"))
        assertEquals(listOf("b", "a"), s.state.value.history["d1"]!!.map { it.link })
        assertEquals(listOf("c"), s.state.value.history["d2"]!!.map { it.link })
    }

    @Test
    fun `same link and package moves to top with new data`() {
        val s = LinkStore(file)
        s.record("d", entry("a", "p"))
        s.record("d", entry("b", "p"))
        s.record("d", entry("a", "p", at = 9))
        val h = s.state.value.history["d"]!!
        assertEquals(listOf("a", "b"), h.map { it.link })
        assertEquals(9, h.first().at)
        s.record("d", entry("a", "other"))
        assertEquals(3, s.state.value.history["d"]!!.size)
    }

    @Test
    fun `history capped at 50 per device`() {
        val s = LinkStore(file)
        repeat(60) { s.record("d", entry("l$it")) }
        val h = s.state.value.history["d"]!!
        assertEquals(50, h.size)
        assertEquals("l59", h.first().link)
        assertEquals("l10", h.last().link)
    }

    @Test
    fun `stars are global and survive clear`() {
        val s = LinkStore(file)
        s.record("d", entry("a"))
        s.star(entry("a"), true)
        s.star(entry("a"), true)
        assertEquals(1, s.state.value.starred.size)
        s.clear("d")
        assertTrue(s.state.value.history["d"].isNullOrEmpty())
        assertEquals(listOf("a"), s.state.value.starred.map { it.link })
        s.star(entry("a"), false)
        assertTrue(s.state.value.starred.isEmpty())
    }

    @Test
    fun `corrupt file gives empty state, backup and error-free recovery`() {
        Files.writeString(file, "{ not json")
        val s = LinkStore(file)
        assertTrue(s.state.value.history.isEmpty() && s.state.value.starred.isEmpty())
        assertTrue(Files.exists(file.resolveSibling("links.json.bak")))
        assertNull(s.error.value, "a working backup is not an error")
        assertEquals("links.json was unreadable; saved a copy as links.json.bak and started fresh", s.notice.value)
        s.dismissNotice()
        assertNull(s.notice.value)
        s.record("d", entry("a"))
        assertNull(LinkStore(file).notice.value, "the next run has a good file and no notice")
        assertEquals(listOf("a"), LinkStore(file).state.value.history["d"]!!.map { it.link })
    }

    @Test
    fun `unreadable file sets error`() {
        // A directory named links.json cannot be read as text.
        Files.createDirectories(file)
        val s = LinkStore(file)
        assertNotNull(s.error.value)
        assertNull(s.notice.value, "an unreadable file is an error, not a recovery")
    }

    @Test
    fun `recording after an unreadable file updates memory and leaves the file alone`() {
        Files.createDirectories(file)
        val s = LinkStore(file)
        assertNotNull(s.error.value)
        s.record("d", entry("a"))
        assertEquals(listOf("a"), s.state.value.history["d"]!!.map { it.link })
        assertTrue(Files.isDirectory(file), "the unreadable file is not overwritten")
    }

    @Test
    fun `persists across instances`() {
        val s = LinkStore(file)
        s.record("d", entry("a", "p"))
        s.star(entry("b"), true)
        val again = LinkStore(file).state.value
        assertEquals(listOf(entry("a", "p")), again.history["d"])
        assertEquals(listOf(entry("b")), again.starred)
    }
}
