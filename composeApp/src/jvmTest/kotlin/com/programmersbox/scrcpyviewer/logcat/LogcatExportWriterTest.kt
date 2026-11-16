package com.programmersbox.scrcpyviewer.logcat

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogcatExportWriterTest {
    private val device = ExportDevice(
        "R5CX12GDWPZ", "samsung", "SM-A156U1", "16", 36, "HANDHELD",
        LocalDate.of(2026, 11, 16), ZoneOffset.ofHours(-7),
    )

    private fun line(
        message: String = "hello",
        level: LogLevel = LogLevel.Info,
        pid: Int = 1091,
        time: String = "06:46:01.112",
    ) = LogcatLine("11-16", time, pid, pid + 1, level, "Tag", message)

    private fun entry(line: LogcatLine, trace: List<String> = emptyList()) =
        LogcatEntry(0, line, 0, trace)

    private suspend fun studio(rows: List<ExportRow>, onProgress: (Int) -> Unit = {}): JsonObject {
        val sb = StringBuilder()
        writeStudioLogcat(sb, rows, device, "package:mine level:info", listOf("com.example.app"), onProgress)
        return Json.parseToJsonElement(sb.toString()).jsonObject
    }

    private fun messages(root: JsonObject): JsonArray = root["logcatMessages"]!!.jsonArray

    @Test
    fun `studio json matches schema`() = runTest {
        val l1 = line(pid = 1091)
        val l2 = line(pid = 77, time = "06:46:02.500")
        val root = studio(
            listOf(
                ExportRow(entry(l1), "com.example.app:remote"),
                ExportRow(entry(l2), null),
            ),
        )
        val meta = root["metadata"]!!.jsonObject
        val phys = meta["device"]!!.jsonObject["physicalDevice"]!!.jsonObject
        assertEquals("R5CX12GDWPZ", phys["serialNumber"]!!.jsonPrimitive.content)
        assertEquals(36, phys["apiLevel"]!!.jsonObject["majorVersion"]!!.jsonPrimitive.int)
        assertEquals(0, phys["apiLevel"]!!.jsonObject["minorVersion"]!!.jsonPrimitive.int)
        assertEquals(36, phys["featureLevel"]!!.jsonPrimitive.int)
        assertEquals("HANDHELD", phys["type"]!!.jsonPrimitive.content)
        assertEquals("package:mine level:info", meta["filter"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("com.example.app"),
            meta["projectApplicationIds"]!!.jsonArray.map { it.jsonPrimitive.content },
        )

        val msgs = messages(root)
        val h1 = msgs[0].jsonObject["header"]!!.jsonObject
        assertEquals("com.example.app", h1["applicationId"]!!.jsonPrimitive.content)
        assertEquals("com.example.app:remote", h1["processName"]!!.jsonPrimitive.content)
        assertEquals("INFO", h1["logLevel"]!!.jsonPrimitive.content)
        val inst = epochOf(l1, device.today, device.offset)
        val ts = h1["timestamp"]!!.jsonObject
        assertEquals(inst.epochSecond, ts["seconds"]!!.jsonPrimitive.long)
        assertEquals(112_000_000L, ts["nanos"]!!.jsonPrimitive.long)

        val h2 = msgs[1].jsonObject["header"]!!.jsonObject
        assertEquals("pid-77", h2["applicationId"]!!.jsonPrimitive.content)
        assertEquals("pid-77", h2["processName"]!!.jsonPrimitive.content)
        assertEquals(500_000_000L, h2["timestamp"]!!.jsonObject["nanos"]!!.jsonPrimitive.long)
    }

    @Test
    fun `studio json keeps odd text`() = runTest {
        val odd = "he said \"hi\" \\ tab\there 🎉"
        val root = studio(listOf(ExportRow(entry(line(odd)), "p"), ExportRow(entry(line("")), "p")))
        val msgs = messages(root)
        assertEquals(odd, msgs[0].jsonObject["message"]!!.jsonPrimitive.content)
        assertEquals("", msgs[1].jsonObject["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun `studio json folds trace into message`() = runTest {
        val e = entry(line("boom"), listOf("\tat a.B.c(B.kt:1)", "Caused by: x"))
        val root = studio(listOf(ExportRow(e, "p")))
        assertEquals(
            "boom\n\tat a.B.c(B.kt:1)\nCaused by: x",
            messages(root)[0].jsonObject["message"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `assert level`() = runTest {
        val root = studio(listOf(ExportRow(entry(line(level = LogLevel.Assert)), "p")))
        assertEquals(
            "ASSERT",
            messages(root)[0].jsonObject["header"]!!.jsonObject["logLevel"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `threadtime lines`() = runTest {
        val l = LogcatLine("11-16", "06:46:01.112", 4211, 4238, LogLevel.Warn, "Img", "miss")
        val sb = StringBuilder()
        writeThreadtime(sb, listOf(ExportRow(entry(l, listOf("  at x")), "p"))) {}
        assertEquals(
            "11-16 06:46:01.112  4211  4238 W Img     : miss\n11-16 06:46:01.112  4211  4238 W Img     :   at x\n",
            sb.toString(),
        )
    }

    @Test
    fun `threadtime round trips through parser`() = runTest {
        val lines = listOf(
            line("one"),
            line("", LogLevel.Error, 5),
            line("with : colon", LogLevel.Debug, 99999),
        )
        val sb = StringBuilder()
        writeThreadtime(sb, lines.map { ExportRow(entry(it), null) }) {}
        assertEquals(lines, sb.lines().filter { it.isNotEmpty() }.map { parseThreadtime(it) })
    }

    @Test
    fun `threadtime digits are ascii in any locale`() = runTest {
        val lines = listOf(line("one", pid = 4211), line("two", LogLevel.Warn, 99999))
        val default = Locale.getDefault()
        val sb = StringBuilder()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar"))
            writeThreadtime(sb, lines.map { ExportRow(entry(it), null) }) {}
        } finally {
            Locale.setDefault(default)
        }
        assertTrue(sb.all { it.code < 128 }, sb.toString())
        assertEquals(lines, sb.lines().filter { it.isNotEmpty() }.map { parseThreadtime(it) })
    }

    @Test
    fun `progress reports every 500 and the total`() = runTest {
        val rows = List(1200) { ExportRow(entry(line("m$it")), "p") }
        val studioCalls = mutableListOf<Int>()
        studio(rows) { studioCalls += it }
        assertEquals(listOf(500, 1000, 1200), studioCalls)
        val textCalls = mutableListOf<Int>()
        writeThreadtime(StringBuilder(), rows) { textCalls += it }
        assertEquals(listOf(500, 1000, 1200), textCalls)
    }
}
