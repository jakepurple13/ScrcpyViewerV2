package com.programmersbox.scrcpyviewer.ui.mirror.links

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.adb.ShellResult
import com.programmersbox.scrcpyviewer.adb.ShellRunner
import com.programmersbox.scrcpyviewer.apps.DeviceApp
import com.programmersbox.scrcpyviewer.links.LinkEntry
import com.programmersbox.scrcpyviewer.links.LinkStore
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.mirror.AppsUiState
import com.programmersbox.scrcpyviewer.ui.mirror.LocalSheetFocus
import com.programmersbox.scrcpyviewer.ui.mirror.QuickActionEvent
import com.programmersbox.scrcpyviewer.ui.mirror.QuickActionsPanel
import com.programmersbox.scrcpyviewer.ui.mirror.QuickActionsUiState
import com.programmersbox.scrcpyviewer.ui.mirror.SheetFocus
import com.programmersbox.scrcpyviewer.ui.mirror.SheetTab
import com.programmersbox.scrcpyviewer.ui.onEdt
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import java.awt.Canvas
import java.awt.event.KeyEvent as AwtKeyEvent
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job

/** The Links tab: each row of the spec's motion table moves over its time, without a single-frame jump. */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class LinksTabTest {
    private val store = LinkStore(Files.createTempDirectory("links-ui").resolve("links.json"))
    private val apps = listOf(DeviceApp("com.android.chrome", "Chrome", system = true, versionName = null, versionCode = null))
    private val scope = CoroutineScope(Dispatchers.Unconfined + Job())

    /** Answers each command with [answer]; while [gate] is set, waits for it first. */
    private class FakeRunner(var answer: String) : ShellRunner(object : AdbClient(adbPath = { error("no adb") }) {}, "s") {
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun run(cmd: String, timeoutMs: Long): ShellResult {
            gate?.await()
            return ShellResult(answer, 0, 1)
        }
    }

    private val ok = "Status: ok\nLaunchState: COLD\nActivity: com.android.chrome/.Main\nTotalTime: 412\nComplete"
    private val unresolved = "Error: Activity not started, unable to resolve Intent { act=android.intent.action.VIEW dat=myapp://x }"

    private fun links(runner: FakeRunner = FakeRunner(ok)) = LinksState("d1", runner, store, { apps }, scope, clock = { NOW })

    private fun scene(links: LinksState, motion: Boolean = true, focus: SheetFocus? = null): SceneDriver =
        SceneDriver(ImageComposeScene(400, 1100, Density(1f)) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                Surface(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalSheetFocus provides focus) {
                        LinksTabContent(links, AppsUiState(apps = apps), enabled = true, onEvent = links::onEvent, motion = motion, now = { NOW })
                    }
                }
            }
        }).also { it.frames(10) }

    private fun SceneDriver.nodes(): List<SemanticsNode> {
        val all = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) {
            all += node
            node.children.forEach(::walk)
        }
        scene.semanticsOwners.forEach { walk(it.unmergedRootSemanticsNode) }
        return all
    }

    private fun SceneDriver.tagged(tag: String): SemanticsNode? =
        nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag }

    private fun SceneDriver.text(text: String): SemanticsNode? =
        nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == text } == true }

    private fun SceneDriver.described(prefix: String): SemanticsNode? =
        nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { d -> d.startsWith(prefix) } == true }

    private fun SceneDriver.click(at: Offset) {
        scene.sendPointerEvent(PointerEventType.Move, at, type = PointerType.Mouse)
        scene.sendPointerEvent(PointerEventType.Press, at, type = PointerType.Mouse, button = PointerButton.Primary)
        scene.sendPointerEvent(PointerEventType.Release, at, type = PointerType.Mouse, button = PointerButton.Primary)
    }

    /** [value] over [frames] frames, the first read one frame after [change]. */
    private fun SceneDriver.track(frames: Int, change: () -> Unit, value: () -> Float): List<Float> {
        val before = value()
        change()
        return listOf(before) + List(frames) {
            frame()
            value()
        }
    }

    /** Moves from its first to its last value over several frames, no frame covering more than [maxShare] of the way. */
    private fun assertEases(path: List<Float>, what: String, maxShare: Float = 0.4f, minFrames: Int = 4) {
        val total = abs(path.last() - path.first())
        assertTrue(total > 4f, "$what moves: $path")
        val steps = path.zipWithNext { a, b -> abs(b - a) }
        assertTrue(steps.max() <= total * maxShare, "$what jumps in one frame: $path")
        assertTrue(steps.count { it > 0.5f } >= minFrames, "$what moves over several frames: $path")
    }

    /**
     * Without motion: the change lands in one frame, at once or, for something going, once its 150 ms fade ends
     * (about 10 frames).
     */
    private fun assertInstant(path: List<Float>, what: String) {
        val steps = path.zipWithNext { a, b -> abs(b - a) }.filter { it > 0.5f }
        assertTrue(steps.size <= 1, "$what is instant without motion: $path")
        assertTrue(abs(path[12] - path.last()) < 0.5f, "$what is done within the short fade: $path")
    }

    @Test
    fun `the kind chip pops when the kind changes, and not without motion`() = onEdt {
        for (motion in listOf(true, false)) {
            val l = links()
            l.onEvent(LinksEvent.SetText("https://example.com"))
            val d = scene(l, motion)
            d.frames(40)
            assertNotNull(d.described("Kind: Web link"))
            val path = d.track(40, { l.onEvent(LinksEvent.SetText("myapp://settings")) }) {
                checkNotNull(d.described("Kind: ")).boundsInRoot.width
            }
            assertNotNull(d.described("Kind: myapp: link"))
            if (motion) {
                // Scaled to .7 in the very frame the label changes, then back to full size over 350 ms.
                assertTrue(path[1] < path.last() * 0.8f, "starts small: $path")
                assertTrue(path.drop(1).zipWithNext { a, b -> b - a }.all { it > -0.5f }, "grows back steadily: $path")
                assertTrue(path.subList(2, 20).any { it > path[1] + 1f && it < path.last() - 1f }, "in between frames: $path")
            } else {
                assertInstant(path, "the chip")
            }
            d.scene.close()
        }
    }

    @Test
    fun `Intent details expands and folds over 400 ms, its chevron turning`() = onEdt {
        for (motion in listOf(true, false)) {
            val l = links()
            l.onEvent(LinksEvent.SetText("https://example.com"))
            val d = scene(l, motion)
            fun commandTop() = checkNotNull(d.described("Command")).boundsInRoot.top
            val open = d.track(40, { l.onEvent(LinksEvent.ToggleDetails) }, ::commandTop)
            assertNotNull(d.described("Action"))
            val close = d.track(40, { l.onEvent(LinksEvent.ToggleDetails) }, ::commandTop)
            d.frames(10)
            assertNull(d.described("Action"))
            if (motion) {
                assertEases(open, "opening details")
                assertEases(close, "closing details")
                // 400 ms is about 25 frames: still moving at frame 15.
                assertTrue(abs(open[15] - open.last()) > 1f, "still opening at 240 ms: $open")
            } else {
                assertInstant(open, "opening details")
                assertInstant(close, "closing details")
            }
            d.scene.close()
        }
    }

    @Test
    fun `Open crossfades to Opening… while running and back when done`() = onEdt {
        val runner = FakeRunner(ok).apply { gate = CompletableDeferred() }
        val l = links(runner)
        l.onEvent(LinksEvent.SetText("https://example.com"))
        val d = scene(l)
        l.onEvent(LinksEvent.Open)
        d.frames(3)
        // Mid-crossfade both are there.
        assertNotNull(d.text("Opening…"))
        assertNotNull(d.text("Open on device"))
        d.frames(40)
        assertNotNull(d.text("Opening…"))
        assertNull(d.text("Open on device"))
        runner.gate!!.complete(Unit)
        d.frames(3)
        assertNotNull(d.text("Open on device"))
        d.frames(40)
        assertNull(d.text("Opening…"))
    }

    @Test
    fun `the result strip expands, a failure shakes it, and editing the link folds it`() = onEdt {
        for (motion in listOf(true, false)) {
            store.clear("d1")
            val runner = FakeRunner(unresolved)
            val l = links(runner)
            l.onEvent(LinksEvent.SetText("myapp://x"))
            val d = scene(l, motion)
            val rest = 16f // the tab's side padding
            val lefts = mutableListOf<Float>()
            val expand = d.track(40, { l.onEvent(LinksEvent.Open) }) {
                d.tagged(RESULT_TAG)?.positionInRoot?.x?.let(lefts::add)
                d.text("History")?.positionInRoot?.y ?: 0f
            }
            assertNotNull(d.text("No app on this device opens this link"))
            if (motion) {
                assertEases(expand.drop(1), "the result strip and history under it")
                assertTrue(lefts.any { abs(it - rest) >= 2f }, "the failure shakes: $lefts")
            } else {
                assertInstant(expand.drop(1), "the result strip")
                assertTrue(lefts.all { abs(it - rest) < 0.5f }, "no shake without motion: $lefts")
            }
            assertEquals(rest, lefts.last(), 0.5f, "the shake ends where it started: $lefts")
            // A new link: the result is stale and folds away.
            val fold = d.track(40, { l.onEvent(LinksEvent.SetText("myapp://y")) }) { checkNotNull(d.text("History")).positionInRoot.y }
            d.frames(10)
            assertNull(d.tagged(RESULT_TAG))
            if (motion) assertEases(fold, "the result strip folding") else assertInstant(fold, "the result strip folding")
            d.scene.close()
        }
    }

    @Test
    fun `a new history row expands in and the rows below move down with it`() = onEdt {
        for (motion in listOf(true, false)) {
            store.clear("d1")
            store.record("d1", LinkEntry("https://old.example", ok = true, appLabel = "Chrome", at = NOW - 60_000))
            val l = links()
            l.onEvent(LinksEvent.SetText("https://new.example"))
            val d = scene(l, motion)
            val height = d.track(40, { l.onEvent(LinksEvent.Open) }) { d.tagged("links-row:https://new.example")?.size?.height?.toFloat() ?: 0f }
            val oldRow = checkNotNull(d.tagged("links-row:https://old.example"))
            assertTrue(oldRow.positionInRoot.y > checkNotNull(d.tagged("links-row:https://new.example")).positionInRoot.y, "newest first")
            if (motion) assertEases(height, "the new row's height") else assertInstant(height, "the new row")
            d.scene.close()
        }
    }

    @Test
    fun `starring slides the row to the top, Clear folds the recent rows away and keeps the star`() = onEdt {
        for (motion in listOf(true, false)) {
            store.clear("d1")
            store.state.value.starred.forEach { store.star(it, false) }
            store.record("d1", LinkEntry("https://b.example", ok = false, at = NOW - 120_000))
            store.record("d1", LinkEntry("https://a.example", ok = true, appLabel = "Chrome", at = NOW - 60_000))
            val l = links()
            val d = scene(l, motion)
            val b = l.history.recent.first { it.link == "https://b.example" }
            fun bTop() = checkNotNull(d.tagged("links-row:https://b.example")).positionInRoot.y
            val aTop = checkNotNull(d.tagged("links-row:https://a.example")).positionInRoot.y
            assertTrue(bTop() > aTop)
            val slide = d.track(40, { l.onEvent(LinksEvent.ToggleStar(b)) }, ::bTop)
            assertTrue(slide.last() < checkNotNull(d.tagged("links-row:https://a.example")).positionInRoot.y, "starred first")
            assertNotNull(d.tagged("links-divider"))
            if (motion) assertEases(slide, "the starred row") else assertInstant(slide, "the starred row")

            val fold = d.track(40, { l.onEvent(LinksEvent.Clear) }) { d.tagged("links-row:https://a.example")?.size?.height?.toFloat() ?: 0f }
            d.frames(10)
            assertNull(d.tagged("links-row:https://a.example"))
            assertNull(d.tagged("links-divider"))
            assertNotNull(d.tagged("links-row:https://b.example"), "the starred row stays")
            if (motion) {
                assertEases(fold, "the cleared row folding", minFrames = 3)
                assertTrue(abs(fold[8] - fold.last()) > 1f, "still folding at 128 ms: $fold")
            } else {
                assertInstant(fold, "the cleared row")
            }
            l.toggleStar(l.history.starred.single())
            d.scene.close()
        }
    }

    @Test
    fun `clicking a row fills the field, which flashes over 600 ms`() = onEdt {
        store.clear("d1")
        store.record("d1", LinkEntry("https://filled.example", ok = true, appLabel = "Chrome", at = NOW))
        val l = links()
        val d = scene(l)
        val field = checkNotNull(d.tagged(FIELD_TAG)).boundsInRoot
        // Just inside the field's edge, where the ring draws.
        val x = (field.left + 2f).toInt()
        val y = field.center.y.toInt()
        val rest = d.snapshot()[x, y].toArgb()
        val row = checkNotNull(d.tagged("links-row:https://filled.example")).boundsInRoot
        d.click(Offset(row.left + 60f, row.center.y))
        assertEquals("https://filled.example", l.text)
        val colors = List(45) { d.snapshot()[x, y].toArgb() }
        assertTrue(colors.distinct().size >= 6, "the ring fades in and out over several frames: $colors")
        assertTrue(colors.take(3).any { it != rest }, "flashing from the first frames: $colors")
        assertEquals(rest, colors.last(), "back to rest after 600 ms")
        assertTrue(colors[20] != rest, "still flashing at 320 ms: $colors")
    }

    @Test
    fun `Esc in a field leaves the field and does not close the sheet`() = onEdt {
        val l = links()
        val events = mutableListOf<QuickActionEvent>()
        val d = SceneDriver(ImageComposeScene(500, 900, Density(1f)) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                Surface(Modifier.fillMaxSize()) {
                    QuickActionsPanel(
                        QuickActionsUiState(open = true, enabled = true, tab = SheetTab.Links), { events += it }, console = null, links = l,
                    )
                }
            }
        }).also { it.frames(20) }
        assertNotNull(d.text("Open a link or intent on this device"))
        val field = checkNotNull(d.described("Link or intent"))
        d.click(field.boundsInRoot.center)
        d.frames(5)
        assertTrue(checkNotNull(d.described("Link or intent")).config.getOrNull(SemanticsProperties.Focused) == true, "focused")
        d.scene.sendKeyEvent(key(AwtKeyEvent.KEY_PRESSED, AwtKeyEvent.VK_ESCAPE))
        d.scene.sendKeyEvent(key(AwtKeyEvent.KEY_RELEASED, AwtKeyEvent.VK_ESCAPE))
        d.frames(5)
        assertFalse(QuickActionEvent.Close in events, "the field used the Esc: $events")
        assertFalse(checkNotNull(d.described("Link or intent")).config.getOrNull(SemanticsProperties.Focused) == true, "left the field")
    }

    @Test
    fun `the Open with menu reports focus to the sheet`() = onEdt {
        val focus = SheetFocus()
        val l = links()
        val d = scene(l, focus = focus)
        assertFalse(focus.focused)
        d.click(checkNotNull(d.described("Open with")).boundsInRoot.center)
        d.frames(20)
        assertNotNull(d.text("Chrome"), "the menu lists the device's apps")
        // Arrow keys move focus into the menu: the sheet then has it, so keys typed there never reach the device.
        d.scene.sendKeyEvent(key(AwtKeyEvent.KEY_PRESSED, AwtKeyEvent.VK_DOWN))
        d.scene.sendKeyEvent(key(AwtKeyEvent.KEY_RELEASED, AwtKeyEvent.VK_DOWN))
        d.frames(5)
        assertTrue(focus.focused, "keys typed in the open menu stay in the sheet")
    }

    private val source = Canvas()

    private fun key(id: Int, code: Int): KeyEvent {
        val awt = AwtKeyEvent(source, id, 0L, 0, code, AwtKeyEvent.CHAR_UNDEFINED)
        val type = if (id == AwtKeyEvent.KEY_PRESSED) KeyEventType.KeyDown else KeyEventType.KeyUp
        return KeyEvent(Key(code), type, nativeEvent = awt)
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
