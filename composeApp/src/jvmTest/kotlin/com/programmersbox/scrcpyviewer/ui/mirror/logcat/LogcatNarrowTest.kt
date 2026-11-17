package com.programmersbox.scrcpyviewer.ui.mirror.logcat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.logcat.LogLevel
import com.programmersbox.scrcpyviewer.logcat.LogcatLine
import com.programmersbox.scrcpyviewer.logcat.LogcatSource
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.common.LocalReduceMotion
import com.programmersbox.scrcpyviewer.ui.common.plainText
import com.programmersbox.scrcpyviewer.ui.onEdt
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
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

private class NarrowSource : LogcatSource(object : AdbClient(adbPath = { error("no adb") }) {}, "SER") {
    val streams = mutableListOf<Channel<LogcatLine>>()
    val stream get() = streams.last()

    override fun lines(since: String?): Flow<LogcatLine> {
        val ch = Channel<LogcatLine>(Channel.UNLIMITED)
        streams += ch
        return ch.consumeAsFlow()
    }

    override suspend fun processes(): Map<Int, String> = mapOf(1234 to "com.example.notes")
}

@OptIn(ExperimentalComposeUiApi::class)
private class NarrowClipboard : Clipboard {
    val texts = mutableListOf<String>()
    override suspend fun getClipEntry(): ClipEntry? = null
    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        clipEntry?.plainText()?.let { texts += it }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override val nativeClipboard: Any get() = this
}

private fun narrowLine(message: String, tag: String = "OkHttp", level: LogLevel = LogLevel.Warn) =
    LogcatLine("11-13", "10:00:01.234", 1234, 1250, level, tag, message)

private const val MESSAGE = "Sync request failed, will retry"

/** The Logcat tab in narrow sheets and windows: the row shapes, the compact toolbar and status bar, the window minimum. */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class LogcatNarrowTest {
    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(StandardTestDispatcher(scheduler))
    private val source = NarrowSource()
    private val controller = LogcatController(source, scope, {}, io = EmptyCoroutineContext)
    private val clipboard = NarrowClipboard()

    /** The tab [width] dp wide (a state, so a test can resize it) in a 720 × 600 scene. */
    private fun scene(width: MutableState<Int>, motion: Boolean): SceneDriver = SceneDriver(
        ImageComposeScene(720, 600, Density(1f)) {
            CompositionLocalProvider(LocalReduceMotion provides !motion, LocalClipboard provides clipboard) {
                ScrcpyViewerTheme(ThemeMode.Light) {
                    Box(Modifier.width(width.value.dp).fillMaxHeight()) {
                        Surface { LogcatTab(controller, width.value.dp, motion = motion) }
                    }
                }
            }
        },
    ).also { it.frames(5) }

    private fun withScene(width: Int, motion: Boolean = false, block: SceneDriver.(MutableState<Int>) -> Unit) {
        val w = mutableStateOf(width)
        val driver = scene(w, motion)
        try {
            driver.block(w)
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

    private fun SceneDriver.started(vararg lines: LogcatLine) {
        controller.play()
        settle()
        frames(30)
        lines.forEach { source.stream.trySend(it) }
        settle()
        frames(60)
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

    private fun SceneDriver.texts(): List<String> = nodes().flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

    private fun SceneDriver.textOrNull(t: String): SemanticsNode? =
        nodes().firstOrNull { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == t } }

    private fun SceneDriver.text(t: String): SemanticsNode = checkNotNull(textOrNull(t)) { "no \"$t\" in ${texts()}" }

    private fun SceneDriver.describedOrNull(d: String): SemanticsNode? =
        nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(d) == true }

    private fun SceneDriver.described(d: String): SemanticsNode = checkNotNull(describedOrNull(d)) { "nothing described \"$d\"" }

    /** Shown: a size, inside [width]. */
    private fun Rect.shownWithin(width: Int) = this.width > 0f && height > 0f && left >= -0.5f && right <= width + 0.5f

    private fun SceneDriver.click(node: SemanticsNode) {
        val at = node.boundsInRoot.center
        scene.sendPointerEvent(PointerEventType.Move, at, type = PointerType.Mouse)
        scene.sendPointerEvent(PointerEventType.Press, at, type = PointerType.Mouse, button = PointerButton.Primary)
        scene.sendPointerEvent(PointerEventType.Release, at, type = PointerType.Mouse, button = PointerButton.Primary)
        frames(20)
    }

    /** How far the message sits below its row's tag (unclipped: mid-glide the message may sit past the row's end). */
    private fun SceneDriver.messageDrop(): Float = text(MESSAGE).positionInRoot.y - text("OkHttp").positionInRoot.y

    @Test
    fun `row shape and toolbar flip exactly at their thresholds`() {
        assertEquals(LogcatRowShape.Full, logcatRowShape(600.dp))
        assertEquals(LogcatRowShape.NoIds, logcatRowShape(599.dp))
        assertEquals(LogcatRowShape.NoIds, logcatRowShape(420.dp))
        assertEquals(LogcatRowShape.Stacked, logcatRowShape(419.dp))
        assertTrue(logcatToolbarCompact(419.dp))
        assertFalse(logcatToolbarCompact(420.dp))
    }

    @Test
    fun `at 280 rows stack, chips wrap, Play is an icon and Wrap folds into the menu`() = onEdt {
        withScene(280) {
            started(narrowLine(MESSAGE))
            val message = text(MESSAGE).boundsInRoot
            val tag = text("OkHttp").boundsInRoot
            assertTrue(message.width >= 200f, "message gets the row: $message")
            assertTrue(message.top >= tag.bottom - 0.5f, "message under its tag: $message $tag")
            assertTrue(message.shownWithin(280), "$message")
            assertNotNull(textOrNull("10:00:01"), "time drops millis")
            assertNull(textOrNull("10:00:01.234"))
            assertTrue(text("Crashes").boundsInRoot.shownWithin(280), "${text("Crashes").boundsInRoot}")
            val pause = described("Pause logcat").boundsInRoot
            assertTrue(pause.width <= 48f && pause.shownWithin(280), "$pause")
            assertTrue(described("Export logs").boundsInRoot.shownWithin(280), "export stays beside Play")
            assertNull(textOrNull("TIME"), "no header when stacked")
            assertTrue(text("1/1").boundsInRoot.shownWithin(280), "counts read shown/total")
            assertNull(describedOrNull("Soft wrap"))
            assertNull(textOrNull("Wrap lines"))
            assertTrue(described("Clear log").boundsInRoot.shownWithin(280), "Clear stays")
            val more = described("More status options")
            assertTrue(more.boundsInRoot.shownWithin(280), "${more.boundsInRoot}")
            click(more)
            click(text("Wrap lines"))
            assertTrue(controller.wrap)
            click(described("More status options"))
            assertNotNull(textOrNull("Compact view"))
        }
    }

    @Test
    fun `at 720 the full row, header and status bar are unchanged`() = onEdt {
        withScene(720) {
            started(narrowLine(MESSAGE))
            for (h in listOf("TIME", "PID-TID", "TAG", "PACKAGE", "MESSAGE")) assertTrue(text(h).boundsInRoot.shownWithin(720), h)
            assertTrue(text("1234-1250").boundsInRoot.shownWithin(720))
            assertTrue(text("10:00:01.234").boundsInRoot.shownWithin(720))
            assertEquals(0f, messageDrop(), "message on the tag's line")
            assertTrue(described("Soft wrap").boundsInRoot.shownWithin(720))
            assertNull(describedOrNull("More status options"))
            assertNotNull(textOrNull("1 of 1 lines"))
            assertTrue(described("Pause logcat").boundsInRoot.width >= 100f)
        }
    }

    @Test
    fun `resizing 720 to 300 glides the message under its tag`() = onEdt {
        withScene(720, motion = true) { width ->
            started(narrowLine(MESSAGE))
            val from = messageDrop()
            val playFrom = described("Pause logcat").boundsInRoot.width
            width.value = 300
            frames(5) // the spring starts on the frame after the resize composes
            val mid = messageDrop()
            val playMid = described("Pause logcat").boundsInRoot.width
            frames(90)
            val to = messageDrop()
            val playTo = described("Pause logcat").boundsInRoot.width
            assertTrue(to > from, "moved down: $from -> $to")
            assertTrue(mid > from && mid < to, "in between one frame in: $from $mid $to")
            assertTrue(playMid < playFrom && playMid > playTo, "Play narrows: $playFrom $playMid $playTo")
        }
    }

    @Test
    fun `with reduced motion a resize snaps`() = onEdt {
        withScene(720, motion = false) { width ->
            started(narrowLine(MESSAGE))
            val from = messageDrop() to described("Pause logcat").boundsInRoot.width
            width.value = 300
            val seen = List(10) {
                frame()
                messageDrop() to described("Pause logcat").boundsInRoot.width
            }
            frames(30)
            val to = messageDrop() to described("Pause logcat").boundsInRoot.width
            assertTrue(to.first > from.first && to.second < from.second, "moved: $from -> $to")
            assertTrue(seen.all { it == from || it == to }, "no frames in between: $from $seen $to")
        }
    }

    @Test
    fun `stacked rows with wrap on select and copy messages only`() = onEdt {
        withScene(300) {
            controller.wrap = true
            started(
                narrowLine("first message that is long enough to wrap onto a second line here", tag = "AlphaTag"),
                narrowLine("second message", tag = "BetaTag"),
            )
            // From the first row's level badge (the tag takes clicks as a filter), across both rows.
            val from = text("W").boundsInRoot.centerLeft + Offset(1f, 0f)
            val to = text("second message").boundsInRoot.centerRight
            val held = PointerButtons(isPrimaryPressed = true)
            scene.sendPointerEvent(PointerEventType.Move, from, type = PointerType.Mouse)
            scene.sendPointerEvent(PointerEventType.Press, from, type = PointerType.Mouse, buttons = held, button = PointerButton.Primary)
            frames(2)
            source.stream.trySend(narrowLine("arrives mid-drag"))
            settle()
            for (i in 1..10) {
                scene.sendPointerEvent(PointerEventType.Move, from + (to - from) * (i / 10f), type = PointerType.Mouse, buttons = held)
                frame()
            }
            scene.sendPointerEvent(PointerEventType.Release, to, type = PointerType.Mouse, button = PointerButton.Primary)
            frames(5)
            for (meta in listOf(true, false)) for (type in listOf(KeyEventType.KeyDown, KeyEventType.KeyUp)) {
                scene.sendKeyEvent(KeyEvent(Key.C, type, isMetaPressed = meta, isCtrlPressed = !meta))
            }
            frames(5)
            settle()
            frames(30)
            val copied = clipboard.texts.lastOrNull().orEmpty()
            assertTrue("second message" in copied, "copied: $copied")
            for (cell in listOf("AlphaTag", "BetaTag", "10:00:01")) assertFalse(cell in copied, "$cell isn't selectable; copied: $copied")
            assertNotNull(textOrNull("arrives mid-drag"), "shown after release")
        }
    }

    /** Paused with a full list, the tab narrows so rows stack: the newest line must still show above the status bar. */
    private fun keepsNewestInView(motion: Boolean) = withScene(720, motion) { width ->
        started(*Array(40) { narrowLine("line $it") })
        controller.pause()
        settle()
        frames(30)
        assertNotNull(textOrNull("line 39"), "newest shown before")
        width.value = 300
        frames(120)
        val newest = checkNotNull(textOrNull("line 39")) { "newest line scrolled out of view: ${texts().filter { it.startsWith("line ") }}" }.boundsInRoot
        val bar = described("Clear log").boundsInRoot
        assertTrue(newest.height > 0f && newest.bottom <= bar.top, "newest above the status bar: $newest $bar")
        assertNull(textOrNull("TIME"), "rows finished stacking")
    }

    @Test
    fun `a resize that stacks rows keeps a pinned list at its newest line`() = onEdt { keepsNewestInView(motion = true) }

    @Test
    fun `with reduced motion a resize that stacks rows keeps a pinned list at its newest line`() = onEdt { keepsNewestInView(motion = false) }

    @Test
    fun `stacking from 500 to 300 glides the time and the row height without a jump`() = onEdt {
        val long = "Sync request failed for the third time, will retry with backoff after the network is back"
        withScene(500, motion = true) { width ->
            started(narrowLine(long), narrowLine("second"))
            fun x(node: SemanticsNode) = node.positionInRoot.x
            fun time() = checkNotNull(nodes().firstOrNull { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text.startsWith("10:00:01") } })
            fun badge() = nodes().first { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "W" } }
            // The second row's tag minus the first's: the first row's height.
            fun pitch(): Float {
                val tags = nodes().filter { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "OkHttp" } }
                return tags[1].positionInRoot.y - tags[0].positionInRoot.y
            }
            val badge0 = x(badge())
            val time0 = x(time())
            width.value = 300
            val seen = List(60) {
                frame()
                Triple(x(badge()), x(time()), pitch())
            }
            frames(30)
            val badge1 = x(badge())
            val time1 = x(time())
            for ((b, t, _) in seen) {
                // One fraction moves every cell: the time is as far along as the badge.
                val s = (badge0 - b) / (badge0 - badge1)
                val expected = time0 + (time1 - time0) * s
                assertTrue(kotlin.math.abs(t - expected) <= 2f, "time at $t, expected $expected (s $s); $seen")
            }
            val steps = seen.map { it.third }.zipWithNext { a, b -> kotlin.math.abs(b - a) }
            assertTrue(steps.all { it < 9f }, "row height steps: ${seen.map { it.third }}")
        }
    }

    @Test
    fun `stacked rows with wrap off select and copy messages only`() = onEdt {
        withScene(300) {
            started(
                narrowLine("first message that is long enough to take both of its lines here", tag = "AlphaTag"),
                narrowLine("second message that is also long enough to take two lines", tag = "BetaTag"),
            )
            val from = text("W").boundsInRoot.centerLeft + Offset(1f, 0f)
            val second = nodes().first { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text.startsWith("second message") } }
            val to = second.boundsInRoot.bottomRight - Offset(2f, 2f)
            val held = PointerButtons(isPrimaryPressed = true)
            scene.sendPointerEvent(PointerEventType.Move, from, type = PointerType.Mouse)
            scene.sendPointerEvent(PointerEventType.Press, from, type = PointerType.Mouse, buttons = held, button = PointerButton.Primary)
            frames(2)
            for (i in 1..10) {
                scene.sendPointerEvent(PointerEventType.Move, from + (to - from) * (i / 10f), type = PointerType.Mouse, buttons = held)
                frame()
            }
            scene.sendPointerEvent(PointerEventType.Release, to, type = PointerType.Mouse, button = PointerButton.Primary)
            frames(5)
            for (meta in listOf(true, false)) for (type in listOf(KeyEventType.KeyDown, KeyEventType.KeyUp)) {
                scene.sendKeyEvent(KeyEvent(Key.C, type, isMetaPressed = meta, isCtrlPressed = !meta))
            }
            frames(5)
            settle()
            val copied = clipboard.texts.lastOrNull().orEmpty()
            assertTrue("first message" in copied && "second message" in copied, "copied: $copied")
            for (cell in listOf("AlphaTag", "BetaTag", "10:00:01")) assertFalse(cell in copied, "$cell isn't selectable; copied: $copied")
        }
    }

    @Test
    fun `the status menu shows each toggle's checked state`() = onEdt {
        withScene(300) {
            controller.wrap = true
            frames(5)
            click(described("More status options"))
            fun state(label: String) = generateSequence(text(label)) { it.parent }
                .firstNotNullOfOrNull { it.config.getOrNull(SemanticsProperties.StateDescription) }
            assertEquals("On", state("Wrap lines"))
            assertEquals("Off", state("Compact view"))
            click(text("Compact view"))
            assertTrue(controller.compact)
            click(described("More status options"))
            assertEquals("On", state("Compact view"))
        }
    }

    @Test
    fun `the own window's minimum is 360 by 360`() {
        assertEquals(DpSize(360.dp, 360.dp), LogcatWindowMinSize)
    }
}
