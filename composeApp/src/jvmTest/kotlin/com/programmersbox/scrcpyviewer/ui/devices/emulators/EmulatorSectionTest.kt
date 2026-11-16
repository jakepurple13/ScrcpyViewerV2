package com.programmersbox.scrcpyviewer.ui.devices.emulators

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.programmersbox.scrcpyviewer.ui.common.OpenPlatform
import com.programmersbox.scrcpyviewer.ui.onEdt
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.emulator.AndroidSdk
import com.programmersbox.scrcpyviewer.emulator.Avd
import com.programmersbox.scrcpyviewer.emulator.AvdKind
import com.programmersbox.scrcpyviewer.emulator.AvdState
import com.programmersbox.scrcpyviewer.emulator.BootStage
import com.programmersbox.scrcpyviewer.emulator.SdkLookup
import com.programmersbox.scrcpyviewer.state.EmulatorPrefs
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import java.nio.file.Paths
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The spec's Motion table, on screen: each change, with motion, moves over several frames with no single-frame jump;
 * without it (reduced motion) it lands in one frame or a 150 ms fade. Motion is passed explicitly: CI runners report
 * reduce motion on. Frames are compared by how much of a region's pixels change between them, never by text widths.
 */
class EmulatorSectionTest {
    private val root = Paths.get("sdk-root")
    private val sdk = SdkLookup.Found(AndroidSdk(root, root.resolve("emulator").resolve("emulator"), "37.3.9"))
    private fun avd(id: String, name: String) = Avd(id, name, 36, "16", AvdKind.Phone, root.resolve("$id.avd"))
    private val pixel = avd("Pixel_9_API_36", "Pixel 9")
    private val tablet = avd("Pixel_Tablet_API_35", "Pixel Tablet")

    private fun row(avd: Avd, state: AvdState = AvdState.Stopped) = EmulatorRow(avd, state, 8_804_682_956L, EmulatorPrefs(lastBootMillis = 20_000))

    private fun ui(vararg rows: EmulatorRow, sdk: SdkLookup? = this.sdk, expanded: Boolean = true) =
        EmulatorsUiState(sdk = sdk, rows = rows.toList(), expanded = expanded)

    private val booting = AvdState.Booting("emulator-5554", BootStage.Starting, startedAt = 0, cold = false)

    private class Region(val x: IntRange, val y: IntRange)

    private val width = 420
    private val height = 320
    private val all = Region(0 until 420, 0 until 320)

    /** Left of the controls (icon, name, subtitle, track): no spinner there. */
    private val text = Region(0 until 200, 36 until 320)

    /** The controls (chip and button) of the first row. */
    private val controls = Region(230 until 380, 36 until 110)

    /** The header: "Emulators", the count, the chevron. */
    private val header = Region(0 until 420, 0 until 34)

    /** How much each frame changed in [region] after [change], starting from the settled frame before it. */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun steps(
        from: EmulatorsUiState,
        motion: Boolean,
        region: Region,
        frames: Int = 45,
        clock: LongArray = longArrayOf(10_000),
        /** The wall clock after the change (a second passing), or unchanged. */
        clockAfter: Long? = null,
        change: (EmulatorsUiState) -> EmulatorsUiState,
    ): List<Long> {
        var state by mutableStateOf(from)
        val scene = ImageComposeScene(width, height, Density(1f)) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Box(Modifier.padding(12.dp)) {
                        EmulatorSection(state, {}, {}, motion = motion, now = { clock[0] })
                    }
                }
            }
        }
        try {
            val driver = SceneDriver(scene)
            driver.frames(60)
            var last = driver.snapshot()
            state = change(state)
            clockAfter?.let { clock[0] = it }
            Snapshot.sendApplyNotifications()
            return List(frames) {
                val next = driver.snapshot()
                diff(last, next, region).also { last = next }
            }
        } finally {
            scene.close()
        }
    }

    private fun diff(a: PixelMap, b: PixelMap, r: Region): Long {
        var sum = 0L
        for (y in r.y) for (x in r.x) {
            val p = a[x, y]
            val q = b[x, y]
            sum += ((abs(p.red - q.red) + abs(p.green - q.green) + abs(p.blue - q.blue)) * 255).toLong()
        }
        return sum
    }

    /** Moves over at least 4 frames, and no one frame carries most of the change. */
    private fun assertMoves(steps: List<Long>, what: String) {
        val total = steps.sum()
        assertTrue(total > 0, "$what: nothing changed")
        val moving = steps.count { it > total / 100 }
        assertTrue(moving >= 4, "$what: changed in only $moving frames: $steps")
        assertTrue(steps.max() <= total * 3 / 4, "$what: one frame jumps ${steps.max() * 100 / total}% of the change: $steps")
    }

    /** Lands in one frame or within a 150 ms fade (10 frames, plus the two before the fade starts). */
    private fun assertLandsQuickly(steps: List<Long>, what: String) {
        val total = steps.sum()
        assertTrue(total > 0, "$what: nothing changed")
        val late = steps.drop(13).sum()
        assertTrue(late <= total / 100, "$what: still changing after 150 ms: $steps")
    }

    // --- Section folds / unfolds ------------------------------------------------------------------------------------

    @Test
    fun `fold moves over frames`() {
        val s = steps(ui(row(pixel), row(tablet)), motion = true, all) { it.copy(expanded = false) }
        assertMoves(s, "fold")
        // The body takes 400 ms: still moving after 250 ms.
        assertTrue(s.drop(16).sum() > 0, "fold ended early: $s")
        assertMoves(steps(ui(row(pixel), row(tablet), expanded = false), motion = true, all) { it.copy(expanded = true) }, "unfold")
    }

    @Test
    fun `fold without motion lands at once`() {
        assertLandsQuickly(steps(ui(row(pixel), row(tablet)), motion = false, all) { it.copy(expanded = false) }, "fold")
        assertLandsQuickly(
            steps(ui(row(pixel), row(tablet), expanded = false), motion = false, all) { it.copy(expanded = true) }, "unfold",
        )
    }

    @Test
    fun `chevron turns over frames`() {
        // Only the chevron changes in the header's right end.
        val chevron = Region(330 until 420, 0 until 34)
        assertMoves(steps(ui(row(pixel)), motion = true, chevron) { it.copy(expanded = false) }, "chevron")
        assertLandsQuickly(steps(ui(row(pixel)), motion = false, chevron) { it.copy(expanded = false) }, "chevron")
    }

    // --- Row controls -----------------------------------------------------------------------------------------------

    @Test
    fun `controls swap moves over frames`() {
        // Stopped → Failed: the chip comes in and Start becomes Retry (no spinner to keep the pixels moving).
        val failed = AvdState.Failed("Emulator exited (code 1).", cold = false)
        assertMoves(steps(ui(row(pixel)), motion = true, controls) { it.copy(rows = listOf(row(pixel, failed))) }, "start to retry")
        assertMoves(
            steps(ui(row(pixel, AvdState.Running("emulator-5554"))), motion = true, controls) { it.copy(rows = listOf(row(pixel))) },
            "running to start",
        )
    }

    @Test
    fun `controls swap without motion lands at once`() {
        val failed = AvdState.Failed("Emulator exited (code 1).", cold = false)
        assertLandsQuickly(steps(ui(row(pixel)), motion = false, controls) { it.copy(rows = listOf(row(pixel, failed))) }, "start to retry")
        assertLandsQuickly(
            steps(ui(row(pixel, AvdState.Running("emulator-5554"))), motion = false, controls) { it.copy(rows = listOf(row(pixel))) },
            "running to start",
        )
    }

    @Test
    fun `chip colour eases between states`() {
        // Running → Failed keeps the chip and eases its colours.
        val failed = AvdState.Failed("Emulator exited (code 1).", cold = false)
        val from = ui(row(pixel, AvdState.Running("emulator-5554")))
        assertMoves(steps(from, motion = true, controls) { it.copy(rows = listOf(row(pixel, failed))) }, "chip colour")
        assertLandsQuickly(steps(from, motion = false, controls) { it.copy(rows = listOf(row(pixel, failed))) }, "chip colour")
    }

    // --- Subtitle ---------------------------------------------------------------------------------------------------

    @Test
    fun `subtitle crossfades over frames`() {
        val from = ui(row(pixel, booting))
        val next = { s: EmulatorsUiState -> s.copy(rows = listOf(row(pixel, booting.copy(stage = BootStage.Booting)))) }
        assertMoves(steps(from, motion = true, text, clock = longArrayOf(5_000), change = next), "stage")
        assertLandsQuickly(steps(from, motion = false, text, clock = longArrayOf(5_000), change = next), "stage")
    }

    // --- Progress track ---------------------------------------------------------------------------------------------

    @Test
    fun `progress track expands in when booting starts`() {
        val clock = longArrayOf(0)
        val start = { s: EmulatorsUiState -> s.copy(rows = listOf(row(pixel, booting))) }
        assertMoves(steps(ui(row(pixel)), motion = true, text, clock = clock, change = start), "track in")
        assertLandsQuickly(steps(ui(row(pixel)), motion = false, text, clock = longArrayOf(0), change = start), "track in")
    }

    @Test
    fun `progress track fill moves each second`() {
        val from = ui(row(pixel, booting.copy(stage = BootStage.Booting)))
        val moving = steps(from, motion = true, text, clock = longArrayOf(5_000), clockAfter = 6_000) { it }
        assertMoves(moving, "fill")
        // A 300 ms linear tween: done by ~20 frames.
        assertTrue(moving.drop(22).sum() == 0L, "fill still moving: $moving")
        assertLandsQuickly(steps(from, motion = false, text, clock = longArrayOf(5_000), clockAfter = 6_000) { it }, "fill")
    }

    @Test
    fun `progress track fills and shrinks away on ready`() {
        val from = ui(row(pixel, booting.copy(stage = BootStage.WaitingForAdb)))
        val ready = { s: EmulatorsUiState -> s.copy(rows = listOf(row(pixel, AvdState.Running("emulator-5554")))) }
        val s = steps(from, motion = true, text, clock = longArrayOf(10_000), change = ready)
        assertMoves(s, "ready")
        // Fill (300 ms) then shrink (300 ms): still moving after 400 ms.
        assertTrue(s.drop(25).sum() > 0, "track gone too soon: $s")
        assertLandsQuickly(steps(from, motion = false, text, clock = longArrayOf(10_000), change = ready), "ready")
    }

    @Test
    fun `boot progress eases towards 96 percent`() {
        assertEquals(0f, bootProgress(0, 30_000))
        assertEquals(TRACK_CEILING, bootProgress(30_000, 30_000))
        assertEquals(TRACK_CEILING, bootProgress(90_000, 30_000))
        val half = bootProgress(15_000, 30_000)
        assertTrue(half > 0.48f * 2 * 0.5f && half < TRACK_CEILING, "ease out: $half")
        // Slows near the end: the last second adds less than the first.
        assertTrue(bootProgress(30_000, 30_000) - bootProgress(29_000, 30_000) < bootProgress(1_000, 30_000))
        assertEquals(20_000, expectedBootMillis(20_000, cold = false))
        assertEquals(32_000, expectedBootMillis(20_000, cold = true))
        assertEquals(DEFAULT_BOOT_MILLIS, expectedBootMillis(null, cold = false))
        assertEquals(48_000, expectedBootMillis(null, cold = true))
    }

    // --- Count, empty states ----------------------------------------------------------------------------------------

    @Test
    fun `count slides to the new number`() {
        val from = ui(row(pixel))
        val two = { s: EmulatorsUiState -> s.copy(rows = listOf(row(pixel), row(tablet))) }
        assertMoves(steps(from, motion = true, header, change = two), "count")
        assertLandsQuickly(steps(from, motion = false, header, change = two), "count")
    }

    @Test
    fun `empty state and rows crossfade`() {
        val none = ui(sdk = SdkLookup.NotFound("~/Library/Android/sdk"))
        val rows = { s: EmulatorsUiState -> s.copy(sdk = sdk, rows = listOf(row(pixel), row(tablet))) }
        assertMoves(steps(none, motion = true, all, change = rows), "no sdk to rows")
        assertMoves(steps(ui(row(pixel)), motion = true, all) { it.copy(sdk = SdkLookup.NotFound("~/Library/Android/sdk"), rows = emptyList()) }, "rows to no sdk")
        assertLandsQuickly(steps(none, motion = false, all, change = rows), "no sdk to rows")
    }

    @Test
    fun `subtitle parts missing are left out`() {
        assertEquals("API 36 · Android 16 · 8.2 GB", subtitle(row(pixel)).text)
        assertEquals("API 36 · Android 16", subtitle(row(pixel).copy(size = null)).text)
        assertEquals("", subtitle(EmulatorRow(Avd("x", "x", null, null, AvdKind.Phone, root), AvdState.Stopped, null, EmulatorPrefs())).text)
        assertEquals(Subtitle("emulator-5554", mono = true), subtitle(row(pixel, AvdState.Running("emulator-5554"))))
        assertEquals("Waiting for adb…", subtitle(row(pixel, booting.copy(stage = BootStage.WaitingForAdb))).text)
    }

    // --- More menu: Show in Finder, Wipe data ----------------------------------------------------------------------

    /** Finds [text]'s merged node (a menu item or button holds its label). */
    private fun SceneDriver.node(text: String): SemanticsNode? {
        var found: SemanticsNode? = null
        fun walk(n: SemanticsNode) {
            if (found == null && n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true) found = n
            n.children.forEach(::walk)
        }
        scene.semanticsOwners.forEach { walk(it.rootSemanticsNode) }
        return found
    }

    private fun SceneDriver.click(at: Offset) {
        scene.sendPointerEvent(PointerEventType.Move, at, type = PointerType.Mouse)
        scene.sendPointerEvent(PointerEventType.Press, at, type = PointerType.Mouse, button = PointerButton.Primary)
        scene.sendPointerEvent(PointerEventType.Release, at, type = PointerType.Mouse, button = PointerButton.Primary)
        frames(3)
    }

    private fun SceneDriver.click(text: String) = click(assertNotNull(node(text), "no $text").boundsInRoot.center)

    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    private fun SceneDriver.key(key: Key) {
        for (type in listOf(KeyEventType.KeyDown, KeyEventType.KeyUp)) {
            scene.sendKeyEvent(KeyEvent(key, type))
            frame()
        }
        frames(2)
    }

    /** The menu for [row] on a plain surface; returns the driver and what it sent. */
    private fun menuScene(
        row: EmulatorRow,
        platform: OpenPlatform = OpenPlatform.Mac,
        motion: Boolean = true,
        events: MutableList<Any> = mutableListOf(),
    ): SceneDriver = SceneDriver(ImageComposeScene(320, 360, Density(1f)) {
        ScrcpyViewerTheme(ThemeMode.Light) {
            Surface(Modifier.fillMaxSize()) {
                Column {
                    EmulatorMenu(
                        row, onEvent = { events += it }, onDismiss = { events += "dismiss" }, onWipe = { events += "wipe" },
                        motion = motion, platform = platform,
                    )
                }
            }
        }
    })

    @Test
    fun `Wipe data disabled unless stopped or failed`() = onEdt {
        val states = listOf(
            AvdState.Stopped to true,
            AvdState.Failed("Emulator exited (code 1).", cold = false) to true,
            booting to false,
            AvdState.Running("emulator-5554") to false,
            AvdState.Stopping("emulator-5554") to false,
        )
        for ((state, enabled) in states) {
            val events = mutableListOf<Any>()
            val d = menuScene(row(pixel, state), events = events)
            try {
                d.frames(20)
                val item = assertNotNull(d.node("Wipe data…"), "$state")
                assertEquals(enabled, !item.config.contains(SemanticsProperties.Disabled), "$state")
                d.click("Wipe data…")
                assertEquals(if (enabled) listOf<Any>("dismiss", "wipe") else emptyList(), events, "$state")
            } finally {
                d.scene.close()
            }
        }
        // Not while its data is already being wiped, nor while our emulator process is still exiting.
        for (row in listOf(row(pixel).copy(wiping = true), row(pixel).copy(exiting = true))) {
            val d = menuScene(row)
            try {
                d.frames(20)
                assertTrue(assertNotNull(d.node("Wipe data…")).config.contains(SemanticsProperties.Disabled), "$row")
            } finally {
                d.scene.close()
            }
        }
    }

    @Test
    fun `reveal is Show in Finder on macOS and Show in folder elsewhere, and opens the avd folder`() = onEdt {
        assertEquals("Show in Finder", revealLabel(OpenPlatform.Mac))
        assertEquals("Show in folder", revealLabel(OpenPlatform.Windows))
        assertEquals("Show in folder", revealLabel(OpenPlatform.Linux))
        val events = mutableListOf<Any>()
        val d = menuScene(row(pixel, AvdState.Running("emulator-5554")), platform = OpenPlatform.Windows, events = events)
        try {
            d.frames(20)
            assertNotNull(d.node("Show in folder"))
            // Offered in every state: a running AVD's folder can be shown too.
            d.click("Show in folder")
            assertEquals(listOf<Any>("dismiss", EmulatorEvent.Reveal(pixel.id)), events)
        } finally {
            d.scene.close()
        }
    }

    /** How much each frame of the menu changed after its row went from [from] to [to] (Stopped to Running by default). */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun menuSteps(
        motion: Boolean,
        from: EmulatorRow = row(pixel),
        to: EmulatorRow = row(pixel, AvdState.Running("emulator-5554")),
    ): List<Long> {
        var row by mutableStateOf(from)
        val scene = ImageComposeScene(320, 360, Density(1f)) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                Surface(Modifier.fillMaxSize()) {
                    Column { EmulatorMenu(row, {}, {}, motion = motion, platform = OpenPlatform.Mac) }
                }
            }
        }
        try {
            val d = SceneDriver(scene)
            d.frames(30)
            var last = d.snapshot()
            row = to
            Snapshot.sendApplyNotifications()
            return List(40) {
                val next = d.snapshot()
                diff(last, next, Region(0 until 320, 0 until 360)).also { last = next }
            }
        } finally {
            scene.close()
        }
    }

    @Test
    fun `menu items crossfade when the state changes`() {
        // Cold boot leaves and Wipe data dims over 200 ms.
        val moving = menuSteps(motion = true)
        assertMoves(moving, "menu items")
        assertLandsQuickly(menuSteps(motion = false), "menu items")
    }

    @Test
    fun `Wipe data fades in when the emulator process has exited`() {
        // Only Wipe data's enabled state changes (Cold boot stays).
        val exiting = row(pixel).copy(exiting = true)
        assertMoves(menuSteps(motion = true, from = exiting, to = row(pixel)), "wipe enabled")
        assertLandsQuickly(menuSteps(motion = false, from = exiting, to = row(pixel)), "wipe enabled")
    }

    /** How much each frame changed after the wipe dialog opened (or closed, from open). */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun dialogSteps(motion: Boolean, opening: Boolean): List<Long> {
        var visible by mutableStateOf(!opening)
        val scene = ImageComposeScene(width, height, Density(1f)) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Box(Modifier.padding(12.dp)) { EmulatorSection(ui(row(pixel)), {}, {}, motion = motion, now = { 10_000 }) }
                    WipeDialog(visible, "Pixel 9", onConfirm = {}, onDismiss = {}, motion = motion)
                }
            }
        }
        try {
            val d = SceneDriver(scene)
            d.frames(60)
            var last = d.snapshot()
            visible = opening
            Snapshot.sendApplyNotifications()
            return List(45) {
                val next = d.snapshot()
                diff(last, next, all).also { last = next }
            }
        } finally {
            scene.close()
        }
    }

    @Test
    fun `wipe dialog opens and closes over frames`() = onEdt {
        val open = dialogSteps(motion = true, opening = true)
        assertMoves(open, "open")
        // The card takes 450 ms: still moving after 300 ms.
        assertTrue(open.drop(19).sum() > 0, "open ended early: $open")
        val close = dialogSteps(motion = true, opening = false)
        assertMoves(close, "close")
        // Closing takes 150 ms.
        assertTrue(close.drop(14).sum() <= close.sum() / 100, "close too slow: $close")
    }

    @Test
    fun `wipe dialog without motion fades in 150 ms`() = onEdt {
        assertLandsQuickly(dialogSteps(motion = false, opening = true), "open")
        assertLandsQuickly(dialogSteps(motion = false, opening = false), "close")
    }

    private class DialogCalls {
        var confirmed = 0
        var dismissed = 0
    }

    /** The wipe dialog, open, over a plain surface; [block] drives it. */
    private fun withDialog(block: SceneDriver.(DialogCalls) -> Unit): DialogCalls {
        val calls = DialogCalls()
        var visible by mutableStateOf(true)
        val d = SceneDriver(ImageComposeScene(width, height, Density(1f)) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                Surface(Modifier.fillMaxSize()) {
                    WipeDialog(
                        visible, "Pixel 9",
                        onConfirm = { calls.confirmed++; visible = false },
                        onDismiss = { calls.dismissed++; visible = false },
                        motion = true,
                    )
                }
            }
        })
        try {
            d.frames(40)
            d.block(calls)
        } finally {
            d.scene.close()
        }
        return calls
    }

    @Test
    fun `wipe dialog shows the confirm copy`() = onEdt {
        withDialog {
            assertNotNull(node("Wipe Pixel 9?"))
            assertNotNull(node("This erases apps, accounts and files on the emulator. It can't be undone."))
            assertNotNull(node("Cancel"))
            assertNotNull(node("Wipe data"))
        }
    }

    @Test
    fun `wipe dialog - Wipe data confirms, and clicks while it closes do nothing`() = onEdt {
        val calls = withDialog {
            val wipe = assertNotNull(node("Wipe data")).boundsInRoot.center
            click(wipe)
            click(wipe)
        }
        assertEquals(1, calls.confirmed)
        assertEquals(0, calls.dismissed)
    }

    @Test
    fun `wipe dialog - Cancel, Esc and the scrim close without wiping`() = onEdt {
        assertEquals(0 to 1, withDialog { click("Cancel") }.let { it.confirmed to it.dismissed })
        assertEquals(0 to 1, withDialog { key(Key.Escape) }.let { it.confirmed to it.dismissed })
        assertEquals(0 to 1, withDialog { click(Offset(4f, 4f)) }.let { it.confirmed to it.dismissed })
        // A click on the card itself (its text) does nothing.
        assertEquals(0 to 0, withDialog { click("Wipe Pixel 9?") }.let { it.confirmed to it.dismissed })
    }

    @Test
    fun `wipe dialog - focus starts on Cancel, Enter on Wipe data confirms`() = onEdt {
        // Enter right away presses the focused Cancel.
        assertEquals(0 to 1, withDialog { key(Key.Enter) }.let { it.confirmed to it.dismissed })
        // Tab moves to Wipe data; Enter there confirms.
        assertEquals(1 to 0, withDialog { key(Key.Tab); key(Key.Enter) }.let { it.confirmed to it.dismissed })
    }

    @Test
    fun `a wiping row says so and offers no second wipe`() {
        assertEquals("Wiping data…", subtitle(row(pixel).copy(wiping = true)).text)
        assertEquals("Wiping data…", subtitle(row(pixel, AvdState.Failed("x", cold = false)).copy(wiping = true)).text)
        assertFalse(canWipe(row(pixel).copy(wiping = true)))
    }

    @Test
    fun `sdk path shows home as a tilde`() {
        val home = Paths.get("Users", "me")
        val sep = java.io.File.separator
        assertEquals("~${sep}Library${sep}Android${sep}sdk", displayPath(home.resolve("Library").resolve("Android").resolve("sdk"), home))
        assertEquals(Paths.get("opt", "sdk").toString(), displayPath(Paths.get("opt", "sdk"), home))
    }
}
