package com.programmersbox.scrcpyviewer.files

import com.programmersbox.scrcpyviewer.assumePosix
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransferCacheTest {
    private val home: Path = Paths.get("/home/me").toAbsolutePath()
    private val tmp: Path = Files.createTempDirectory("transfer-cache")

    private fun root(mac: Boolean = false, windows: Boolean = false, env: Map<String, String> = emptyMap(), override: String? = null) =
        transferCacheRoot(mac, windows, { env[it] }, home, override)

    @Test
    fun `one per-user cache folder per OS, never the shared temp folder`() {
        assertEquals(home.resolve("Library").resolve("Caches").resolve("ScrcpyViewer"), root(mac = true))
        val local = tmp.resolve("Local").toAbsolutePath()
        assertEquals(local.resolve("ScrcpyViewer").resolve("Cache"), root(windows = true, env = mapOf("LOCALAPPDATA" to local.toString())))
        assertEquals(home.resolve("AppData").resolve("Local").resolve("ScrcpyViewer").resolve("Cache"), root(windows = true))
        val xdg = tmp.resolve("xdg-cache").toAbsolutePath()
        assertEquals(xdg.resolve("ScrcpyViewer"), root(env = mapOf("XDG_CACHE_HOME" to xdg.toString())))
        assertEquals(home.resolve(".cache").resolve("ScrcpyViewer"), root())
        // XDG says a relative value is invalid and is ignored.
        assertEquals(home.resolve(".cache").resolve("ScrcpyViewer"), root(env = mapOf("XDG_CACHE_HOME" to "rel/cache")))
        assertEquals(home.resolve(".cache").resolve("ScrcpyViewer"), root(env = mapOf("XDG_CACHE_HOME" to "")))
        assertEquals(tmp.resolve("o"), root(mac = true, override = tmp.resolve("o").toString()))
        val tmpdir = Paths.get(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
        assertFalse(transferCacheRoot().toAbsolutePath().normalize().startsWith(tmpdir.resolve("scrcpy-vwr")))
    }

    @Test
    fun `a missing root is made for this user alone`() {
        val r = tmp.resolve("a").resolve("ScrcpyViewer")
        assertEquals(r, ensurePrivateDir(r))
        assertTrue(Files.isDirectory(r))
        if (hasPosixPermissions()) {
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(r))
        }
        // Again: still fine, and a widened one is narrowed back.
        if (hasPosixPermissions()) Files.setPosixFilePermissions(r, PosixFilePermissions.fromString("rwxrwxrwx"))
        ensurePrivateDir(r)
        if (hasPosixPermissions()) {
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(r, LinkOption.NOFOLLOW_LINKS))
        }
    }

    @Test
    fun `a root of another user is refused`() {
        val r = Files.createDirectories(tmp.resolve("theirs"))
        val e = assertFailsWith<IOException> { ensurePrivateDir(r, posix = true) { false } }
        assertEquals("The cache folder $r belongs to another user; not using it", e.message)
    }

    @Test
    fun `a root that is a link is refused`() {
        val target = Files.createDirectories(tmp.resolve("elsewhere"))
        val link = runCatching { Files.createSymbolicLink(tmp.resolve("link"), target) }.getOrNull() ?: return
        val e = assertFailsWith<IOException> { ensurePrivateDir(link) }
        assertEquals("The cache folder $link is a link; not using it", e.message)
    }

    @Test
    fun `ownership does not depend on a passwd entry for user_name`() {
        assumePosix()
        val r = Files.createDirectories(tmp.resolve("mine"))
        val saved = System.getProperty("user.name")
        System.setProperty("user.name", "no-such-user-svw")
        try {
            assertTrue(ownedByCurrentUser(r))
            assertEquals(r, ensurePrivateDir(r))
        } finally {
            System.setProperty("user.name", saved)
        }
    }

    @Test
    fun `a root owned by someone else is not mine`() {
        assumePosix()
        // The filesystem root is owned by root (or the system), never by the account running the tests.
        if (Files.getOwner(Paths.get("/")) == Files.getOwner(Files.createTempFile(tmp, "p", null))) return
        assertFalse(ownedByCurrentUser(Paths.get("/")))
    }

    @Test
    fun `a root that is a file is refused`() {
        val f = Files.writeString(tmp.resolve("file"), "x")
        assertFailsWith<IOException> { ensurePrivateDir(f) }
    }
}
