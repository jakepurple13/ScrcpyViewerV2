package com.programmersbox.scrcpyviewer.files

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
        var l: Listing? = null
        val ms = measureTimeMillis { l = parseListing(sb.toString(), 0) }
        assertEquals(5000, entries(l!!).size)
        assertEquals("file 4999.jpg", entries(l!!).last().name)
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
    fun freeSpace() {
        assertEquals("df -k /sdcard", freeSpaceCommand())
        assertEquals(12_500_000L * 1024, parseFreeSpace(res("df-android8.txt")))
        assertEquals(72_091_392L * 1024, parseFreeSpace(res("df-android15.txt")))
        assertEquals(93_083_004L * 1024, parseFreeSpace(res("df-android14-samsung.txt")))
        assertNull(parseFreeSpace("df: /sdcard: No such file"))
        assertNull(parseFreeSpace(""))
    }
}
