package com.programmersbox.scrcpyviewer.files

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DragCacheTest {
    private val root = Files.createTempDirectory("drag-cache").resolve("scrcpy-vwr")
    private val cache = DragCache(root)

    @Test
    fun `a drag dir is a fresh folder under the serial's drag folder`() {
        val a = cache.newDragDir("R5CX12")
        val b = cache.newDragDir("R5CX12")
        assertTrue(Files.isDirectory(a))
        assertEquals(root.resolve("R5CX12").resolve("drag"), a.parent)
        assertNotEquals(a, b)
        assertEquals(0, Files.list(a).use { it.count() })
    }

    @Test
    fun `a Wi-Fi serial becomes one path segment`() {
        val dir = cache.newDragDir("192.168.1.5:5555")
        assertEquals(root.resolve("192.168.1.5_5555").resolve("drag"), dir.parent)
    }

    @Test
    fun `deleteSession removes that serial's drag folders only`() {
        val mine = cache.newDragDir("A")
        Files.writeString(mine.resolve("photo.jpg"), "x")
        Files.createDirectories(mine.resolve("Camera/sub"))
        val other = cache.newDragDir("B")
        val open = Files.createDirectories(root.resolve("A").resolve("open"))
        cache.deleteSession("A")
        assertFalse(Files.exists(root.resolve("A").resolve("drag")))
        assertTrue(Files.isDirectory(other))
        assertTrue(Files.isDirectory(open), "Open on computer files stay")
    }

    @Test
    fun `delete removes one drag folder and nothing outside the cache`() {
        val a = cache.newDragDir("A")
        val b = cache.newDragDir("A")
        Files.writeString(a.resolve("f.txt"), "x")
        cache.delete(a)
        assertFalse(Files.exists(a))
        assertTrue(Files.exists(b))
        val outside = Files.createTempDirectory("not-the-cache")
        cache.delete(outside)
        assertTrue(Files.exists(outside))
    }

    @Test
    fun `sweep removes every serial's leftovers and keeps the rest`() {
        Files.writeString(cache.newDragDir("A").resolve("a.txt"), "x")
        cache.newDragDir("B")
        val install = Files.createDirectories(root.resolve("A").resolve("install"))
        cache.sweep()
        assertFalse(Files.exists(root.resolve("A").resolve("drag")))
        assertFalse(Files.exists(root.resolve("B").resolve("drag")))
        assertTrue(Files.isDirectory(install))
    }

    @Test
    fun `sweep without a cache folder does nothing`() {
        DragCache(Files.createTempDirectory("none").resolve("missing")).sweep()
    }
}
