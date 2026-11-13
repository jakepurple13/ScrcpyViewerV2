package com.programmersbox.scrcpyviewer.ui.tray

import com.programmersbox.scrcpyviewer.adb.Device
import com.programmersbox.scrcpyviewer.adb.DeviceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class TrayMenuModelTest {
    private fun device(serial: String, state: DeviceState = DeviceState.Device) =
        Device(serial, state, "raw", null, null, null, false)

    private fun menu(devices: List<Device>, mirroring: Set<String> = emptySet()) =
        trayMenu(devices, mirroring) { "T-${it.serial}" }

    private val tail = listOf(
        TrayRow.Divider,
        TrayRow.Command("Open Scrcpy Vwr", TrayAction.OpenMain),
        TrayRow.Command("Settings…", TrayAction.Settings),
        TrayRow.Divider,
        TrayRow.Command("Quit Scrcpy Vwr", TrayAction.Quit),
    )

    @Test
    fun headerForZeroOneTwoDevices() {
        assertEquals(TrayRow.Header("No devices connected"), menu(emptyList()).first())
        assertEquals(TrayRow.Header("Scrcpy Vwr · 1 device"), menu(listOf(device("a"))).first())
        assertEquals(TrayRow.Header("Scrcpy Vwr · 2 devices"), menu(listOf(device("a"), device("b"))).first())
    }

    @Test
    fun emptyMenuHasOnlyHeaderAndTail() {
        assertEquals(listOf<TrayRow>(TrayRow.Header("No devices connected")) + tail, menu(emptyList()))
    }

    @Test
    fun readyDeviceMirrors() {
        assertEquals(TrayRow.DeviceRow("a", "⚪ T-a — Mirror", TrayAction.Mirror), menu(listOf(device("a")))[2])
    }

    @Test
    fun mirroringDeviceFocuses() {
        assertEquals(
            TrayRow.DeviceRow("a", "🟢 T-a — Mirroring", TrayAction.Focus),
            menu(listOf(device("a")), setOf("a"))[2],
        )
    }

    @Test
    fun unauthorizedHasNoAction() {
        assertEquals(
            TrayRow.DeviceRow("a", "🟠 T-a — Accept the USB debugging prompt", null),
            menu(listOf(device("a", DeviceState.Unauthorized)))[2],
        )
    }

    @Test
    fun openMirrorFocusesWhateverTheAdbState() {
        // A window whose device went unauthorized or offline can still be reached from the menu.
        DeviceState.entries.forEach { state ->
            assertEquals(
                TrayRow.DeviceRow("a", "🟢 T-a — Mirroring", TrayAction.Focus),
                menu(listOf(device("a", state)), setOf("a"))[2],
                "state $state",
            )
        }
    }

    @Test
    fun offlineHasNoAction() {
        assertEquals(
            TrayRow.DeviceRow("a", "⚫ T-a — Offline", null),
            menu(listOf(device("a", DeviceState.Offline)))[2],
        )
    }

    @Test
    fun orderFollowsDeviceList() {
        val rows = menu(listOf(device("z"), device("a"))).filterIsInstance<TrayRow.DeviceRow>()
        assertEquals(listOf("z", "a"), rows.map { it.serial })
    }

    @Test
    fun deviceMenuEndsWithTail() {
        assertEquals(tail, menu(listOf(device("a"))).takeLast(tail.size))
    }

    @Test
    fun equalInputsGiveEqualModels() {
        assertEquals(menu(listOf(device("a")), setOf("a")), menu(listOf(device("a")), setOf("a")))
    }

    @Test
    fun sameLabelsWithDifferentSerialsGiveDifferentModels() {
        // The native menu hash ignores click targets, so the rows themselves are the rebuild key.
        assertNotEquals(menu(listOf(device("a"), device("b"))), menu(listOf(device("b"), device("a"))))
        val sameTitle = { _: Device -> "Pixel 7" }
        assertNotEquals(
            trayMenu(listOf(device("a")), emptySet(), sameTitle),
            trayMenu(listOf(device("b")), emptySet(), sameTitle),
        )
    }

    @Test
    fun mirrorWithoutDeviceRowStaysReachable() {
        val rows = menu(listOf(device("a")), setOf("gone2", "a", "gone1")).filterIsInstance<TrayRow.DeviceRow>()
        assertEquals(
            listOf(
                TrayRow.DeviceRow("a", "🟢 T-a — Mirroring", TrayAction.Focus),
                TrayRow.DeviceRow("gone2", "🟢 gone2 — Mirroring", TrayAction.Focus),
                TrayRow.DeviceRow("gone1", "🟢 gone1 — Mirroring", TrayAction.Focus),
            ),
            rows,
        )
    }
}
