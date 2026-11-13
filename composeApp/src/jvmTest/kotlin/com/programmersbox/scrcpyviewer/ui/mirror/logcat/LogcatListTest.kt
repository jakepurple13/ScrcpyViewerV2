package com.programmersbox.scrcpyviewer.ui.mirror.logcat

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.logcat.LogLevel
import com.programmersbox.scrcpyviewer.logcat.LogcatEntry
import com.programmersbox.scrcpyviewer.logcat.LogcatLine
import com.programmersbox.scrcpyviewer.logcat.LogcatSource
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.common.plainText
import com.programmersbox.scrcpyviewer.ui.onEdt
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler

private class ListSource : LogcatSource(object : AdbClient(adbPath = { error("no adb") }) {}, "SER") {
    val streams = mutableListOf<Channel<LogcatLine>>()
    val stream get() = streams.last()

    override fun lines(since: String?): Flow<LogcatLine> {
        val ch = Channel<LogcatLine>(Channel.UNLIMITED)
        streams += ch
        return ch.consumeAsFlow()
    }

    override suspend fun processes(): Map<Int, String> = mapOf(1234 to "com.example.notes")
}

/** Records what is put on the clipboard. */
@OptIn(ExperimentalComposeUiApi::class)
private class FakeClipboard : Clipboard {
    val texts = mutableListOf<String>()
    override suspend fun getClipEntry(): ClipEntry? = null
    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        clipEntry?.plainText()?.let { texts += it }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override val nativeClipboard: Any get() = this
}

private fun line(
    message: String,
    level: LogLevel = LogLevel.Info,
    tag: String = "OkHttp",
    time: String = "10:00:01.234",
    pid: Int = 1234,
    tid: Int = 1250,
) = LogcatLine("11-13", time, pid, tid, level, tag, message)

private const val CLOCK = 1_731_500_000_000L

/** The Logcat tab's list: start card, columns, traces, tag clicks, following, banners. */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class LogcatListTest {
    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(StandardTestDispatcher(scheduler))
    private val source = ListSource()
    private val toasts = mutableListOf<String>()
    private val controller = LogcatController(source, scope, { toasts += it }, clock = { CLOCK }, io = EmptyCoroutineContext)
    private val clipboard = FakeClipboard()

    private fun withScene(width: Int = 900, height: Int = 600, sheetWidth: Dp = width.dp, block: SceneDriver.() -> Unit) {
        val driver = SceneDriver(
            ImageComposeScene(width, height, Density(1f)) {
                ScrcpyViewerTheme(ThemeMode.Light) {
                    CompositionLocalProvider(LocalClipboard provides clipboard) {
                        Surface(Modifier.fillMaxSize()) { LogcatTab(controller, sheetWidth, motion = true) }
                    }
                }
            },
        ).also { it.frames(5) }
        try {
            driver.block()
        } finally {
            driver.scene.close()
            scope.cancel()
        }
    }

    private fun settle() {
        repeat(3) {
            scheduler.advanceTimeBy(100)
            scheduler.runCurrent()
        }
    }

    private fun SceneDriver.started() {
        controller.play()
        settle()
        frames(30)
    }

    private fun SceneDriver.send(vararg lines: LogcatLine) {
        lines.forEach { source.stream.trySend(it) }
        settle()
        frames(30)
    }

    private fun SceneDriver.nodes(): List<SemanticsNode> {
        val all = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) {
            all += node
            node.children.forEach(::walk)
        }
        scene.semanticsOwners.forEach { walk(it.unmergedRootSemanticsNode) }
        return all
    }

    private fun SceneDriver.texts(): List<String> = nodes().flatMap { node ->
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
    }

    private fun SceneDriver.text(t: String): SemanticsNode? =
        nodes().firstOrNull { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == t } }

    private fun SceneDriver.containing(t: String): SemanticsNode? =
        nodes().firstOrNull { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { t in it.text } }

    private fun SceneDriver.described(d: String): List<SemanticsNode> =
        nodes().filter { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(d) == true }

    private fun SceneDriver.click(node: SemanticsNode, alt: Boolean = false) = clickAt(node.boundsInRoot.center, alt)

    private fun SceneDriver.clickAt(at: Offset, alt: Boolean = false) {
        val mods = PointerKeyboardModifiers(isAltPressed = alt)
        scene.sendPointerEvent(PointerEventType.Move, at, type = PointerType.Mouse, keyboardModifiers = mods)
        scene.sendPointerEvent(PointerEventType.Press, at, type = PointerType.Mouse, button = PointerButton.Primary, keyboardModifiers = mods)
        scene.sendPointerEvent(PointerEventType.Release, at, type = PointerType.Mouse, button = PointerButton.Primary, keyboardModifiers = mods)
        frames(5)
        settle()
        frames(5)
    }

    private fun hostTime(): String =
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(CLOCK))

    @Test
    fun `start card shows before play and leaves after`() = onEdt {
        withScene {
            assertNotNull(text("Logcat isn't running"))
            assertNotNull(text("Press Play to read this device's log. It stops when you leave this tab, unless Logcat is in its own window."))
            // The big Play is the card's; the toolbar's comes first.
            val big = described("Start logcat").last()
            assertTrue(big.boundsInRoot.width >= 50f, "big Play is 56 dp")
            click(big)
            assertEquals(LogcatRun.Running, controller.run)
            frames(30)
            assertNull(text("Logcat isn't running"))
        }
    }

    @Test
    fun `rows show time, pid-tid, tag, package, level, message`() = onEdt {
        withScene {
            started()
            send(line("Sync request started"))
            for (h in listOf("TIME", "PID-TID", "TAG", "PACKAGE", "MESSAGE")) assertNotNull(text(h), h)
            assertNotNull(text("10:00:01.234"))
            assertNotNull(text("1234-1250"))
            assertNotNull(text("OkHttp"))
            assertNotNull(text("com.example.notes"))
            assertNotNull(text("I"))
            assertNotNull(text("Sync request started"))
        }
    }

    @Test
    fun `compact hides pid-tid and package and drops millis`() = onEdt {
        withScene {
            started()
            send(line("Sync request started"))
            controller.compact = true
            frames(30)
            assertNull(text("1234-1250"))
            assertNull(text("com.example.notes"))
            assertNull(text("PID-TID"))
            assertNull(text("PACKAGE"))
            assertNull(text("10:00:01.234"))
            assertNotNull(text("10:00:01"))
            assertNotNull(text("OkHttp"))
            assertNotNull(text("TIME"))
        }
    }

    @Test
    fun `under 600 dp hides pid-tid and package`() = onEdt {
        withScene(width = 560) {
            started()
            send(line("Sync request started"))
            assertNull(text("1234-1250"))
            assertNull(text("com.example.notes"))
            assertNull(text("PID-TID"))
            assertNull(text("PACKAGE"))
            assertNotNull(text("10:00:01.234"), "millis stay outside compact")
        }
    }

    private val crash = arrayOf(
        line("FATAL EXCEPTION: main", LogLevel.Error, "AndroidRuntime"),
        line("Process: com.example.notes, PID: 1234", LogLevel.Error, "AndroidRuntime"),
        line("java.lang.IllegalStateException: boom", LogLevel.Error, "AndroidRuntime"),
        line("\tat com.example.notes.NotesRepo.load(NotesRepo.kt:118)", LogLevel.Error, "AndroidRuntime"),
        line("\tat android.os.Handler.dispatchMessage(Handler.java:99)", LogLevel.Error, "AndroidRuntime"),
    )

    @Test
    fun `crash trace starts unfolded, other traces fold, clicking N lines toggles`() = onEdt {
        withScene {
            started()
            send(
                *crash,
                line("Sync failed", LogLevel.Warn, "SyncWorker", tid = 1300),
                line("java.net.SocketTimeoutException: timeout", LogLevel.Warn, "SyncWorker", tid = 1300),
                line("\tat okhttp3.Call.execute(Call.kt:42)", LogLevel.Warn, "SyncWorker", tid = 1300),
            )
            assertNotNull(text("4 lines"))
            assertNotNull(text("2 lines"))
            assertNotNull(containing("IllegalStateException: boom"), "crash unfolded")
            assertNull(containing("SocketTimeoutException"), "other folded")
            click(checkNotNull(text("2 lines")))
            frames(30)
            assertNotNull(containing("SocketTimeoutException"))
            click(checkNotNull(text("4 lines")))
            frames(30)
            assertNull(containing("IllegalStateException: boom"))
        }
    }

    @Test
    fun `clicking a tag adds tag-x and toasts, alt-click adds -tag-x`() = onEdt {
        withScene {
            started()
            send(line("Sync request started"))
            click(checkNotNull(text("OkHttp")))
            assertEquals("tag:OkHttp", controller.query)
            assertTrue("Filtering to OkHttp" in toasts, "$toasts")
            frames(30)
            click(checkNotNull(text("com.example.notes")), alt = true)
            assertEquals("tag:OkHttp -package:com.example.notes", controller.query)
            assertTrue("Hiding com.example.notes" in toasts, "$toasts")
        }
    }

    /** Click targets inside the trace text: the own-frame links. */
    private fun SceneDriver.linksIn(trace: SemanticsNode): List<SemanticsNode> {
        val box: Rect = trace.boundsInRoot
        return nodes().filter { n ->
            n.id != trace.id && n.config.getOrNull(SemanticsActions.OnClick) != null &&
                n.config.getOrNull(SemanticsProperties.Text) == null &&
                box.contains(n.boundsInRoot.center)
        }
    }

    @Test
    fun `own frame click copies File-kt-118`() = onEdt {
        withScene {
            started()
            send(*crash)
            val before = checkNotNull(containing("NotesRepo.kt:118"))
            assertTrue(linksIn(before).isEmpty(), "no picked app, no links")
            controller.pick("com.example.notes")
            controller.query = ""
            frames(30)
            val trace = checkNotNull(containing("NotesRepo.kt:118"))
            val links = linksIn(trace)
            assertEquals(1, links.size, "only the app's own frame")
            click(links.single())
            assertEquals(listOf("NotesRepo.kt:118"), clipboard.texts)
            assertTrue("Copied NotesRepo.kt:118" in toasts, "$toasts")
        }
    }

    @Test
    fun `pinned list follows new lines, scrolled up shows 3 new lines pill, clicking it scrolls to the end`() = onEdt {
        withScene {
            started()
            send(*Array(80) { line("line $it") })
            frames(30)
            assertNotNull(text("line 79"), "pinned: follows to the end")
            assertNull(text("line 0"))
            // Wheel up over the list.
            repeat(10) {
                scene.sendPointerEvent(PointerEventType.Scroll, Offset(450f, 300f), scrollDelta = Offset(0f, -10f), type = PointerType.Mouse)
                frame()
            }
            frames(30)
            assertNull(text("line 79"), "scrolled up")
            send(line("new a"), line("new b"), line("new c"))
            assertNull(text("new c"), "stays put")
            val pill = checkNotNull(text("3 new lines"))
            click(pill)
            frames(60)
            assertNotNull(text("new c"))
            assertNull(text("3 new lines"))
            // Pinned again: the next line is followed.
            send(line("new d"))
            frames(30)
            assertNotNull(text("new d"))
            assertNull(text("1 new line"))
        }
    }

    @Test
    fun `paused banner text for Pause and for leaving`() = onEdt {
        withScene {
            started()
            assertNull(containing("Paused at"))
            controller.pause()
            frames(30)
            assertNotNull(text("Paused at ${hostTime()}"))
            controller.play()
            settle()
            frames(30)
            assertNull(containing("Paused at"))
            controller.stopForLeaving()
            frames(30)
            assertNotNull(text("Stopped when you left the tab · Resume to catch up"))
        }
    }

    @Test
    fun `gap divider text`() = onEdt {
        withScene {
            started()
            send(line("before"))
            controller.pause()
            frames(5)
            controller.play()
            settle()
            send(line("after", time = "10:00:05.000"))
            val t = hostTime()
            assertNotNull(text("Paused $t – $t"))
        }
    }

    @Test
    fun `empty filter result shows the empty message`() = onEdt {
        withScene {
            started()
            send(line("Sync request started"))
            assertNull(text("No lines match this filter yet."))
            controller.query = "nomatchatall"
            frames(30)
            assertNotNull(text("No lines match this filter yet."))
            assertNotNull(text("New lines that match appear here as they arrive."))
        }
    }

    private fun entryLine(seq: Long, isCrash: Boolean = false) = LogcatItem.Line(
        LogcatEntry(seq, if (isCrash) this.crash[0] else line("m$seq"), 0L),
        null,
    )

    @Test
    fun `live rows grow only for small batches after the first look, crashes flash once`() {
        val live = LiveRows()
        live.observe(List(1000) { entryLine(it.toLong()) })
        assertTrue(live.fresh.isEmpty(), "initial load doesn't grow")
        live.observe(List(1000) { entryLine(it.toLong()) } + entryLine(1000) + entryLine(1001, isCrash = true))
        assertEquals(setOf(1000L, 1001L), live.fresh)
        assertTrue(live.flashOnce(1001L))
        assertFalse(live.flashOnce(1001L), "once per key")
        // A flood (the -T 1000 backlog or a resume) doesn't grow.
        live.observe(List(1100) { entryLine(it.toLong()) })
        assertTrue(live.fresh.isEmpty())
        // Old keys coming back (a filter flip) never grow.
        live.observe(List(10) { entryLine(it.toLong()) })
        assertTrue(live.fresh.isEmpty())
    }
}
