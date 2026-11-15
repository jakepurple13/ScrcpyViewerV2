package com.programmersbox.scrcpyviewer.ui.devices

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.programmersbox.scrcpyviewer.adb.Device
import com.programmersbox.scrcpyviewer.adb.DeviceInfo
import com.programmersbox.scrcpyviewer.adb.DeviceState
import com.programmersbox.scrcpyviewer.emulator.Avd
import com.programmersbox.scrcpyviewer.emulator.AvdKind
import com.programmersbox.scrcpyviewer.emulator.AvdState
import com.programmersbox.scrcpyviewer.state.EmulatorPrefs
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.devices.emulators.EmulatorRow
import com.programmersbox.scrcpyviewer.ui.devices.emulators.EmulatorsUiState
import com.programmersbox.scrcpyviewer.ui.onEdt
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Connected device rows on screen: an emulator's right-click menu has no Wi-Fi item while a real device's is unchanged,
 * and an emulator's title crossfades to its AVD's name once the launcher knows it (at once without motion). Motion is
 * passed explicitly: CI runners report reduce motion on.
 */
@OptIn(ExperimentalComposeUiApi::class)
class DeviceRowTest {
    private val phone = Device("R5CX12GDWPZ", DeviceState.Device, "device", "SM_A156U1", null, 1, false, DeviceInfo("SM-A156U1", "15", 35))
    private val wifi = Device("192.168.1.31:5555", DeviceState.Device, "device", "SM_X710", null, 2, true)
    private val emulator = Device(
        "emulator-5554", DeviceState.Device, "device", "sdk_gphone64_arm64", null, 3, false,
        DeviceInfo("sdk_gphone64_arm64", "14", 34),
    )
    private val headsUp = Avd("heads_up_api34", "heads_up_api34", 34, "14", AvdKind.Phone, Paths.get("avd", "heads_up_api34.avd"))

    private fun SceneDriver.texts(): List<String> {
        val all = mutableListOf<String>()
        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { all += it.text }
            node.children.forEach(::walk)
        }
        scene.semanticsOwners.forEach { walk(it.unmergedRootSemanticsNode) }
        return all
    }

    private fun menuTexts(device: Device): List<String> {
        val state = DevicesUiState(devices = listOf(phone, wifi, emulator), adbConnected = true)
        val actions = RowActions(onOpen = {}, onConnectWifi = {}, onDisconnect = {}, canMirror = { true })
        val d = SceneDriver(ImageComposeScene(400, 300, Density(1f)) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                Surface(Modifier.fillMaxSize()) { Column { DeviceMenu(device, state, actions, onDismiss = {}) } }
            }
        })
        try {
            d.frames(3)
            return d.texts()
        } finally {
            d.scene.close()
        }
    }

    @Test
    fun `an emulator's menu has Mirror and no Wi-Fi item`() = onEdt {
        val texts = menuTexts(emulator)
        assertTrue("Mirror" in texts, "$texts")
        assertFalse(texts.any { "Wi-Fi" in it }, "no Wi-Fi item for an emulator: $texts")
    }

    @Test
    fun `a real device's menu is unchanged - Connect over Wi-Fi on USB, Disconnect on Wi-Fi`() = onEdt {
        assertEquals(listOf("Mirror", "double-click", "Connect over Wi-Fi"), menuTexts(phone))
        assertEquals(listOf("Mirror", "double-click", "Disconnect"), menuTexts(wifi))
    }

    /** [frames]' texts; [before] is how many times "heads_up_api34" showed before the match (its Emulators row, if any). */
    private class Titles(val before: Int, val frames: List<List<String>>) {
        fun named(texts: List<String>) = texts.count { it == "heads_up_api34" } > before
    }

    /** The connected rows' texts each frame after the launcher matches emulator-5554 to heads_up_api34. */
    private fun titlesAfterMatch(motion: Boolean): Titles {
        val state = DevicesUiState(devices = listOf(phone, emulator), adbConnected = true, selectedSerial = phone.serial)
        var emulators by mutableStateOf(EmulatorsUiState(rows = listOf(EmulatorRow(headsUp, AvdState.Stopped, null, EmulatorPrefs()))))
        val d = SceneDriver(ImageComposeScene(1280, 800, Density(1f)) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    DevicesContent(
                        state, {}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {}, {}, {},
                        emulators = emulators, motion = motion,
                    )
                }
            }
        })
        try {
            d.frames(90) // the rows' launch entrance settles
            val before = d.texts()
            assertTrue("sdk_gphone64_arm64" in before, "$before")
            emulators = emulators.copy(rows = listOf(EmulatorRow(headsUp, AvdState.Running("emulator-5554"), null, EmulatorPrefs())))
            return Titles(before.count { it == "heads_up_api34" }, (1..30).map { d.frame(); d.texts() })
        } finally {
            d.scene.close()
        }
    }

    @Test
    fun `an emulator's title crossfades from its model to its AVD name`() = onEdt {
        val titles = titlesAfterMatch(motion = true)
        // Both titles are on screen together for several frames (the crossfade), then only the AVD name.
        val both = titles.frames.count { "sdk_gphone64_arm64" in it && titles.named(it) }
        assertTrue(both >= 3, "crossfades over several frames: $both")
        val last = titles.frames.last()
        assertTrue(titles.named(last) && "sdk_gphone64_arm64" !in last, "$last")
        // The serial subtitle and the real device's row are unchanged.
        assertTrue("emulator-5554" in last && "SM-A156U1" in last && "R5CX12GDWPZ" in last, "$last")
    }

    @Test
    fun `without motion an emulator's title changes at once`() = onEdt {
        val titles = titlesAfterMatch(motion = false)
        val first = titles.frames[1]
        assertTrue(titles.named(first) && "sdk_gphone64_arm64" !in first, "$first")
    }
}
