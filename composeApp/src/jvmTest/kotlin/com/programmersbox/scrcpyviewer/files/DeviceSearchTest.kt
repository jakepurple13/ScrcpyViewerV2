package com.programmersbox.scrcpyviewer.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceSearchTest {
    @Test
    fun `command prunes Android data and obb, and hidden entries unless asked`() {
        val plain = searchCommand("screen", includeHidden = false)
        assertEquals(
            "cd '/sdcard' && find . \\( -path './Android/data' -o -path './Android/obb' -o -path '*/.*' \\) -prune -o " +
                "-iname '*screen*' -exec stat -L -c '%F|%s|%Y|%n|__svend__' {} + 2>/dev/null",
            plain,
        )
        val hidden = searchCommand("screen", includeHidden = true)
        assertFalse("'*/.*'" in hidden)
        assertTrue("-path './Android/obb'" in hidden)
    }

    @Test
    fun `glob characters and quotes in the query are literal`() {
        assertEquals("a\\*b\\?c\\[d\\]\\\\", globLiteral("a*b?c[d]\\"))
        assertTrue("-iname '*it'\\''s*'" in searchCommand("it's", includeHidden = false))
    }

    @Test
    fun `parser turns records into absolute folders and names`() {
        val p = SearchParser()
        val hits = p.feed(
            "directory|3452|1790280167|./DCIM/Screenshots|__svend__\n" +
                "regular file|1093920|1790280166|./DCIM/Screenshots/Screenshot_1 Gabb Maps.jpg|__svend__\n" +
                "regular file|5|10|./top.txt|__svend__\n",
        )
        assertEquals(
            listOf(
                SearchHit("/sdcard/DCIM", DeviceEntry("Screenshots", true, 3452, 1_790_280_167_000)),
                SearchHit("/sdcard/DCIM/Screenshots", DeviceEntry("Screenshot_1 Gabb Maps.jpg", false, 1_093_920, 1_790_280_166_000)),
                SearchHit("/sdcard", DeviceEntry("top.txt", false, 5, 10_000)),
            ),
            hits,
        )
        assertEquals("/sdcard/DCIM/Screenshots/Screenshot_1 Gabb Maps.jpg", hits[1].path)
    }

    @Test
    fun `a record cut between chunks waits for the rest`() {
        val p = SearchParser()
        assertEquals(emptyList(), p.feed("regular file|5|10|./Down"))
        assertEquals(emptyList(), p.feed("load/a|b.txt|__sv"))
        val hits = p.feed("end__\nregular file|1|2|./c.txt|__svend__")
        assertEquals(listOf("a|b.txt"), hits.map { it.entry.name }, "a pipe in a name stays in the name")
        assertEquals("/sdcard/Download", hits.single().dir)
        assertEquals(listOf("c.txt"), p.finish().map { it.entry.name }, "the last record may lack its newline")
    }

    @Test
    fun `the root itself and broken records are dropped`() {
        val p = SearchParser()
        val hits = p.feed("directory|0|0|.|__svend__\nnoise|__svend__\nregular file|x|1|./a|__svend__\n")
        assertEquals(emptyList(), hits)
    }
}
