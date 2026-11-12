package com.programmersbox.scrcpyviewer.files

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DragCacheTest {
    private val tmp: Path = Files.createTempDirectory("drag-cache")
    private val root = tmp.resolve("scrcpy-vwr")
    private val cache = DragCache(root)
    private val hourAgo = System.currentTimeMillis() - 2 * DRAG_SWEEP_MIN_AGE_MILLIS

    /** A drag folder of [serial] as a pull makes it, with a file in it. */
    private fun made(serial: String, ageMillis: Long? = null): Path {
        val dir = Files.createDirectories(cache.newDragDir(serial))
        Files.writeString(dir.resolve("photo.jpg"), "x")
        if (ageMillis != null) Files.setLastModifiedTime(dir, FileTime.fromMillis(System.currentTimeMillis() - ageMillis))
        return dir
    }

    /** A symbolic link at [link] to [target], or null where the OS does not let us make one. */
    private fun symlink(link: Path, target: Path): Path? =
        runCatching { Files.createSymbolicLink(link, target) }.getOrNull()

    /** A folder outside the cache, with a `drag/<name>/keep.txt` in it, that a link could point at. */
    private fun victim(name: String = "abc"): Path {
        val v = Files.createTempDirectory("victim")
        Files.writeString(Files.createDirectories(v.resolve("drag").resolve(name)).resolve("keep.txt"), "mine")
        Files.setLastModifiedTime(v.resolve("drag").resolve(name), FileTime.fromMillis(hourAgo))
        return v
    }

    @Test
    fun `a drag dir is a fresh path under the serial's drag folder, made by the pull`() {
        val a = cache.newDragDir("R5CX12")
        val b = cache.newDragDir("R5CX12")
        assertEquals(root.resolve("R5CX12").resolve("drag"), a.parent)
        assertNotEquals(a, b)
        assertFalse(Files.exists(a), "no file I/O when a drag begins")
    }

    @Test
    fun `a Wi-Fi serial becomes one path segment`() {
        assertEquals(root.resolve("192.168.1.5_5555").resolve("drag"), cache.newDragDir("192.168.1.5:5555").parent)
    }

    @Test
    fun `deleteSession removes that serial's drag folders only`() {
        val mine = made("A")
        Files.createDirectories(mine.resolve("Camera/sub"))
        val other = made("B")
        val open = Files.createDirectories(root.resolve("A").resolve("open"))
        cache.deleteSession("A")
        assertFalse(Files.exists(root.resolve("A").resolve("drag")))
        assertTrue(Files.isDirectory(other))
        assertTrue(Files.isDirectory(open), "Open on computer files stay")
    }

    @Test
    fun `delete removes one drag folder and nothing outside the cache`() {
        val a = made("A")
        val b = made("A")
        cache.delete(a)
        assertFalse(Files.exists(a))
        assertTrue(Files.exists(b))
        val outside = Files.createTempDirectory("not-the-cache")
        cache.delete(outside)
        assertTrue(Files.exists(outside))
        // Inside the root but not a drag folder.
        val open = Files.createDirectories(root.resolve("A").resolve("open").resolve("x"))
        cache.delete(open)
        assertTrue(Files.exists(open))
    }

    @Test
    fun `sweep removes old leftovers and keeps young ones and the rest`() {
        val old = made("A", ageMillis = 2 * DRAG_SWEEP_MIN_AGE_MILLIS)
        val oldB = made("B", ageMillis = 2 * DRAG_SWEEP_MIN_AGE_MILLIS)
        val young = made("A", ageMillis = 60_000)
        val install = Files.createDirectories(root.resolve("A").resolve("install"))
        cache.sweep()
        assertFalse(Files.exists(old))
        assertFalse(Files.exists(oldB))
        assertTrue(Files.exists(young), "another instance's drag may be in progress")
        assertTrue(Files.isDirectory(install))
    }

    @Test
    fun `sweep without a cache folder does nothing`() {
        DragCache(Files.createTempDirectory("none").resolve("missing")).sweep()
    }

    @Test
    fun `a serial folder that is a link is never followed`() {
        val v = victim()
        Files.createDirectories(root)
        symlink(root.resolve("EVIL"), v) ?: return // no symlink permission (Windows without the privilege)
        cache.sweep()
        cache.deleteSession("EVIL")
        cache.delete(root.resolve("EVIL").resolve("drag").resolve("abc"))
        assertTrue(Files.exists(v.resolve("drag").resolve("abc").resolve("keep.txt")))
    }

    @Test
    fun `a drag folder that is a link is never followed`() {
        val v = victim()
        Files.createDirectories(root.resolve("A"))
        symlink(root.resolve("A").resolve("drag"), v.resolve("drag")) ?: return
        cache.sweep()
        cache.deleteSession("A")
        cache.delete(root.resolve("A").resolve("drag").resolve("abc"))
        assertTrue(Files.exists(v.resolve("drag").resolve("abc").resolve("keep.txt")))
    }

    @Test
    fun `a link inside a drag folder is removed, not what it points to`() {
        val v = victim()
        val dir = made("A", ageMillis = 2 * DRAG_SWEEP_MIN_AGE_MILLIS)
        symlink(dir.resolve("link"), v) ?: return
        Files.setLastModifiedTime(dir, FileTime.fromMillis(hourAgo))
        cache.sweep()
        assertFalse(Files.exists(dir))
        assertTrue(Files.exists(v.resolve("drag").resolve("abc").resolve("keep.txt")))
    }

    @Test
    fun `a root that is a link is left alone`() {
        val v = victim()
        Files.createDirectories(v.resolve("S"))
        Files.move(v.resolve("drag"), v.resolve("S").resolve("drag"))
        val linkRoot = tmp.resolve("linked-root")
        symlink(linkRoot, v) ?: return
        val linked = DragCache(linkRoot)
        linked.sweep()
        linked.deleteSession("S")
        linked.delete(linkRoot.resolve("S").resolve("drag").resolve("abc"))
        assertTrue(Files.exists(v.resolve("S").resolve("drag").resolve("abc").resolve("keep.txt")))
    }
}
