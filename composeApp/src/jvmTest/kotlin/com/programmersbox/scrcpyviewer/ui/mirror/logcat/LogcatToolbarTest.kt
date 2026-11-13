package com.programmersbox.scrcpyviewer.ui.mirror.logcat

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.logcat.LogLevel
import com.programmersbox.scrcpyviewer.logcat.LogcatLine
import com.programmersbox.scrcpyviewer.logcat.LogcatSource
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.onEdt
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import java.awt.Canvas
import java.awt.event.KeyEvent as AwtKeyEvent
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

private class ToolbarSource : LogcatSource(object : AdbClient(adbPath = { error("no adb") }) {}, "SER") {
    val streams = mutableListOf<Channel<LogcatLine>>()
    val stream get() = streams.last()

    override fun lines(since: String?): Flow<LogcatLine> {
        val ch = Channel<LogcatLine>(Channel.UNLIMITED)
        streams += ch
        return ch.consumeAsFlow()
    }

    override suspend fun processes(): Map<Int, String> = emptyMap()
}

private fun toolbarLine(message: String, level: LogLevel = LogLevel.Info, tag: String = "Tag") =
    LogcatLine("11-13", "10:00:00.000", 100, 100, level, tag, message)

/** The Logcat tab's toolbar and status bar: Play/Pause, the filter field and its autocomplete, chips, counts. */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class LogcatToolbarTest {
    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(StandardTestDispatcher(scheduler))
    private val source = ToolbarSource()
    private val toasts = mutableListOf<String>()
    private val controller = LogcatController(source, scope, { toasts += it }, io = EmptyCoroutineContext)
    private var colors: ColorScheme? = null
    private val awtSource = Canvas()

    private fun scene(motion: Boolean = true): SceneDriver = SceneDriver(
        ImageComposeScene(640, 600, Density(1f)) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                colors = MaterialTheme.colorScheme
                Surface(Modifier.fillMaxSize()) { LogcatTab(controller, 640.dp, motion = motion) }
            }
        },
    ).also { it.frames(5) }

    private fun withScene(motion: Boolean = true, block: SceneDriver.() -> Unit) {
        val driver = scene(motion)
        try {
            driver.block()
        } finally {
            driver.scene.close()
            scope.cancel()
        }
    }

    private fun settle() {
        scheduler.advanceTimeBy(100)
        scheduler.runCurrent()
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

    private fun SceneDriver.described(d: String): SemanticsNode? =
        nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(d) == true }

    private fun SceneDriver.field(): SemanticsNode =
        checkNotNull(nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.EditableText) != null }) { "no field" }

    private fun SceneDriver.click(node: SemanticsNode) {
        val at = node.boundsInRoot.center
        scene.sendPointerEvent(PointerEventType.Move, at, type = PointerType.Mouse)
        scene.sendPointerEvent(PointerEventType.Press, at, type = PointerType.Mouse, button = PointerButton.Primary)
        scene.sendPointerEvent(PointerEventType.Release, at, type = PointerType.Mouse, button = PointerButton.Primary)
        frames(5)
    }

    private fun key(id: Int, code: Int, char: Char = AwtKeyEvent.CHAR_UNDEFINED): KeyEvent {
        val typed = id == AwtKeyEvent.KEY_TYPED
        val awt = AwtKeyEvent(awtSource, id, 0L, 0, if (typed) AwtKeyEvent.VK_UNDEFINED else code, char)
        val type = when (id) {
            AwtKeyEvent.KEY_PRESSED -> KeyEventType.KeyDown
            AwtKeyEvent.KEY_RELEASED -> KeyEventType.KeyUp
            else -> KeyEventType.Unknown
        }
        return KeyEvent(Key(if (typed) AwtKeyEvent.VK_UNDEFINED else code), type, codePoint = char.code, nativeEvent = awt)
    }

    private fun SceneDriver.type(text: String) = text.forEach { c ->
        val code = AwtKeyEvent.getExtendedKeyCodeForChar(c.code)
        scene.sendKeyEvent(key(AwtKeyEvent.KEY_PRESSED, code, c))
        scene.sendKeyEvent(key(AwtKeyEvent.KEY_TYPED, code, c))
        scene.sendKeyEvent(key(AwtKeyEvent.KEY_RELEASED, code, c))
        frame()
    }

    private fun SceneDriver.press(code: Int) {
        scene.sendKeyEvent(key(AwtKeyEvent.KEY_PRESSED, code))
        scene.sendKeyEvent(key(AwtKeyEvent.KEY_RELEASED, code))
        frames(5)
    }

    private fun SceneDriver.pulsing(): Boolean =
        nodes().any { it.config.getOrNull(LogcatPulseKey) == true }

    @Test
    fun `play button reads Play, then Pause, then Resume`() = onEdt {
        withScene {
            assertNotNull(text("Play"))
            click(checkNotNull(described("Start logcat")))
            assertEquals(LogcatRun.Running, controller.run)
            frames(30)
            assertNotNull(text("Pause"))
            assertNull(text("Play"))
            click(checkNotNull(described("Pause logcat")))
            assertEquals(LogcatRun.Paused, controller.run)
            frames(30)
            assertNotNull(text("Resume"))
            click(checkNotNull(described("Resume logcat")))
            assertEquals(LogcatRun.Running, controller.run)
        }
    }

    @Test
    fun `a nudge bump starts the pulse animation on the Play button`() = onEdt {
        withScene {
            frames(10)
            assertFalse(pulsing(), "no pulse on first composition")
            controller.requestNudge()
            frames(3)
            assertTrue(pulsing(), "first ring")
            frames(60) // ~960 ms: the second ring
            assertTrue(pulsing(), "second ring")
            frames(60)
            assertFalse(pulsing(), "two 900 ms rings, then still")
        }
    }

    @Test
    fun `a nudge made before the tab shows pulses when it appears`() = onEdt {
        controller.requestNudge()
        withScene {
            frames(2)
            assertTrue(pulsing())
        }
    }

    @Test
    fun `no pulse under reduced motion`() = onEdt {
        withScene(motion = false) {
            controller.requestNudge()
            repeat(20) {
                frame()
                assertFalse(pulsing())
            }
        }
    }

    @Test
    fun `typing a query highlights spans and shows the hint for level loud`() = onEdt {
        withScene {
            click(field())
            type("-tag:x | level:loud")
            frames(30)
            assertEquals("-tag:x | level:loud", controller.query)
            val shown = checkNotNull(field().config.getOrNull(SemanticsProperties.EditableText))
            val c = checkNotNull(colors)
            fun styleAt(start: Int, end: Int) = shown.spanStyles.firstOrNull { it.start == start && it.end == end }?.item
            assertEquals(c.error, styleAt(0, 1)?.color, "- is error")
            assertEquals(c.primary, styleAt(1, 5)?.color, "tag: is primary")
            assertEquals(LogcatColors.QueryOperatorLight, styleAt(7, 8)?.color, "| is orange")
            val bad = checkNotNull(styleAt(9, 15)) { "level: flagged" }
            assertEquals(TextDecoration.Underline, bad.textDecoration)
            assertNotNull(text("level: expects verbose, debug, info, warn, error or assert."))

            // A quoted value is blue.
            controller.query = "message:\"a b\""
            frames(10)
            val quoted = checkNotNull(field().config.getOrNull(SemanticsProperties.EditableText))
            assertEquals(
                LogcatColors.QueryStringLight,
                quoted.spanStyles.first { it.start == 8 }.item.color,
            )
            frames(30)
            assertNull(text("level: expects verbose, debug, info, warn, error or assert."), "hint closes")
        }
    }

    @Test
    fun `suggestions open on focus, Down then Tab accepts`() = onEdt {
        withScene {
            assertNull(text("Log tag"))
            click(field())
            frames(20)
            assertNotNull(text("tag:"))
            assertNotNull(text("Log tag"))
            assertTrue(texts().any { "excludes" in it && "regex" in it }, "key footer")
            press(AwtKeyEvent.VK_DOWN)
            press(AwtKeyEvent.VK_TAB)
            frames(20)
            assertEquals("package:", controller.query)
            // Now the values for package:, no key footer.
            assertNotNull(text("package:mine"))
            assertFalse(texts().any { "excludes" in it })
            press(AwtKeyEvent.VK_ESCAPE)
            frames(30)
            assertNull(text("package:mine"))
            assertEquals("package:", controller.query)
        }
    }

    @Test
    fun `chips toggle their term and show checked`() = onEdt {
        withScene {
            fun SemanticsNode.has(label: String): Boolean =
                config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label } || children.any { it.has(label) }
            fun selected(label: String): Boolean {
                val chip = nodes().first { n -> n.config.getOrNull(SemanticsProperties.Selected) != null && n.has(label) }
                return chip.config[SemanticsProperties.Selected]
            }
            assertFalse(selected("Picked app"))
            click(checkNotNull(text("Picked app")))
            assertEquals("package:mine", controller.query)
            assertTrue(selected("Picked app"))
            click(checkNotNull(text("Warnings and up")))
            assertEquals("package:mine level:warn", controller.query)
            assertTrue(selected("Warnings and up"))

            controller.query = "level:error"
            frames(5)
            assertFalse(selected("Warnings and up"))
            assertFalse(selected("Picked app"))
            click(checkNotNull(text("Warnings and up")))
            assertEquals("level:warn", controller.query)

            click(checkNotNull(text("Crashes")))
            assertEquals("level:warn is:crash", controller.query)
            assertTrue(selected("Crashes"))
            click(checkNotNull(text("Crashes")))
            assertEquals("level:warn", controller.query)
            assertFalse(selected("Crashes"))
        }
    }

    @Test
    fun `mine label shows the picked package or Pick an app in Apps`() = onEdt {
        withScene {
            assertNotNull(text("Pick an app in Apps"))
            controller.pick("com.example.notes")
            frames(30)
            assertNotNull(text("com.example.notes"))
            assertNotNull(text("mine ="))
            assertNull(text("Pick an app in Apps"))
        }
    }

    @Test
    fun `status bar shows Not running, Live, Paused and X of Y lines`() = onEdt {
        withScene {
            assertNotNull(text("Not running"))
            assertNotNull(text("0 of 0 lines"))
            controller.play()
            settle() // the stream starts on the test dispatcher
            frames(30)
            assertNotNull(text("Live"))
            assertNull(text("Not running"))
            source.stream.trySend(toolbarLine("one"))
            source.stream.trySend(toolbarLine("two", LogLevel.Warn))
            source.stream.trySend(toolbarLine("three", LogLevel.Error))
            settle()
            frames(5)
            assertNotNull(text("3 of 3 lines"))
            controller.query = "level:warn"
            frames(5)
            assertNotNull(text("2 of 3 lines"))
            controller.pause()
            frames(30)
            assertNotNull(text("Paused"))
            assertNull(text("Live"))

            click(checkNotNull(described("Clear log")))
            assertEquals(0, controller.totalLines)
            assertNotNull(text("0 of 0 lines"))
            assertTrue("Cleared. Older lines stay on the device buffer." in toasts)

            click(checkNotNull(described("Soft wrap")))
            assertTrue(controller.wrap)
            click(checkNotNull(described("Compact view")))
            assertTrue(controller.compact)
        }
    }
}
