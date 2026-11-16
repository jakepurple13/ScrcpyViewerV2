package com.programmersbox.scrcpyviewer.files

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceListingTest {
    private fun res(name: String) =
        javaClass.getResourceAsStream("/listing/$name")!!.readBytes().toString(Charsets.UTF_8)

    private fun entries(l: Listing) = (l as Listing.Entries).entries

    @Test
    fun android8() {
        val e = entries(parseListing(res("android8.txt"), 0))
        assertEquals(
            listOf(
                DeviceEntry("DCIM", true, 4096, 1_700_000_000_000),
                DeviceEntry("notes.txt", false, 214_337, 1_699_400_000_000),
                DeviceEntry(".thumbnails", true, 4096, 1_700_000_100_000),
            ),
            e,
        )
        assertTrue(e.last().hidden)
    }

    @Test
    fun android11EmptyFile() {
        val e = entries(parseListing(res("android11.txt"), 0))
        assertEquals(DeviceEntry("clip.mp4", false, 4_312_448, 1_731_090_720_000), e[0])
        assertEquals(DeviceEntry("empty.txt", false, 0, 1_731_090_000_000), e[1])
    }

    @Test
    fun android15BrokenLink() {
        val e = entries(parseListing(res("android15.txt"), 0))
        assertEquals(listOf("Trips", "a.PNG", "dangling"), e.map { it.name })
        assertEquals(listOf(true, false, false), e.map { it.isDir })
        assertEquals(0L, e[2].size)
    }

    @Test
    fun realSamsungFixture() {
        val e = entries(parseListing(res("android14-samsung.txt"), 0))
        assertEquals(7, e.size)
        assertEquals("BigBuckBunny_640x360.m4v.zip", e[0].name)
        assertEquals(121_284_117L, e[0].size)
        assertEquals(true, e[1].isDir)
    }

    @Test
    fun trickyNames() {
        val out = "regular file|5|10|/sdcard/a\nb|c__svend__ d|__svend__\nregular file|1|2|/sdcard/plain|__svend__\n"
        val e = entries(parseListing(out, 0))
        assertEquals(listOf("a\nb|c__svend__ d", "plain"), e.map { it.name })
        assertEquals(5L, e[0].size)
    }

    @Test
    fun statErrorLinesIgnored() {
        val out = "stat: weird error\nregular file|1|2|/sdcard/ok|__svend__\n"
        assertEquals(listOf("ok"), entries(parseListing(out, 0)).map { it.name })
    }

    @Test
    fun blockedAndFailed() {
        assertEquals(Listing.Blocked, parseListing(res("blocked.txt"), 13))
        assertEquals(Listing.Blocked, parseListing("ls: /sdcard/x: Operation not permitted", 13))
        assertEquals(
            Listing.Failed("ls: x: No such file or directory"),
            parseListing("ls: x: No such file or directory", 13),
        )
        assertEquals(Listing.Failed("boom"), parseListing("noise\nboom\n\n", 1))
        assertTrue(parseListing("", null) is Listing.Failed)
    }

    @Test
    fun bigListingFast() {
        val sb = StringBuilder()
        repeat(5000) { sb.append("regular file|$it|1700000000|/sdcard/Big/file $it.jpg|__svend__\n") }
        val text = sb.toString()
        // Warm up once (class loading, JIT), then the best of three: a slow CI box's one-off pause is not the parser.
        val l = parseListing(text, 0)
        assertEquals(5000, entries(l).size)
        assertEquals("file 4999.jpg", entries(l).last().name)
        val ms = (1..3).minOf { measureTimeMillis { parseListing(text, 0) } }
        assertTrue(ms < 200, "took $ms ms")
    }

    @Test
    fun listCommandQuotes() {
        val cmd = listCommand("/sdcard/it's")
        assertTrue("'/sdcard/it'\\''s'" in cmd, cmd)
        assertTrue("/sdcard/it's" !in cmd, cmd)
        assertTrue("exit 13" in cmd && "__svend__" in cmd)
    }

    @Test
    fun `listing records name entries relative to the folder, broken links after the rest`() {
        val out = "directory|3452|1700000000|./Camera|__svend__\n" +
            "regular file|5|1700000000|./.nomedia|__svend__\n" +
            "regular file|7|1700000000|./two\nlines|__svend__\n" +
            "regular file|8|1700000000|./it's ü|__svend__\n" +
            "regular empty file|0|1700000000|./-x|__svend__\n" +
            "broken|0|0|./dangling|__svend__\n"
        val e = entries(parseListing(out, 0))
        assertEquals(listOf("Camera", ".nomedia", "two\nlines", "it's ü", "-x", "dangling"), e.map { it.name })
        assertEquals(listOf(true, false, false, false, false, false), e.map { it.isDir })
        assertEquals(DeviceEntry("dangling", false, 0, 0), e.last())
    }

    /**
     * Runs [listCommand] in the host shell over [dir]. It needs GNU or toybox `stat -c`: a Mac without coreutils'
     * `gstat` skips it (null). [findWorks] false puts a `find` that always fails first on the PATH, for the old-device
     * loop. [folder] is made only after the Windows check: names like "two\nlines" are invalid there.
     */
    private fun runListOnHost(findWorks: Boolean = true, folder: () -> Path): Pair<String, Int>? {
        if (System.getProperty("os.name").startsWith("Windows")) return null
        val dir = folder()
        val shim = Files.createTempDirectory("svw-shim")
        val native = runCatching {
            ProcessBuilder("stat", "-c", "%n", "/").redirectErrorStream(true).start().waitFor() == 0
        }.getOrDefault(false)
        if (!native) {
            val gnu = listOf("/opt/homebrew/bin/gstat", "/usr/local/bin/gstat").map { Paths.get(it) }
                .firstOrNull { Files.isExecutable(it) } ?: return null
            Files.createSymbolicLink(shim.resolve("stat"), gnu)
        }
        if (!findWorks) Files.writeString(shim.resolve("find"), "#!/bin/sh\nexit 1\n").toFile().setExecutable(true)
        val pb = ProcessBuilder("/bin/sh", "-c", listCommand(dir.toString()))
        pb.environment()["PATH"] = "$shim:" + System.getenv("PATH")
        val p = pb.start()
        val out = String(p.inputStream.readBytes(), Charsets.UTF_8)
        p.waitFor(60, TimeUnit.SECONDS)
        return out to p.exitValue()
    }

    /** A folder of odd names, a hidden file, a subfolder, a broken link and a working one. */
    private fun oddFolder(): Path {
        val dir = Files.createTempDirectory("svw-list")
        for (name in listOf("a.jpg", "b c.txt", ".hidden", "..dots", "-x", "it's", "uni ü 写真", "two\nlines")) {
            Files.writeString(dir.resolve(name), "12345")
        }
        Files.createDirectory(dir.resolve("Sub dir"))
        Files.createSymbolicLink(dir.resolve("dangling"), dir.resolve("nowhere"))
        Files.createSymbolicLink(dir.resolve("good link"), dir.resolve("a.jpg"))
        return dir
    }

    private fun checkOdd(out: String, exit: Int) {
        assertEquals(0, exit, out)
        val e = entries(parseListing(out, exit)).associateBy { it.name }
        assertEquals(
            setOf("a.jpg", "b c.txt", ".hidden", "..dots", "-x", "it's", "uni ü 写真", "two\nlines", "Sub dir", "dangling", "good link"),
            e.keys,
        )
        assertEquals(11, entries(parseListing(out, exit)).size, "each entry once")
        assertEquals(5L, e.getValue("two\nlines").size)
        assertEquals(5L, e.getValue("good link").size, "a link is listed as what it points to")
        assertTrue(e.getValue("Sub dir").isDir)
        assertEquals(DeviceEntry("dangling", false, 0, 0), e.getValue("dangling"))
        assertTrue(e.getValue("a.jpg").mtimeMillis > 0)
    }

    @Test
    fun `list command on the host shell lists odd names, hidden files and broken links`() {
        val (out, exit) = runListOnHost(folder = ::oddFolder) ?: return
        checkOdd(out, exit)
    }

    @Test
    fun `list command without find -exec falls back to one stat per entry`() {
        val (out, exit) = runListOnHost(findWorks = false, folder = ::oddFolder) ?: return
        checkOdd(out, exit)
    }

    @Test
    fun `list command on the host shell lists a big folder in a few processes`() {
        var started = 0L
        val (out, exit) = runListOnHost {
            Files.createTempDirectory("svw-big").also { dir ->
                repeat(3000) { Files.createFile(dir.resolve("file $it.jpg")) }
                started = System.nanoTime()
            }
        } ?: return
        val ms = (System.nanoTime() - started) / 1_000_000
        assertEquals(0, exit)
        assertEquals(3000, entries(parseListing(out, exit)).size)
        // One stat per file took seconds here; a few processes take well under one.
        assertTrue(ms < 5_000, "took $ms ms")
    }

    @Test
    fun `list command on a missing folder exits 13 with ls's error`() {
        if (System.getProperty("os.name").startsWith("Windows")) return
        val p = ProcessBuilder("/bin/sh", "-c", listCommand("/nonexistent/svw")).redirectErrorStream(true).start()
        val out = String(p.inputStream.readBytes(), Charsets.UTF_8)
        p.waitFor(10, TimeUnit.SECONDS)
        assertEquals(13, p.exitValue())
        assertTrue(parseListing(out, p.exitValue()) is Listing.Failed, out)
    }

    @Test
    fun counts() {
        val cmd = countsCommand("/sdcard", listOf("A b", "it's"))
        assertTrue("'/sdcard'" in cmd && "'A b'" in cmd && "'it'\\''s'" in cmd, cmd)
        assertEquals(
            mapOf("DCIM" to 12, "we|ird" to 0, "x\ny" to 3),
            parseCounts("12|DCIM|__svend__\n0|we|ird|__svend__\n3|x\ny|__svend__\n"),
        )
        assertEquals(mapOf("a" to 1), parseCounts("   1|a|__svend__\n"))
    }

    @Test
    fun commandsAvoidEchoOfNames() {
        for (cmd in listOf(listCommand("/sdcard"), countsCommand("/sdcard", listOf("a")))) {
            assertTrue(!Regex("echo[^;]*\\\$f").containsMatchIn(cmd), cmd)
            assertTrue("printf" in cmd, cmd)
        }
    }

    @Test
    fun countsCommandRoundTripsBackslashNamesOnHostShell() {
        if (System.getProperty("os.name").startsWith("Windows")) return
        val dir = java.nio.file.Files.createTempDirectory("svw-counts")
        val name = "back\\nslash\\tdir"
        java.nio.file.Files.createDirectory(dir.resolve(name))
        java.nio.file.Files.createFile(dir.resolve(name).resolve("x"))
        val p = ProcessBuilder("/bin/sh", "-c", countsCommand(dir.toString(), listOf(name))).start()
        val out = String(p.inputStream.readBytes(), Charsets.UTF_8)
        p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals(mapOf(name to 1), parseCounts(out))
    }

    @Test
    fun countsCommandEmptyIsNoOp() {
        assertEquals(":", countsCommand("/sdcard", emptyList()))
    }

    @Test
    fun freeSpace() {
        assertEquals("df -k /sdcard", freeSpaceCommand())
        assertEquals(DeviceSpace(12_500_000L * 1024, 25_000_000L * 1024), parseFreeSpace(res("df-android8.txt")))
        assertEquals(DeviceSpace(72_091_392L * 1024, 113_091_392L * 1024), parseFreeSpace(res("df-android15.txt")))
        val samsung = DeviceSpace(93_083_004L * 1024, 110_534_640L * 1024)
        assertEquals(samsung, parseFreeSpace(res("df-android14-samsung.txt")))
        assertEquals(0.158f, samsung.usedFraction, 0.001f)
        assertEquals(0f, DeviceSpace(0, 0).usedFraction)
        assertNull(parseFreeSpace("df: /sdcard: No such file"))
        assertNull(parseFreeSpace(""))
    }

    @Test
    fun `tree command lists every file and folder under the names, relative to the folder`() {
        assertEquals(
            "cd '/sdcard/My '\\''Dir' && find './Camera' './-x.jpg' \\( -type d -o -type f \\) " +
                "-exec stat -c '%F|%s|%n|__svend__' {} + 2>/dev/null",
            treeCommand("/sdcard/My 'Dir", listOf("Camera", "-x.jpg")),
        )
    }

    @Test
    fun `tree output keeps folders and regular files, drops links and odd entries`() {
        val out = "directory|3452|./Camera|__svend__\n" +
            "regular file|120|./Camera/1.jpg|__svend__\n" +
            "regular empty file|0|./Camera/empty|__svend__\n" +
            "directory|3452|./Camera/Sub dir|__svend__\n" +
            "regular file|7|./Camera/Sub dir/a|b.txt|__svend__\n" +
            "symbolic link|9|./Camera/link|__svend__\n" +
            "regular file|9|./Camera/two\nlines|__svend__\n" +
            "regular file|nope|./bad|__svend__\n" +
            "regular file|55|./-x.jpg|__svend__\n"
        assertEquals(
            listOf(
                TreeEntry("Camera", isDir = true, size = 0),
                TreeEntry("Camera/1.jpg", isDir = false, size = 120),
                TreeEntry("Camera/empty", isDir = false, size = 0),
                TreeEntry("Camera/Sub dir", isDir = true, size = 0),
                TreeEntry("Camera/Sub dir/a|b.txt", isDir = false, size = 7),
                TreeEntry("Camera/two\nlines", isDir = false, size = 9),
                TreeEntry("-x.jpg", isDir = false, size = 55),
            ),
            parseTree(out),
        )
    }
}
