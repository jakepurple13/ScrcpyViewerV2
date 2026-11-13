package com.programmersbox.scrcpyviewer.logcat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QuerySuggestionsTest {
    private val ctx = SuggestionContext(
        tags = mapOf("ActivityManager" to 5, "My Tag" to 9, "Net" to 2),
        packages = setOf("com.a.app", "com.b.app"),
        processes = setOf("com.a.app:svc"),
        mine = "com.a.app",
    )

    private fun s(text: String, c: SuggestionContext = ctx) = suggest(text, text.length, c)

    @Test
    fun `empty token lists all keys with descriptions`() {
        val r = s("")
        assertEquals(7, r.size)
        assertEquals("tag:", r[0].insert)
        assertEquals(LogcatQuery.KEYS["tag"], r[0].description)
    }

    @Test
    fun `prefix filters keys and keeps the minus`() {
        val r = s("-ta")
        assertEquals(listOf("-tag:"), r.map { it.insert })
        assertEquals(listOf("-tag:"), r.map { it.label })
    }

    @Test
    fun `key prefix is case insensitive`() {
        assertEquals(listOf("tag:"), s("TA").map { it.insert })
        assertEquals(listOf("-tag:"), s("-Ta").map { it.insert })
    }

    @Test
    fun `after level lists levels`() {
        val r = s("level:")
        assertEquals(LogLevel.entries.map { "level:${it.word} " }, r.map { it.insert })
        assertTrue(r.all { it.insert.endsWith(" ") })
        assertEquals(listOf("level:warn "), s("level:wa").map { it.insert })
    }

    @Test
    fun `after package lists mine first with the pick`() {
        val r = s("package:")
        assertEquals("package:mine ", r[0].insert)
        assertEquals("picked in Apps: com.a.app", r[0].description)
        assertEquals("", r[1].description)
        assertEquals("Pick an app in Apps", s("package:", ctx.copy(mine = null))[0].description)
    }

    @Test
    fun `after tag lists tags by line count and quotes spaces`() {
        val r = s("tag:")
        assertEquals(listOf("My Tag", "ActivityManager", "Net"), r.map { it.label.removePrefix("tag:") })
        assertEquals("9 lines", r[0].description)
        assertEquals("tag:\"My Tag\" ", r[0].insert)
    }

    @Test
    fun `nothing for a full value or unknown key`() {
        assertEquals(emptyList(), s("tag:Net"))
        assertEquals(emptyList(), s("zzz:"))
        assertEquals(emptyList(), s("zz"))
    }

    @Test
    fun `is age and process values`() {
        assertEquals(listOf("is:crash ", "is:stacktrace "), s("is:").map { it.insert })
        assertEquals(listOf("age:30s ", "age:5m ", "age:1h ", "age:1d "), s("age:").map { it.insert })
        assertEquals(listOf("process:com.a.app:svc "), s("process:").map { it.insert })
    }

    @Test
    fun `caps at seven and is case insensitive`() {
        val many = ctx.copy(tags = (1..10).associate { "T$it" to it })
        assertEquals(7, s("tag:", many).size)
        assertEquals(1, s("level:WA").size)
    }

    @Test
    fun `applyTo replaces only the caret token`() {
        val text = "level:warn ta"
        val sug = suggest(text, text.length, ctx).single()
        assertEquals("level:warn tag:" to "level:warn tag:".length, sug.applyTo(text))

        val mid = "level:warn ta g:x"
        val m = suggest(mid, 13, ctx).single()
        assertEquals("level:warn tag: g:x" to 15, m.applyTo(mid))
    }

    @Test
    fun `accepting a key with the caret inside the token keeps its value`() {
        fun accept(text: String, caret: Int, label: String = "tag:") =
            suggest(text, caret, ctx).first { it.label == label }.applyTo(text)
        assertEquals("tag:x" to 4, accept("tag:x", 2)) // ta|g:x
        assertEquals("tag:x level:warn" to 4, accept("tag:x level:warn", 1))
        assertEquals("-tag~:Ok" to 6, accept("-tag~:Ok", 3, "-tag:")) // -ta|g~:Ok keeps the regex op
        assertEquals("tag: rest" to 4, accept("tagz rest", 2)) // no colon: the whole token goes
    }

    @Test
    fun `accepting a value with the caret inside the token replaces the rest of it`() {
        fun accept(text: String, caret: Int, label: String) =
            suggest(text, caret, ctx).first { it.label == label }.applyTo(text)
        assertEquals("level:warn " to 11, accept("level:wa", 7, "level:warn")) // level:w|a
        assertEquals("level:warn tag:x" to 11, accept("level:wa tag:x", 7, "level:warn"))
        assertEquals("level:error " to 12, accept("level:erorr", 8, "level:error")) // level:er|orr
    }
}
