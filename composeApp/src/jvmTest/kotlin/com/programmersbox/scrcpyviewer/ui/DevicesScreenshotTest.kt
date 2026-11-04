package com.programmersbox.scrcpyviewer.ui

import androidx.compose.foundation.layout.fillMaxSize
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
}
