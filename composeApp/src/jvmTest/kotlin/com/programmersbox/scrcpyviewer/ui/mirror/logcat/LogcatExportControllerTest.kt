package com.programmersbox.scrcpyviewer.ui.mirror.logcat

import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.logcat.ExportDevice
import com.programmersbox.scrcpyviewer.logcat.ExportFormat
import com.programmersbox.scrcpyviewer.logcat.ExportRow
import com.programmersbox.scrcpyviewer.logcat.LogLevel
import com.programmersbox.scrcpyviewer.logcat.LogcatLine
import com.programmersbox.scrcpyviewer.logcat.LogcatSource
import com.programmersbox.scrcpyviewer.logcat.writeThreadtime
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.io.path.createTempDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue

private val device = ExportDevice(
    "R5CX12GDWPZ", "samsung", "SM-A156U1", "16", 36, "HANDHELD",
    LocalDate.of(2026, 11, 16), ZoneOffset.ofHours(-7),
)

private class ExportFakeSource : LogcatSource(object : AdbClient(adbPath = { error("no adb") }) {}, "SER") {
    val stream = Channel<LogcatLine>(Channel.UNLIMITED)

    override fun lines(since: String?): Flow<LogcatLine> = stream.consumeAsFlow()

    override suspend fun processes(): Map<Int, String> = emptyMap()

    override suspend fun exportDevice(hostNow: ZonedDateTime): ExportDevice = device
}

private fun line(i: Int, level: LogLevel = LogLevel.Info) =
    LogcatLine("11-16", "06:46:01.%03d".format(i % 1000), 100, 100, level, "Tag", "msg $i")

@OptIn(ExperimentalCoroutinesApi::class)
class LogcatExportControllerTest {
    private val dir: Path = createTempDirectory("export-test")

    @AfterTest
    fun cleanup() {
        dir.toFile().walkBottomUp().forEach {
            it.setWritable(true)
            it.delete()
        }
    }

    private class Env(val controller: LogcatController, val source: ExportFakeSource, val toasts: MutableList<String>)

    private fun TestScope.env(): Env {
        val source = ExportFakeSource()
        val toasts = mutableListOf<String>()
        val controller = LogcatController(
            source = source,
            scope = backgroundScope,
            notify = { toasts += it },
            clock = { testScheduler.currentTime },
            io = StandardTestDispatcher(testScheduler),
            now = { LocalDateTime.of(2026, 11, 16, 6, 46, 0) },
        )
        return Env(controller, source, toasts)
    }

    private fun TestScope.feed(env: Env, count: Int, levelOf: (Int) -> LogLevel = { LogLevel.Info }) {
        if (env.controller.run != LogcatRun.Running) env.controller.play()
        for (i in 0 until count) env.source.stream.trySend(line(i, levelOf(i)))
        testScheduler.advanceTimeBy(100)
        testScheduler.runCurrent()
    }

    @Test
    fun `exports every line regardless of filter`() = runTest {
        val e = env()
        feed(e, 3) { if (it == 1) LogLevel.Error else LogLevel.Info }
        e.controller.query = "level:error"
        val target = dir.resolve("out.logcat")
        e.controller.export(ExportFormat.Studio) { _, _ -> target }
        runCurrent()
        assertIs<LogcatExport.Running>(e.controller.export)
        advanceTimeBy(400)
        runCurrent()
        assertEquals(LogcatExport.Done(target, 3), e.controller.export)
        val root = Json.parseToJsonElement(target.readText()).jsonObject
        assertEquals(3, root["logcatMessages"]!!.jsonArray.size)
        assertEquals("level:error", root["metadata"]!!.jsonObject["filter"]!!.jsonPrimitive.content)
        advanceTimeBy(3_600)
        runCurrent()
        assertEquals(LogcatExport.Idle, e.controller.export)
    }

    @Test
    fun `text export writes threadtime`() = runTest {
        val e = env()
        feed(e, 3)
        val target = dir.resolve("out.txt")
        e.controller.export(ExportFormat.Text) { _, _ -> target }
        advanceTimeBy(500)
        runCurrent()
        val rows = e.controller.items.filterIsInstance<LogcatItem.Line>().map { ExportRow(it.entry, it.process) }
        val expected = StringBuilder().also { writeThreadtime(it, rows) {} }.toString()
        assertEquals(expected, target.readText())
    }

    @Test
    fun `suggested name passed to picker`() = runTest {
        val e = env()
        feed(e, 1)
        val asked = mutableListOf<Pair<String, String>>()
        e.controller.export(ExportFormat.Studio) { name, ext ->
            asked += name to ext
            null
        }
        runCurrent()
        assertEquals(listOf("samsung-SM-A156U1-Android-16_2026-11-16_064600.logcat" to "logcat"), asked)
    }

    @Test
    fun `picker cancel stays idle`() = runTest {
        val e = env()
        feed(e, 2)
        e.controller.export(ExportFormat.Studio) { _, _ -> null }
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(LogcatExport.Idle, e.controller.export)
        assertTrue(e.toasts.isEmpty())
        assertTrue(dir.listDirectoryEntries().isEmpty())
    }

    @Test
    fun `lines arriving during export are not included`() = runTest {
        val e = env()
        feed(e, 2)
        val target = dir.resolve("out.logcat")
        e.controller.export(ExportFormat.Studio) { _, _ ->
            e.source.stream.trySend(line(50))
            testScheduler.advanceTimeBy(100)
            target
        }
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(3, e.controller.totalLines)
        val root = Json.parseToJsonElement(target.readText()).jsonObject
        assertEquals(2, root["logcatMessages"]!!.jsonArray.size)
    }

    @Test
    fun `cancel removes partial file and keeps existing target`() = runTest {
        val e = env()
        feed(e, 5_000)
        val target = dir.resolve("out.logcat")
        target.writeText("old")
        var sawPart = false
        e.controller.onExportProgress = {
            sawPart = Files.exists(dir.resolve("out.logcat.part"))
            e.controller.cancelExport()
        }
        e.controller.export(ExportFormat.Studio) { _, _ -> target }
        runCurrent()
        assertTrue(sawPart)
        assertEquals(LogcatExport.Canceled, e.controller.export)
        assertEquals("old", target.readText())
        assertEquals(listOf(target), dir.listDirectoryEntries())
        advanceTimeBy(2_800)
        runCurrent()
        assertEquals(LogcatExport.Idle, e.controller.export)
    }

    @Test
    fun `write failure toasts and cleans up`() = runTest {
        val e = env()
        feed(e, 2)
        val sub = Files.createDirectory(dir.resolve("ro"))
        sub.toFile().setWritable(false)
        assumeTrue(!Files.isWritable(sub))
        e.controller.export(ExportFormat.Studio) { _, _ -> sub.resolve("out.logcat") }
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(e.toasts.single().startsWith("Could not export: "), e.toasts.toString())
        assertEquals(LogcatExport.Idle, e.controller.export)
        sub.toFile().setWritable(true)
        assertTrue(sub.listDirectoryEntries().isEmpty())
    }

    @Test
    fun `no lines does nothing`() = runTest {
        val e = env()
        var asked = false
        e.controller.export(ExportFormat.Studio) { _, _ ->
            asked = true
            null
        }
        runCurrent()
        assertTrue(!asked)
        assertEquals(LogcatExport.Idle, e.controller.export)
    }

    @Test
    fun `running shows at least 400 ms`() = runTest {
        val e = env()
        feed(e, 1)
        val target = dir.resolve("out.logcat")
        e.controller.export(ExportFormat.Studio) { _, _ -> target }
        runCurrent()
        advanceTimeBy(399)
        runCurrent()
        assertIs<LogcatExport.Running>(e.controller.export)
        advanceTimeBy(1)
        runCurrent()
        assertIs<LogcatExport.Done>(e.controller.export)
    }
}
