package com.programmersbox.scrcpyviewer.ui.devices.emulators

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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

    @Test
    fun `sdk path shows home as a tilde`() {
        val home = Paths.get("Users", "me")
        val sep = java.io.File.separator
        assertEquals("~${sep}Library${sep}Android${sep}sdk", displayPath(home.resolve("Library").resolve("Android").resolve("sdk"), home))
        assertEquals(Paths.get("opt", "sdk").toString(), displayPath(Paths.get("opt", "sdk"), home))
    }
}
