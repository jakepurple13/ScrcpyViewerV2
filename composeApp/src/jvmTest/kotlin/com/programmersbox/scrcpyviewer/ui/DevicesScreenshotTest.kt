package com.programmersbox.scrcpyviewer.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import com.programmersbox.scrcpyviewer.adb.Device
import com.programmersbox.scrcpyviewer.adb.DeviceInfo
import com.programmersbox.scrcpyviewer.adb.DeviceState
import com.programmersbox.scrcpyviewer.options.OptionCatalog
import com.programmersbox.scrcpyviewer.options.OptionGroup
import com.programmersbox.scrcpyviewer.options.OptionValues
import com.programmersbox.scrcpyviewer.presets.Preset
import com.programmersbox.scrcpyviewer.ui.devices.DevicesContent
import com.programmersbox.scrcpyviewer.ui.devices.DevicesUiState
import com.programmersbox.scrcpyviewer.ui.devices.PairingTab
import com.programmersbox.scrcpyviewer.ui.devices.DeviceMenu
import com.programmersbox.scrcpyviewer.ui.devices.RowActions
import com.programmersbox.scrcpyviewer.ui.devices.RestartAdbDialog
import com.programmersbox.scrcpyviewer.ui.devices.RestartAdbImpact
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.ui.devices.WifiPairingActions
import com.programmersbox.scrcpyviewer.ui.devices.WifiPairingDialog
import com.programmersbox.scrcpyviewer.ui.devices.WifiPairingState
import com.programmersbox.scrcpyviewer.adb.MdnsService
import com.programmersbox.scrcpyviewer.adb.MdnsType
import com.programmersbox.scrcpyviewer.adb.WirelessParsers
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertTrue

/** Excluded from the default `jvmTest` run; run with `./gradlew :composeApp:jvmTest -Pscreenshots`. */
class DevicesScreenshotTest {
    private val outDir = Paths.get(System.getProperty("user.dir")).resolve("build/screenshots")

    private val values = OptionValues(
        mapOf("max-size" to "1920", "no-audio" to null, "show-touches" to null, "stay-awake" to null, "video-codec" to "h265", "record" to ""),
    )

    private val state = DevicesUiState(
        devices = listOf(
            Device("39121FDJH004AB", DeviceState.Device, "device", "Pixel_8_Pro", null, 1, false, DeviceInfo("Pixel 8 Pro", "15", 35)),
            Device("adb-R5CT90ABCDE-xK2fPq._adb-tls-connect._tcp", DeviceState.Device, "device", "SM_X710", null, 2, true),
            Device("ZY22H8KLMN", DeviceState.Unauthorized, "unauthorized", null, null, null, false),
        ),
        selectedSerial = "39121FDJH004AB",
        visibleOptions = OptionCatalog.all,
        values = values,
        errors = values.validate(),
        presetLabel = "QA default (modified)",
        command = "scrcpy -s 39121FDJH004AB --max-size=1920 --video-codec=h265 --no-audio --show-touches --stay-awake --record",
        canMirror = false,
        adbConnected = true,
        hasBasePreset = true,
    )

    private fun render(
        name: String,
        mode: ThemeMode,
        state: DevicesUiState = this.state,
        width: Int = 1280,
        listWidthPx: Int? = null,
        detailOpen: Boolean = false,
    ) {
        val out = outDir.resolve(name)
        renderToPng(width, 800, out, settleMillis = 1_000) {
            ScrcpyViewerTheme(mode) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    DevicesContent(
                        state, {}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {}, {}, {},
                        panes = screenshotPanes(listWidthPx, detailOpen),
                    )
                }
            }
        }
        assertTrue(Files.size(out) > 10_000, "$name should not be blank")
    }

    @Test
    fun `devices light wide divider`() = render("devices-light-divider.png", ThemeMode.Light, listWidthPx = 460)

    @Test
    fun `devices dark wide divider`() = render("devices-dark-divider.png", ThemeMode.Dark, listWidthPx = 460)

    @Test
    fun `devices narrow list`() = render("devices-light-narrow-list.png", ThemeMode.Light, width = 760)

    @Test
    fun `devices narrow detail`() = render("devices-light-narrow-detail.png", ThemeMode.Light, width = 760, detailOpen = true)

    @Test
    fun `devices dark narrow detail`() = render("devices-dark-narrow-detail.png", ThemeMode.Dark, width = 760, detailOpen = true)

    @Test
    fun `devices light`() = render("devices-light.png", ThemeMode.Light)

    @Test
    fun `devices dark`() = render("devices-dark.png", ThemeMode.Dark)

    @Test
    fun `devices light filtered`() = render(
        "devices-light-audio.png", ThemeMode.Light,
        state.copy(groupFilter = OptionGroup.Audio, visibleOptions = OptionCatalog.all.filter { it.group == OptionGroup.Audio }),
    )

    @Test
    fun `devices persistence error`() = render(
        "devices-light-persistence-error.png", ThemeMode.Light,
        state.copy(persistenceError = "Could not save devices.json: Permission denied"),
    )

    @Test
    fun `devices light preset menu`() {
        val out = outDir.resolve("devices-light-preset-menu.png")
        renderToPng(1280, 800, out) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    DevicesContent(
                        state.copy(
                            presets = listOf(
                                Preset("n", "New preset", options = OptionValues(emptyMap())),
                                Preset("r", "recording", options = values),
                            ),
                            basePresetId = "r",
                            presetLabel = "recording",
                        ),
                        {}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {}, {}, {},
                        presetMenuOpen = true,
                    )
                }
            }
        }
        assertTrue(Files.size(out) > 10_000)
    }

    /** Popups do not render offscreen, so the menu's items are drawn on a menu-like surface instead. */
    private fun renderMenu(name: String, mode: ThemeMode, serial: String, state: DevicesUiState = this.state) {
        val out = outDir.resolve(name)
        val device = state.devices.first { it.serial == serial }
        val actions = RowActions(onOpen = {}, onConnectWifi = {}, onDisconnect = {}, canMirror = { it == "39121FDJH004AB" })
        renderToPng(400, 200, out, settleMillis = 500) {
            ScrcpyViewerTheme(mode) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    Surface(
                        Modifier.padding(24.dp).width(260.dp),
                        shape = MaterialTheme.shapes.extraSmall,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shadowElevation = 3.dp,
                    ) {
                        Column(Modifier.padding(vertical = 8.dp)) { DeviceMenu(device, state, actions, onDismiss = {}) }
                    }
                }
            }
        }
        assertTrue(Files.size(out) > 3_000, "$name should not be blank")
    }

    @Test
    fun `devices context menu usb`() = renderMenu("devices-light-menu-usb.png", ThemeMode.Light, "39121FDJH004AB")

    @Test
    fun `devices context menu usb switching dark`() =
        renderMenu("devices-dark-menu-switching.png", ThemeMode.Dark, "39121FDJH004AB", state.copy(switching = setOf("39121FDJH004AB")))

    @Test
    fun `devices context menu wifi`() =
        renderMenu("devices-light-menu-wifi.png", ThemeMode.Light, "adb-R5CT90ABCDE-xK2fPq._adb-tls-connect._tcp")

    private val pairing = WifiPairingState(
        qrName = "scrcpy-viewer-7QK2ab",
        qrPassword = "9Xc4mT28aa",
        qrPayload = WirelessParsers.qrPayload("scrcpy-viewer-7QK2ab", "9Xc4mT28aa"),
        pairingServices = listOf(
            MdnsService("adb-3A111FDJG0029S-AbCdEf", MdnsType.Pairing, "192.168.1.31:41877"),
            MdnsService("adb-27KX1A2B3C-QwErTy", MdnsType.Pairing, "192.168.1.52:38211"),
        ),
        chosen = "192.168.1.31:41877",
        code = "482",
        ip = "192.168.1.31",
        pairPort = "41877",
        pairCode = "482913",
    )

    private fun renderDialog(name: String, mode: ThemeMode, state: WifiPairingState) {
        val out = outDir.resolve(name)
        renderToPng(1280, 800, out, settleMillis = 1_000) {
            ScrcpyViewerTheme(mode) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    WifiPairingDialog(state, WifiPairingActions(), onDismiss = {})
                }
            }
        }
        assertTrue(Files.size(out) > 10_000, "$name should not be blank")
    }

    private fun renderRestart(name: String, mode: ThemeMode, impact: RestartAdbImpact) {
        val out = outDir.resolve(name)
        renderToPng(1280, 800, out, settleMillis = 1_000) {
            ScrcpyViewerTheme(mode) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    DevicesContent(
                        state, {}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {}, {}, {},
                        panes = screenshotPanes(null, false),
                    )
                    RestartAdbDialog(impact, onConfirm = {}, onDismiss = {})
                }
            }
        }
        assertTrue(Files.size(out) > 10_000, "$name should not be blank")
    }

    @Test
    fun `restart adb dialog with mirrors`() = renderRestart(
        "restart-adb-light-mirrors.png", ThemeMode.Light,
        RestartAdbImpact(mirrors = listOf("Pixel 8 Pro", "SM X710"), wifiDevices = listOf("SM X710")),
    )

    @Test
    fun `restart adb dialog wifi only dark`() = renderRestart(
        "restart-adb-dark-wifi.png", ThemeMode.Dark,
        RestartAdbImpact(mirrors = emptyList(), wifiDevices = listOf("SM X710")),
    )

    @Test
    fun `devices restarting adb`() = render("devices-light-restarting.png", ThemeMode.Light, state.copy(restartingAdb = true))

    @Test
    fun `wifi pairing dialog qr`() = renderDialog("wifi-light-qr.png", ThemeMode.Light, pairing)

    @Test
    fun `wifi pairing dialog qr dark`() = renderDialog("wifi-dark-qr.png", ThemeMode.Dark, pairing)

    @Test
    fun `wifi pairing dialog code`() = renderDialog("wifi-light-code.png", ThemeMode.Light, pairing.copy(tab = PairingTab.Code))

    @Test
    fun `wifi pairing dialog ip`() = renderDialog(
        "wifi-dark-ip.png", ThemeMode.Dark,
        pairing.copy(tab = PairingTab.Ip, error = "Wrong pairing code. Check the code on the phone and try again."),
    )

    /**
     * Renders [content] at each of [steps] (frame time in ms, then an action run before that frame), writing
     * `<prefix>-<ms>.png` per step, so mid-animation frames can be inspected.
     */
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun renderFrames(
        prefix: String,
        width: Int,
        height: Int,
        steps: List<Pair<Long, () -> Unit>>,
        content: @androidx.compose.runtime.Composable () -> Unit,
    ) {
        javax.swing.SwingUtilities.invokeAndWait {
            val scene = androidx.compose.ui.ImageComposeScene(width, height, androidx.compose.ui.unit.Density(1f), content = content)
            try {
                scene.render(0)
                steps.forEach { (ms, action) ->
                    action()
                    androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
                    val image = scene.render(ms * 1_000_000)
                    val out = outDir.resolve("motion/$prefix-${ms.toString().padStart(5, '0')}.png")
                    Files.createDirectories(out.parent)
                    Files.write(out, image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes)
                }
            } finally {
                scene.close()
            }
        }
    }

    private fun frames(vararg ms: Long, at: Map<Long, () -> Unit> = emptyMap()): List<Pair<Long, () -> Unit>> =
        ms.map { it to (at[it] ?: {}) }

    @Test
    fun `motion pairing dialog grows from its button`() {
        val anchor = com.programmersbox.scrcpyviewer.ui.devices.PairDialogAnchor()
        var open by androidx.compose.runtime.mutableStateOf(false)
        renderFrames(
            "pairing", 1280, 800,
            frames(
                100, 116, 132, 148, 160, 220, 300, 400, 560, 900, 1000, 1050, 1100, 1150, 1200, 1300, 1500,
                at = mapOf(100L to { open = true }, 1000L to { open = false }),
            ),
        ) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    DevicesContent(
                        state, {}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {}, {}, {},
                        panes = screenshotPanes(null, false),
                        pairAnchor = anchor,
                    )
                    anchor.visibility.targetState = open
                    if (anchor.showing) {
                        WifiPairingDialog(pairing, WifiPairingActions(), onDismiss = {}, visibleState = anchor.visibility, anchor = anchor)
                    }
                }
            }
        }
    }

    @Test
    fun `motion device list arrival, chip and restart`() {
        val usb = state.devices[0]
        var devices by androidx.compose.runtime.mutableStateOf(state.devices.take(2))
        var switching by androidx.compose.runtime.mutableStateOf(emptySet<String>())
        var restarting by androidx.compose.runtime.mutableStateOf(false)
        val wifi = Device("192.168.1.31:5555", DeviceState.Device, "device", "Pixel_8_Pro", null, 9, true)
        renderFrames(
            "list", 560, 420,
            frames(
                50, 800, 860, 950, 1100, 1300, 1700, 2300, 2400, 2460, 2560, 2700, 3000, 3150, 3300, 3450, 3800,
                4000, 4350, 4700, 5000, 5200, 5400, 5600, 5800, 6000, 6200, 6300, 6400, 6500, 6600, 6800, 7000, 7500,
                9000,
                at = mapOf(
                    // Past the first-load grace (wall clock), so the new device counts as an arrival.
                    800L to { Thread.sleep(700); devices = state.devices },
                    2400L to { devices = state.devices.take(2) },
                    3000L to { switching = setOf(usb.serial) },
                    3800L to { switching = emptySet(); devices = state.devices.take(2) + wifi },
                    4000L to { restarting = true },
                    5000L to { restarting = false },
                ),
            ),
        ) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    DevicesContent(
                        state.copy(devices = devices, switching = switching, restartingAdb = restarting),
                        {}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {}, {}, {},
                        panes = screenshotPanes(560, false),
                    )
                }
            }
        }
    }
}
