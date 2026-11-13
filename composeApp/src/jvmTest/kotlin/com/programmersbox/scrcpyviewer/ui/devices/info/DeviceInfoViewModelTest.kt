package com.programmersbox.scrcpyviewer.ui.devices.info

import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.adb.Device
import com.programmersbox.scrcpyviewer.adb.DeviceInfo
import com.programmersbox.scrcpyviewer.adb.DeviceState
import com.programmersbox.scrcpyviewer.info.BatteryHealth
import com.programmersbox.scrcpyviewer.info.BatteryInfo
import com.programmersbox.scrcpyviewer.info.ChargeStatus
import com.programmersbox.scrcpyviewer.info.DeviceInfoReader
import com.programmersbox.scrcpyviewer.info.LiveInfo
import com.programmersbox.scrcpyviewer.info.NetworkInfo
import com.programmersbox.scrcpyviewer.info.PowerSource
import com.programmersbox.scrcpyviewer.info.SoftwareInfo
import com.programmersbox.scrcpyviewer.info.StaticInfo
import com.programmersbox.scrcpyviewer.state.AppSettings
import com.programmersbox.scrcpyviewer.ui.devices.DeviceSelection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeviceInfoViewModelTest {
    private val dir = Files.createTempDirectory("device-info-vm")
    private val settings = AppSettings(dir.resolve("settings.json"))

    private fun device(serial: String, state: DeviceState = DeviceState.Device, model: String? = null) =
        Device(serial, state, state.name.lowercase(), model, null, null, false, null)

    private val devices = MutableStateFlow(listOf(device("R1", model = "Pixel_8"), device("R2")))
    private val connected = MutableStateFlow(true)

    private fun static(serial: String, version: Int = 1) = StaticInfo(
        SoftwareInfo("$serial-v$version", null, null, null, null, null, null, null, null),
        display = null,
        storage = null,
        network = NetworkInfo(null, null, null),
    )

    private fun live(level: Int) = LiveInfo(
        BatteryInfo(level, ChargeStatus.Charging, PowerSource.Usb, null, null, BatteryHealth.Good),
        memory = null,
    )

    /** Scripted reader: [onStatic] and [onLive] get the call number (1-based) and return or throw. */
    private class FakeReader(serial: String) : DeviceInfoReader(AdbClient(adbPath = { error("no adb") }), serial) {
        var staticCalls = 0
        var liveCalls = 0
        var onStatic: suspend (Int) -> StaticInfo = { error("unscripted") }
        var onLive: suspend (Int) -> LiveInfo = { error("unscripted") }

        override suspend fun readStatic(): StaticInfo = onStatic(++staticCalls)
        override suspend fun readLive(): LiveInfo = onLive(++liveCalls)
    }

    private val readers = mutableMapOf<String, FakeReader>()

    private fun reader(serial: String): FakeReader = readers.getOrPut(serial) {
        FakeReader(serial).apply {
            onStatic = { static(serial, it) }
            onLive = { live(it) }
        }
    }

    private fun TestScope.selection() = DeviceSelection(devices, connected, backgroundScope)

    private fun TestScope.vm(selection: DeviceSelection = selection()) = DeviceInfoViewModel(
        selection, settings, DeviceInfoReaderFactory { reader(it) }, backgroundScope, clock = { testScheduler.currentTime },
    )

    @Test
    fun `does nothing until active`() = runTest {
        val r1 = reader("R1")
        val vm = vm()
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(0, r1.staticCalls)
        assertEquals(0, r1.liveCalls)
        assertEquals("R1", vm.state.value.serial)
        assertEquals("Pixel 8", vm.state.value.title)
    }

    @Test
    fun `loads static once and polls live every 5 s`() = runTest {
        val r1 = reader("R1")
        val vm = vm()
        vm.setActive(true)
        runCurrent()
        assertEquals(1, r1.staticCalls)
        assertEquals(1, r1.liveCalls)
        assertEquals(Load.Ready(static("R1")), vm.state.value.static)
        assertEquals(Load.Ready(live(1)), vm.state.value.live)
        assertEquals(0L, vm.state.value.liveUpdatedAt)
        advanceTimeBy(15_000)
        runCurrent()
        assertEquals(4, r1.liveCalls)
        assertEquals(1, r1.staticCalls)
        assertEquals(Load.Ready(live(4)), vm.state.value.live)
        assertEquals(15_000L, vm.state.value.liveUpdatedAt)
    }

    @Test
    fun `stops polling when inactive`() = runTest {
        val r1 = reader("R1")
        val vm = vm()
        vm.setActive(true)
        runCurrent()
        vm.setActive(false)
        runCurrent()
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(1, r1.liveCalls)
        assertEquals(1, r1.staticCalls)
    }

    @Test
    fun `switching device ignores late results`() = runTest {
        val gate = CompletableDeferred<Unit>()
        reader("R1").apply {
            onStatic = { gate.await(); static("R1") }
            onLive = { gate.await(); live(99) }
        }
        val selection = selection()
        val vm = vm(selection)
        vm.setActive(true)
        runCurrent()
        selection.select("R2")
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals("R2", vm.state.value.serial)
        assertEquals(Load.Ready(static("R2")), vm.state.value.static)
        assertEquals(Load.Ready(live(1)), vm.state.value.live)
    }

    @Test
    fun `switch is not delayed by a stuck read`() = runTest {
        val gate = CompletableDeferred<Unit>()
        reader("R1").apply {
            onStatic = { withContext(NonCancellable) { gate.await() }; static("R1") }
            onLive = { withContext(NonCancellable) { gate.await() }; live(99) }
        }
        val selection = selection()
        val vm = vm(selection)
        vm.setActive(true)
        runCurrent()
        selection.select("R2")
        runCurrent()
        // R1's reads are still stuck: R2 must already show.
        assertEquals("R2", vm.state.value.serial)
        assertEquals(Load.Ready(static("R2")), vm.state.value.static)
        assertEquals(Load.Ready(live(1)), vm.state.value.live)
        gate.complete(Unit)
        runCurrent()
        assertEquals(Load.Ready(static("R2")), vm.state.value.static)
        assertEquals(Load.Ready(live(1)), vm.state.value.live)
    }

    @Test
    fun `caches static per serial`() = runTest {
        val r1 = reader("R1")
        val selection = selection()
        val vm = vm(selection)
        vm.setActive(true)
        runCurrent()
        selection.select("R2")
        runCurrent()
        val refresh = CompletableDeferred<Unit>()
        r1.onStatic = { refresh.await(); static("R1", it) }
        selection.select("R1")
        runCurrent()
        // Shown from the cache before the quiet refresh finishes.
        assertEquals(Load.Ready(static("R1", 1)), vm.state.value.static)
        assertEquals(2, r1.staticCalls)
        refresh.complete(Unit)
        runCurrent()
        assertEquals(Load.Ready(static("R1", 2)), vm.state.value.static)
    }

    @Test
    fun `not ready device does not poll`() = runTest {
        devices.value = listOf(device("U1", DeviceState.Unauthorized))
        val u1 = reader("U1")
        val selection = selection()
        selection.select("U1")
        val vm = vm(selection)
        vm.setActive(true)
        runCurrent()
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals("Accept the USB debugging prompt on the device", vm.state.value.notReady)
        assertEquals(0, u1.staticCalls)
        assertEquals(0, u1.liveCalls)
    }

    @Test
    fun `offline and other states have their own message`() = runTest {
        devices.value = listOf(device("O1", DeviceState.Offline))
        val selection = selection()
        selection.select("O1")
        val vm = vm(selection)
        runCurrent()
        assertEquals("Device is offline", vm.state.value.notReady)
        devices.value = listOf(device("O1", DeviceState.Other))
        runCurrent()
        assertEquals("Device isn't ready", vm.state.value.notReady)
    }

    @Test
    fun `device turning unauthorized stops polling`() = runTest {
        val r1 = reader("R1")
        // Chosen by the user, so the selection stays on R1 instead of moving to the next ready device.
        val selection = selection()
        selection.select("R1")
        val vm = vm(selection)
        vm.setActive(true)
        runCurrent()
        assertNull(vm.state.value.notReady)
        devices.value = listOf(device("R1", DeviceState.Unauthorized), device("R2"))
        runCurrent()
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(1, r1.liveCalls)
        assertEquals("Accept the USB debugging prompt on the device", vm.state.value.notReady)
    }

    @Test
    fun `failed live keeps last values`() = runTest {
        val r1 = reader("R1").apply {
            onLive = { if (it == 2) throw IOException("adb: closed") else live(it) }
        }
        val vm = vm()
        vm.setActive(true)
        runCurrent()
        assertEquals(Load.Ready(live(1)), vm.state.value.live)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, r1.liveCalls)
        assertEquals(Load.Ready(live(1)), vm.state.value.live)
        assertEquals(true, vm.state.value.liveStale)
        assertEquals(0L, vm.state.value.liveUpdatedAt)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(Load.Ready(live(3)), vm.state.value.live)
        assertEquals(false, vm.state.value.liveStale)
    }

    @Test
    fun `failed first live shows message`() = runTest {
        reader("R1").onLive = { throw IOException("adb: closed") }
        val vm = vm()
        vm.setActive(true)
        runCurrent()
        assertEquals(Load.Failed("adb: closed"), vm.state.value.live)
    }

    @Test
    fun `failed static shows message and retry reloads`() = runTest {
        val r1 = reader("R1").apply {
            onStatic = { if (it == 1) throw IOException("adb: closed") else static("R1", it) }
        }
        val vm = vm()
        vm.setActive(true)
        runCurrent()
        assertEquals(Load.Failed("adb: closed"), vm.state.value.static)
        vm.retry()
        runCurrent()
        assertEquals(Load.Ready(static("R1", 2)), vm.state.value.static)
        assertEquals(2, r1.liveCalls)
    }

    @Test
    fun `tab is saved`() = runTest {
        val vm = vm()
        assertEquals(DetailTab.Options, vm.tab.value)
        vm.setTab(DetailTab.Info)
        runCurrent()
        assertEquals("Info", settings.settings.value.deviceDetailTab)
        assertEquals(DetailTab.Info, vm.tab.value)
        assertEquals(DetailTab.Info, vm().tab.value)
    }

    @Test
    fun `unknown tab name falls back to Options`() {
        assertEquals(DetailTab.Options, DetailTab.fromName("Apps"))
        assertEquals(DetailTab.Info, DetailTab.fromName("Info"))
    }
}
