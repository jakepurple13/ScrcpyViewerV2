package com.programmersbox.scrcpyviewer.files

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DevicePathTest {
    @Test
    fun shellQuoteEscapesSingleQuote() {
        assertEquals("'it'\\''s'", shellQuote("it's"))
        assertEquals("'plain'", shellQuote("plain"))
        assertEquals("''", shellQuote(""))
    }

    @Test
    fun shellQuoteRoundTripsThroughHostShell() {
        if (System.getProperty("os.name").startsWith("Windows")) return
        val names = listOf(
            "plain.txt", "a b", "it's", "\$HOME", "`id`", ";rm -rf /", "line1\nline2",
            "\"dq\"", "back\\slash", "😀.png", "写真.jpg", "*", "-n",
        )
        for (name in names) {
            val p = ProcessBuilder("/bin/sh", "-c", "printf %s ${shellQuote(name)}")
                .redirectErrorStream(false).start()
            val out = p.inputStream.readBytes()
            p.waitFor(10, TimeUnit.SECONDS)
            assertEquals(name, String(out, Charsets.UTF_8), "round trip of <$name>")
        }
    }

    @Test
    fun devicePathJoins() {
        assertEquals("/sdcard/a", devicePath("/sdcard", "a"))
        assertEquals("/sdcard/a", devicePath("/sdcard/", "a"))
        assertEquals("/sdcard/DCIM/a b", devicePath("/sdcard/DCIM", "a b"))
    }

    @Test
    fun parentOfWalksUp() {
        assertNull(parentOf(DEVICE_ROOT))
        assertEquals("/sdcard", parentOf("/sdcard/DCIM"))
        assertEquals("/sdcard/DCIM", parentOf("/sdcard/DCIM/Camera"))
    }

    @Test
    fun crumbsFromRoot() {
        assertEquals(
            listOf(
                "Internal storage" to "/sdcard",
                "DCIM" to "/sdcard/DCIM",
                "Camera" to "/sdcard/DCIM/Camera",
            ),
            crumbs("/sdcard/DCIM/Camera"),
        )
        assertEquals(listOf("Internal storage" to "/sdcard"), crumbs("/sdcard"))
    }

    @Test
    fun normalizeDirCases() {
        assertEquals("/sdcard/Download", normalizeDir("/sdcard/Download/"))
        assertEquals("/sdcard", normalizeDir("/sdcard/"))
        assertEquals("/sdcard", normalizeDir("/storage/emulated/0"))
        assertEquals("/sdcard", normalizeDir("/storage/emulated/0/"))
        assertEquals("/sdcard/DCIM", normalizeDir("/storage/emulated/0/DCIM"))
        assertEquals("/sdcard", normalizeDir("/sdcard"))
    }

    @Test
    fun uniqueNameCases() {
        assertEquals("b.txt", uniqueName(setOf("a.txt"), "b.txt"))
        assertEquals("a (1).txt", uniqueName(setOf("a.txt"), "a.txt"))
        assertEquals("a (2).txt", uniqueName(setOf("a.txt", "a (1).txt"), "a.txt"))
        assertEquals("New folder (1)", uniqueName(setOf("New folder"), "New folder"))
        assertEquals(".nomedia (1)", uniqueName(setOf(".nomedia"), ".nomedia"))
    }

    @Test
    fun nameProblemCases() {
        assertEquals("No “/” in names", nameProblem("a/b", emptySet()))
        assertEquals("Name already used", nameProblem("a", setOf("a")))
        assertEquals("Name can’t be empty", nameProblem("  ", emptySet()))
        assertEquals("Name can’t be empty", nameProblem("", setOf("")))
        assertEquals("Name not allowed", nameProblem(".", emptySet()))
        assertEquals("Name not allowed", nameProblem("..", emptySet()))
        assertEquals("Name can’t be empty", nameProblem(" ", setOf(".")))
        assertNull(nameProblem("...", emptySet()))
        assertNull(nameProblem("ok", setOf("a")))
    }

    @Test
    fun isInsideCases() {
        assertTrue(isInside("/sdcard/A/B", "/sdcard/A"))
        assertTrue(isInside("/sdcard/A", "/sdcard/A"))
        assertFalse(isInside("/sdcard/AB", "/sdcard/A"))
        assertFalse(isInside("/sdcard", "/sdcard/A"))
    }
}
