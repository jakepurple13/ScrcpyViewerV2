package com.programmersbox.scrcpyviewer.ui.common

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImageClipboardTest {
    private val png: Path = Files.createTempFile("my shot #1", ".png").also { Files.write(it, byteArrayOf(1, 2, 3)) }

    private fun copy(env: Map<String, String>, tools: Set<String>, run: ToolRun = { _, _ -> 0 }) =
        ImageClipboard.copyPng(png, OpenPlatform.Linux, env::get, tools::contains, run)

    @Test
    fun `wayland session uses wl-copy with the png on stdin`() {
        var seen: Pair<List<String>, Path?>? = null
        val r = copy(mapOf("WAYLAND_DISPLAY" to "wayland-0"), setOf("wl-copy", "xclip")) { c, s -> seen = c to s; 0 }
        assertTrue(r.isSuccess)
        assertEquals(listOf("wl-copy", "--type", "image/png") to png, seen)
    }

    @Test
    fun `x session uses xclip with the file as one argument`() {
        var seen: Pair<List<String>, Path?>? = null
        copy(emptyMap(), setOf("wl-copy", "xclip")) { c, s -> seen = c to s; 0 }
        assertEquals(listOf("xclip", "-selection", "clipboard", "-t", "image/png", "-i", png.toString()) to null, seen)
    }

    @Test
    fun `wayland without wl-copy falls back to xclip, x without xclip to wl-copy`() {
        assertEquals("xclip", ImageClipboard.linuxCommand(png, { "w" }, { it == "xclip" })!!.command.first())
        assertEquals("wl-copy", ImageClipboard.linuxCommand(png, { null }, { it == "wl-copy" })!!.command.first())
    }

    @Test
    fun `no tool fails with the install message and runs nothing`() {
        var ran = false
        val r = copy(mapOf("WAYLAND_DISPLAY" to "w"), emptySet()) { _, _ -> ran = true; 0 }
        assertEquals("Install wl-clipboard or xclip to copy images", r.exceptionOrNull()?.message)
        assertTrue(!ran)
    }

    @Test
    fun `timeout and non-zero exit fail`() {
        assertTrue(copy(emptyMap(), setOf("xclip")) { _, _ -> null }.exceptionOrNull()!!.message!!.contains("timed out"))
        assertTrue(copy(emptyMap(), setOf("xclip")) { _, _ -> 1 }.isFailure)
    }

    @Test
    fun `a missing file fails without running a tool`() {
        val r = ImageClipboard.copyPng(Path.of("/nonexistent/x.png"), OpenPlatform.Linux, { null }, { true }, { _, _ -> 0 })
        assertTrue(r.isFailure)
    }
}
