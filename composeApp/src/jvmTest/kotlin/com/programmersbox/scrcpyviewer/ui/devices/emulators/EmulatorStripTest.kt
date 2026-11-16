package com.programmersbox.scrcpyviewer.ui.devices.emulators

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.adb.Device
import com.programmersbox.scrcpyviewer.adb.DeviceInfo
import com.programmersbox.scrcpyviewer.adb.DeviceState
import com.programmersbox.scrcpyviewer.emulator.AndroidSdk
import com.programmersbox.scrcpyviewer.emulator.Avd
import com.programmersbox.scrcpyviewer.emulator.AvdKind
import com.programmersbox.scrcpyviewer.emulator.AvdState
import com.programmersbox.scrcpyviewer.emulator.SdkLookup
import com.programmersbox.scrcpyviewer.options.OptionCatalog
import com.programmersbox.scrcpyviewer.options.OptionValues
import com.programmersbox.scrcpyviewer.state.EmulatorPrefs
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.common.LocalReduceMotion
import com.programmersbox.scrcpyviewer.ui.common.rememberListDetailPanes
import com.programmersbox.scrcpyviewer.ui.devices.DevicesContent
import com.programmersbox.scrcpyviewer.ui.devices.DevicesUiState
import com.programmersbox.scrcpyviewer.ui.onEdt
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Emulators in narrow windows (1 px = 1 dp): the item view's strip lists them after an "AVD" divider, and an emulator
 * row's controls glide to their second line. Motion is on and passed explicitly: CI runners report reduce motion on.
 */
@OptIn(ExperimentalComposeUiApi::class)
class EmulatorStripTest {
    private val root = Paths.get("sdk-root")
    private val sdk = SdkLookup.Found(AndroidSdk(root, root.resolve("emulator").resolve("emulator"), "37.3.9"))
    private fun avd(id: String, name: String) = Avd(id, name, 36, "16", AvdKind.Phone, root.resolve("$id.avd"))
    private fun row(avd: Avd, state: AvdState = AvdState.Stopped) = EmulatorRow(avd, state, 8_804_682_956L, EmulatorPrefs(lastBootMillis = 20_000))

    private val pixel = Device("39121FDJH004AB", DeviceState.Device, "device", "Pixel_8_Pro", null, 1, false, DeviceInfo("Pixel 8 Pro", "15", 35))
    private val tablet = Device("R52W70ABCDE", DeviceState.Device, "device", "SM_X710", null, 2, false)
    private val running = Device("emulator-5554", DeviceState.Device, "device", "sdk_gphone64", null, 3, false)

    private val avds = EmulatorsUiState(
        sdk = sdk,
        rows = listOf(
            row(avd("Pixel_9_API_36", "Pixel 9"), AvdState.Running("emulator-5554")),
            row(avd("Pixel_Tablet_API_35", "Pixel Tablet")),
            row(avd("Wear_API_35", "Wear Round")),
        ),
        expanded = false,
    )

    private val devices = DevicesUiState(
        devices = listOf(pixel, tablet, running),
        selectedSerial = pixel.serial,
        visibleOptions = OptionCatalog.all,
        values = OptionValues(emptyMap()),
        adbConnected = true,
        canMirror = true,
    )

    private var emulators by mutableStateOf(avds)

    private fun SceneDriver.nodes(): List<SemanticsNode> {
        val out = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) {
            out += node
            node.children.forEach(::walk)
        }
        scene.semanticsOwners.forEach { walk(it.unmergedRootSemanticsNode) }
        return out.filter { it.layoutInfo.isPlaced }
    }

    private fun SemanticsNode.texts() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
    private fun SemanticsNode.described(label: String) = config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true
    private fun SemanticsNode.shown() = boundsInRoot.width > 0f && boundsInRoot.height > 0f
    private fun SceneDriver.described(label: String) = nodes().filter { it.described(label) && it.shown() }

    /** Where Stop is laid out (the row's springing height may clip it for a moment). */
    private fun SceneDriver.stopTop() = nodes().single { it.described("Stop") }.positionInRoot.y
    private fun SceneDriver.text(label: String) = nodes().filter { label in it.texts() && it.shown() }

    /** The Devices screen at 640 with the Pixel open: item view, so the list is the strip. */
    private fun strip(block: SceneDriver.() -> Unit) = onEdt {
        emulators = avds
        val driver = SceneDriver(ImageComposeScene(1000, 900, Density(1f)) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                CompositionLocalProvider(LocalReduceMotion provides false) {
                    Surface {
                        Box(Modifier.width(640.dp).fillMaxHeight()) {
                            val panes = rememberListDetailPanes()
                            remember(panes) { panes.navigation.openDetail() }
                            DevicesContent(
                                devices, onSelect = {}, onOpen = {}, onQuery = {}, onGroup = {}, onToggle = { _, _ -> }, onValue = { _, _ -> },
                                onSave = {}, onSaveAsNew = {}, onMirror = {}, panes = panes, motion = true,
                                emulators = emulators,
                                onEmulator = { if (it is EmulatorEvent.SetExpanded) emulators = emulators.copy(expanded = it.expanded) },
                                emulatorNow = { 10_000 },
                            )
                        }
                    }
                }
            }
        })
        try {
            driver.frames(150)
            driver.block()
        } finally {
            driver.scene.close()
        }
    }

    @Test
    fun `the strip lists devices, then the AVD divider, the emulator tile and the running emulator`() = strip {
        val pixelAvatar = described("Pixel 8 Pro").single { it.boundsInRoot.right <= 76f }.boundsInRoot
        val tabletAvatar = described("SM X710").single { it.boundsInRoot.right <= 76f }.boundsInRoot
        val divider = assertNotNull(text("AVD").singleOrNull(), "the AVD divider").boundsInRoot
        assertTrue(divider.top >= pixelAvatar.bottom && divider.top >= tabletAvatar.bottom, "divider $divider below $pixelAvatar, $tabletAvatar")
        val emulator = assertNotNull(described("Pixel 9").singleOrNull { it.boundsInRoot.right <= 76f }, "the running emulator's avatar").boundsInRoot
        assertTrue(emulator.top >= divider.bottom, "the emulator $emulator after the divider $divider")
        val tile = assertNotNull(described("Emulators").singleOrNull { it.boundsInRoot.right <= 76f }, "the emulator tile").boundsInRoot
        assertTrue(tile.top >= divider.bottom, "the tile $tile after the divider $divider")
        assertTrue(text("3").isNotEmpty(), "the tile's AVD count")
    }

    @Test
    fun `the emulator tile opens the list with the emulators expanded`() = strip {
        assertTrue(described("Collapse").isEmpty(), "no section before")
        val tile = nodes().first { SemanticsActions.OnClick in it.config && it.described("Emulators") }
        tile.config[SemanticsActions.OnClick].action?.invoke()
        frames(150)
        assertTrue(emulators.expanded, "the section expanded")
        assertTrue(described("Close list").isNotEmpty(), "the overlay is open")
        assertTrue(described("Collapse").isNotEmpty(), "the emulators header shows, expanded")
        assertTrue(text("Pixel Tablet").isNotEmpty(), "the AVD rows show")
    }

    @Test
    fun `an emulator row's Stop glides to its second line`() = onEdt {
        var width by mutableIntStateOf(360)
        val driver = SceneDriver(ImageComposeScene(500, 400, Density(1f)) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                CompositionLocalProvider(LocalReduceMotion provides false) {
                    Surface {
                        Box(Modifier.width(width.dp)) {
                            EmulatorSection(avds.copy(expanded = true), {}, {}, motion = true, now = { 10_000 })
                        }
                    }
                }
            }
        })
        try {
            driver.frames(60)
            val before = driver.stopTop()
            width = 280
            // The glide starts from where Stop was: the frame that lays out the new width, and the spring's first
            // frame (its start time), still show it there; the next one has it on its way.
            val glide = List(10) { driver.frame(); driver.stopTop() }
            driver.frames(120)
            val after = driver.stopTop()
            assertTrue(after > before + 10f, "Stop moves down a line: $before -> $after")
            val oneFrameIn = glide[2]
            assertTrue(oneFrameIn > before && oneFrameIn < after, "one frame into the glide, Stop is between: $before, $glide, $after")
            assertTrue(glide.count { it > before && it < after - 1f } >= 4, "Stop moves over several frames: $glide")
        } finally {
            driver.scene.close()
        }
    }

    @Test
    fun `without motion the row restacks at once`() = onEdt {
        var width by mutableIntStateOf(360)
        val driver = SceneDriver(ImageComposeScene(500, 400, Density(1f)) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                CompositionLocalProvider(LocalReduceMotion provides true) {
                    Surface {
                        Box(Modifier.width(width.dp)) {
                            EmulatorSection(avds.copy(expanded = true), {}, {}, motion = false, now = { 10_000 })
                        }
                    }
                }
            }
        })
        try {
            driver.frames(60)
            width = 280
            driver.frame()
            val oneFrame = driver.stopTop()
            driver.frames(60)
            assertEquals(driver.stopTop(), oneFrame)
        } finally {
            driver.scene.close()
        }
    }
}
