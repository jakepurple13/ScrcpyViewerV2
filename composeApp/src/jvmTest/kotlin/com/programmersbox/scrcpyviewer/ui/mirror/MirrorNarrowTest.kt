package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.adb.ShellEvent
import com.programmersbox.scrcpyviewer.adb.ShellRunner
import com.programmersbox.scrcpyviewer.media.VideoFrame
import com.programmersbox.scrcpyviewer.scrcpy.SessionStatus
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.common.LocalReduceMotion
import com.programmersbox.scrcpyviewer.ui.onEdt
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The mirror window's content in narrow windows: the sheet floats over the stage, compact tabs, the Shell live bar. */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class MirrorNarrowTest {
    private class Content(width: Int, quickActions: QuickActionsUiState, reduceMotion: Boolean) {
        var quick by mutableStateOf(quickActions)
        var sheet: Rect? = null
        var stage: IntSize = IntSize.Zero
        val pointers = mutableListOf<PointerEventType>()
        val driver = SceneDriver(ImageComposeScene(width, 700, Density(1f)) {
            CompositionLocalProvider(LocalReduceMotion provides reduceMotion) {
                ScrcpyViewerTheme(ThemeMode.Light) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                        MirrorContent(
                            state = MirrorUiState(model = "Pixel", details = "Android 15 · USB", status = SessionStatus.Running, quickActions = quick),
                            frame = mutableStateOf<VideoFrame?>(null),
                            onZoom = {}, onAction = {}, onReconnect = {},
                            onPointer = { pointers += it.type },
                            onViewSize = {}, onStageSize = { stage = it },
                            onSheetBounds = { sheet = it },
                        )
                    }
                }
            }
        }).also { it.frames(5) }
    }

    private fun SceneDriver.nodes(merged: Boolean = false): List<SemanticsNode> {
        val all = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) {
            all += node
            node.children.forEach(::walk)
        }
        scene.semanticsOwners.forEach { walk(if (merged) it.rootSemanticsNode else it.unmergedRootSemanticsNode) }
        return all
    }

    /** The node showing [text]; [merged] skips what measuring probes compose (their semantics are cleared). */
    private fun SceneDriver.text(text: String, merged: Boolean = false): SemanticsNode? =
        nodes(merged).firstOrNull { it.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text == text }

    /** Unclipped bounds: where the node is laid out, whatever its ancestors clip. */
    private fun SemanticsNode.unclipped(): Rect {
        val at = layoutInfo.coordinates.positionInRoot()
        return Rect(at, androidx.compose.ui.geometry.Size(size.width.toFloat(), size.height.toFloat()))
    }

    private fun assertShown(node: SemanticsNode?, inside: Rect, what: String) {
        assertNotNull(node, "$what is there")
        val r = node.unclipped()
        assertTrue(r.width > 0f && r.left >= inside.left - 0.5f && r.right <= inside.right + 0.5f, "$what at $r inside $inside")
        assertEquals(r.width, node.boundsInRoot.width, 0.5f, "$what is not clipped")
        val layout = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layout)
        layout.firstOrNull()?.let { assertTrue(!it.isLineEllipsized(0) && it.getLineRight(it.lineCount - 1) <= it.size.width + 0.5f && it.lineCount == 1, "$what is not cut off: ${it.getLineRight(0)} in ${it.size}") }
    }

    private val actionsOpen = QuickActionsUiState(open = true, enabled = true)

    @Test
    fun `at 450 the Actions sheet floats over the stage, which keeps the row less the toolbar`() = onEdt {
        val c = Content(450, actionsOpen, reduceMotion = false)
        val sheet = checkNotNull(c.sheet)
        assertTrue(sheet.right <= 450f - 64f + 0.5f, "sheet ends at the toolbar's start: $sheet")
        assertEquals(280f, sheet.width, 0.5f)
        assertEquals(450 - 64, c.stage.width)
    }

    @Test
    fun `at 600 the Actions sheet docks as before`() = onEdt {
        val c = Content(600, actionsOpen, reduceMotion = false)
        val sheet = checkNotNull(c.sheet)
        assertEquals(600f, sheet.right, 0.5f)
        assertEquals(280f, sheet.width, 0.5f)
        assertEquals(600 - 64 - 280, c.stage.width)
    }

    @Test
    fun `at 344 the Files sheet fills the row beside the toolbar`() = onEdt {
        val c = Content(344, actionsOpen.copy(tab = SheetTab.Files), reduceMotion = true)
        val sheet = checkNotNull(c.sheet)
        assertEquals(0f, sheet.left, 0.5f)
        assertEquals(280f, sheet.width, 0.5f)
        assertEquals(344 - 64, c.stage.width)
    }

    @Test
    fun `presses on the scrim never reach the device, and the sheet stays open`() = onEdt {
        val c = Content(450, actionsOpen, reduceMotion = false)
        val at = Offset(40f, 300f)
        c.driver.scene.sendPointerEvent(PointerEventType.Press, at, type = PointerType.Mouse, button = PointerButton.Primary)
        c.driver.scene.sendPointerEvent(PointerEventType.Release, at, type = PointerType.Mouse, button = PointerButton.Primary)
        c.driver.frames(3)
        assertTrue(c.pointers.none { it == PointerEventType.Press || it == PointerEventType.Release }, "${c.pointers}")
        assertNotNull(c.sheet, "the sheet stays open")

        // Docked, the same press reaches the device as before.
        val docked = Content(700, actionsOpen, reduceMotion = false)
        docked.driver.scene.sendPointerEvent(PointerEventType.Press, at, type = PointerType.Mouse, button = PointerButton.Primary)
        docked.driver.frames(2)
        assertTrue(PointerEventType.Press in docked.pointers, "${docked.pointers}")
    }

    @Test
    fun `resizing across the breakpoint glides the toolbar and sheet with a spring, at once under reduced motion`() = onEdt {
        for (reduce in listOf(false, true)) {
            var width by mutableStateOf(600)
            var sheet: Rect? = null
            val driver = SceneDriver(ImageComposeScene(600, 700, Density(1f)) {
                CompositionLocalProvider(LocalReduceMotion provides reduce) {
                    ScrcpyViewerTheme(ThemeMode.Light) {
                        Surface(Modifier.width(width.dp)) {
                            MirrorContent(
                                state = MirrorUiState(model = "Pixel", details = "Android 15 · USB", status = SessionStatus.Running, quickActions = actionsOpen),
                                frame = mutableStateOf<VideoFrame?>(null),
                                onZoom = {}, onAction = {}, onReconnect = {}, onPointer = {}, onViewSize = {}, onStageSize = {},
                                onSheetBounds = { sheet = it },
                            )
                        }
                    }
                }
            })
            driver.frames(5)
            assertEquals(600f, checkNotNull(sheet).right, 0.5f)
            width = 450
            driver.frames(3)
            val mid = checkNotNull(sheet).right
            if (reduce) {
                assertEquals(386f, mid, 0.5f, "snaps under reduced motion")
            } else {
                assertTrue(mid > 386.5f, "still gliding toward the toolbar's start: $mid")
            }
            driver.frames(90)
            assertEquals(386f, checkNotNull(sheet).right, 0.5f)
        }
    }

    @Test
    fun `at 280 dp on Files with two transfers the Files label and its badge show inside the sheet`() = onEdt {
        val c = Content(344, actionsOpen.copy(tab = SheetTab.Files, filesTransfersRunning = 2), reduceMotion = true)
        val sheet = checkNotNull(c.sheet)
        assertEquals(280f, sheet.width, 0.5f)
        assertShown(c.driver.text("Files"), sheet, "the Files label")
        assertShown(c.driver.text("2"), sheet, "the badge")
        // The other segments show their icons only, each named for accessibility.
        val tabs = c.driver.nodes(merged = true).filter { it.config.getOrNull(SemanticsProperties.Role) == Role.Tab }
        assertEquals(6, tabs.size)
        assertNull(c.driver.text("Actions"), "unselected segments show no label")
        val files = tabs[SheetTab.Files.ordinal].boundsInRoot
        val actions = tabs[SheetTab.Actions.ordinal].boundsInRoot
        assertTrue(files.width > actions.width * 1.8f, "the selected segment is wider: $files vs $actions")
    }

    @Test
    fun `at 440 dp every tab label shows`() = onEdt {
        val c = Content(504, actionsOpen.copy(tab = SheetTab.Files, filesTransfersRunning = 2), reduceMotion = true)
        val sheet = checkNotNull(c.sheet)
        assertEquals(440f, sheet.width, 0.5f)
        for (tab in SheetTab.entries) assertShown(c.driver.text(tab.name), sheet, "the ${tab.name} label")
    }

    /** Streams one line, then waits. */
    private class Streaming : ShellRunner(object : AdbClient(adbPath = { error("no adb") }) {}, "serial") {
        override fun stream(cmd: String): Flow<ShellEvent> = flow {
            emit(ShellEvent.Output("line\n"))
            awaitCancellation()
        }
    }

    @Test
    fun `the Shell live bar folds Copy into the overflow menu in a narrow sheet`() = onEdt {
        for ((width, copyShown) in listOf(400 to true, 300 to false)) {
            val console = ShellConsole(Streaming(), ShellHistory(), CoroutineScope(Dispatchers.Unconfined), {})
            console.input = "logcat"
            console.submit()
            val driver = SceneDriver(ImageComposeScene(width, 600, Density(1f)) {
                CompositionLocalProvider(LocalReduceMotion provides true) {
                    ScrcpyViewerTheme(ThemeMode.Light) {
                        Surface(Modifier.fillMaxSize()) { ShellTabContent(console, emptyList()) {} }
                    }
                }
            })
            driver.frames(5)
            assertNotNull(driver.text("Pause", merged = true), "Pause stays at $width")
            assertEquals(copyShown, driver.text("Copy", merged = true) != null, "Copy at $width")
            assertEquals(!copyShown, driver.nodes(merged = true).any { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("More") == true })
            assertNotNull(driver.text("■ Stop", merged = true), "the Stop button at $width")
            console.stop()
        }
    }

    /** MirrorContent in a 700 px scene whose content width, sheet and pin the test changes as it goes. */
    private class Live(
        width: Int,
        quick: QuickActionsUiState,
        onPointer: (androidx.compose.ui.input.pointer.PointerEvent) -> Unit = {},
        onInputDetached: () -> Unit = {},
        onStageCovered: (Boolean) -> Unit = {},
    ) {
        var width by mutableStateOf(width)
        var quick by mutableStateOf(quick)
        var pinned by mutableStateOf<Int?>(null)
        var sheet: Rect? = null
        val driver = SceneDriver(ImageComposeScene(700, 700, Density(1f)) {
            CompositionLocalProvider(LocalReduceMotion provides false) {
                ScrcpyViewerTheme(ThemeMode.Light) {
                    Surface(Modifier.width(this.width.dp)) {
                        MirrorContent(
                            state = MirrorUiState(model = "Pixel", details = "Android 15 · USB", status = SessionStatus.Running, quickActions = this.quick),
                            frame = mutableStateOf<VideoFrame?>(null),
                            onZoom = {}, onAction = {}, onReconnect = {}, onPointer = onPointer, onViewSize = {}, onStageSize = {},
                            onSheetBounds = { sheet = it },
                            pinnedStageWidth = pinned,
                            onInputDetached = onInputDetached,
                            onStageCovered = onStageCovered,
                        )
                    }
                }
            }
        }).also { it.frames(5) }

        /** The side toolbar's Back button: where the toolbar is. */
        fun toolbarX(): Float = driver.scene.semanticsOwners.flatMap { owner ->
            val all = mutableListOf<SemanticsNode>()
            fun walk(n: SemanticsNode) { all += n; n.children.forEach(::walk) }
            walk(owner.unmergedRootSemanticsNode)
            all
        }.first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Back") == true }.boundsInRoot.center.x
    }

    @Test
    fun `covering the stage releases the device's held key and button, ends the capture, and keeps keys off it`() = onEdt {
        val sent = mutableListOf<com.programmersbox.scrcpyviewer.scrcpy.ControlMessage>()
        val mapper = InputMapper(frameSize = { IntSize(1080, 2400) }, viewRect = { Rect(0f, 0f, 300f, 600f) })
        var open = false
        var captureEnded = 0
        val cover = StageCoverRelease(endCapture = { captureEnded++ })
        val keys = MirrorKeys(
            mapper = mapper, hidKeyboard = null, send = { sent += it }, clipboardText = { null },
            scope = CoroutineScope(Dispatchers.Unconfined), sheetOpen = { open }, sheetFocused = { false }, closeSheet = {},
            applyZoom = {}, stageCovered = { cover.covered },
        )
        cover.releaseKeys = keys::onStageCovered
        val live = Live(700, actionsOpen.copy(open = false), onPointer = { sent += mapper.pointer(it) }, onInputDetached = { sent += mapper.releaseButtons() }, onStageCovered = cover::update)
        // A key and a button held on the device.
        assertTrue(keys.onKey(androidx.compose.ui.input.key.KeyEvent(androidx.compose.ui.input.key.Key.A, androidx.compose.ui.input.key.KeyEventType.KeyDown)))
        live.driver.scene.sendPointerEvent(PointerEventType.Press, Offset(100f, 300f), type = PointerType.Mouse, button = PointerButton.Primary)
        live.driver.frames(2)
        assertTrue(sent.any { it is com.programmersbox.scrcpyviewer.scrcpy.ControlMessage.InjectTouch }, "the press reached the device: $sent")
        sent.clear()

        // The sheet opens in a narrow window: it floats over the stage.
        open = true
        live.width = 450
        live.quick = actionsOpen
        live.driver.frames(5)
        assertTrue(cover.covered)
        assertEquals(1, captureEnded, "the capture ended")
        val keyUp = sent.filterIsInstance<com.programmersbox.scrcpyviewer.scrcpy.ControlMessage.InjectKeycode>()
        assertEquals(listOf(InputMapper.KEY_ACTION_UP), keyUp.map { it.action }, "A released: $sent")
        assertTrue(sent.any { it is com.programmersbox.scrcpyviewer.scrcpy.ControlMessage.InjectTouch && it.action == 1 }, "the button released: $sent")
        sent.clear()
        // From here no key reaches the device.
        assertFalse(keys.onKey(androidx.compose.ui.input.key.KeyEvent(androidx.compose.ui.input.key.Key.B, androidx.compose.ui.input.key.KeyEventType.KeyDown)))
        assertTrue(sent.isEmpty(), "$sent")
    }

    @Test
    fun `growth while floating keeps the toolbar at the end edge, with no snap when it ends`() = onEdt {
        val live = Live(450, actionsOpen)
        assertEquals(450f - 32f, live.toolbarX(), 1f)
        // A tab switch while floating grows the window: the stage is pinned meanwhile.
        live.pinned = 200
        val path = List(20) {
            live.driver.frame()
            live.toolbarX()
        }
        live.pinned = null
        val after = List(10) {
            live.driver.frame()
            live.toolbarX()
        }
        (path + after).forEach { assertEquals(450f - 32f, it, 1f, "the toolbar stays at the end edge: ${path + after}") }
        assertTrue(checkNotNull(live.sheet).right <= 386.5f)
    }

    @Test
    fun `a sheet closed while floating and reopened docked opens as a fresh one, with no glide from the old place`() = onEdt {
        fun sheetPath(live: Live) = List(20) {
            live.driver.frame()
            checkNotNull(live.sheet)
        }
        val fresh = Live(700, actionsOpen.copy(open = false))
        fresh.quick = actionsOpen
        val freshPath = sheetPath(fresh)

        val reopened = Live(450, actionsOpen)
        reopened.quick = actionsOpen.copy(open = false)
        reopened.driver.frames(60)
        reopened.width = 700
        reopened.driver.frames(10)
        reopened.quick = actionsOpen
        val reopenedPath = sheetPath(reopened)
        assertEquals(freshPath, reopenedPath)
    }
}
