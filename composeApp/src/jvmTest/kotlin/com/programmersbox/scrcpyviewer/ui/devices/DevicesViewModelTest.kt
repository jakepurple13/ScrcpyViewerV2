package com.programmersbox.scrcpyviewer.ui.devices

import com.programmersbox.scrcpyviewer.adb.Device
import com.programmersbox.scrcpyviewer.adb.DeviceInfo
import com.programmersbox.scrcpyviewer.adb.DeviceState
import com.programmersbox.scrcpyviewer.adb.AdbOutcome
import com.programmersbox.scrcpyviewer.adb.FakeWirelessAdb
import com.programmersbox.scrcpyviewer.adb.SwitchResult
import com.programmersbox.scrcpyviewer.adb.WirelessAdb
import com.programmersbox.scrcpyviewer.ui.mirror.MirrorRequests
import com.programmersbox.scrcpyviewer.ui.mirror.OpenMirrors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import com.programmersbox.scrcpyviewer.options.OptionGroup
import com.programmersbox.scrcpyviewer.options.OptionValues
import com.programmersbox.scrcpyviewer.presets.Preset
import com.programmersbox.scrcpyviewer.presets.PresetStore
import com.programmersbox.scrcpyviewer.state.AppSettings
import com.programmersbox.scrcpyviewer.state.DeviceOptionsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DevicesViewModelTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val dir = Files.createTempDirectory("devices-vm")
    private val presets = PresetStore(dir.resolve("presets.json"), scope)
    private val options = DeviceOptionsStore(dir.resolve("devices.json"), presets, scope)
    private val settings = AppSettings(dir.resolve("settings.json"))

    private fun device(serial: String, state: DeviceState = DeviceState.Device, model: String? = null, info: DeviceInfo? = null) =
        Device(serial, state, state.name.lowercase(), model, null, null, false, info)

    private val ready = device("R1")
    private val readyToo = device("R2")
    private val unauthorized = device("U1", DeviceState.Unauthorized)

    private val devices = MutableStateFlow(listOf(unauthorized, ready, readyToo))
    private val connected = MutableStateFlow(true)
    private val mirrorRequests = MutableSharedFlow<String>(replay = 8)

    private val wireless = FakeWirelessAdb()

    /** Serials with an open mirror window. */
    private var openMirrors: Set<String> = emptySet()

    private fun vm(options: DeviceOptionsStore = this.options, wireless: WirelessAdb = this.wireless) =
        DevicesViewModel(
            DeviceSelection(devices, connected, scope), options, presets, MirrorRequests(mirrorRequests), wireless,
            OpenMirrors { openMirrors }, settings, scope,
        )

    private val pixel = device("R1", info = DeviceInfo("Pixel 8 Pro", "15", 35, "R1"))
    private val usbAbc = device("ABC")
    private val tcpAbc = Device("192.168.1.31:5555", DeviceState.Device, "device", null, null, 9, true, DeviceInfo("P", "15", 35, "ABC"))

    /** Collects notices from [vm] into a list (Unconfined scope, so emission is synchronous). */
    private fun notices(vm: DevicesViewModel): List<WifiNotice> {
        val out = java.util.Collections.synchronizedList(ArrayList<WifiNotice>())
        scope.launch { vm.notices.collect { out += it } }
        return out
    }

    @Test
    fun `wifi device shares options with its usb twin`() {
        devices.value = listOf(usbAbc)
        val vm = vm()
        vm.toggle("no-audio", true)
        devices.value = listOf(usbAbc, tcpAbc)
        vm.select(tcpAbc.serial)
        assertEquals("ABC", vm.selectedOptionsKey.value)
        assertTrue("no-audio" in vm.state.value.values.entries)
    }

    @Test
    fun `wifi device without info uses its own key then switches`() {
        devices.value = listOf(usbAbc)
        val vm = vm()
        vm.toggle("no-audio", true)
        devices.value = listOf(usbAbc, tcpAbc.copy(info = null))
        vm.select(tcpAbc.serial)
        assertEquals("192.168.1.31:5555", vm.selectedOptionsKey.value)
        assertFalse("no-audio" in vm.state.value.values.entries)
        devices.value = listOf(usbAbc, tcpAbc)
        assertEquals("ABC", vm.selectedOptionsKey.value)
        assertTrue("no-audio" in vm.state.value.values.entries)
    }

    @Test
    fun `connectOverWifi success emits notice with mirror action`() {
        devices.value = listOf(pixel)
        wireless.switchResult = SwitchResult.Connected("192.168.1.31:5555")
        val vm = vm()
        val seen = notices(vm)
        vm.connectOverWifi("R1")
        assertEquals(listOf(WifiNotice("Pixel 8 Pro connected at 192.168.1.31:5555", "192.168.1.31:5555")), seen)
    }

    @Test
    fun `connectOverWifi failures`() {
        devices.value = listOf(pixel)
        val vm = vm()
        val seen = notices(vm)
        wireless.switchResult = SwitchResult.NotOnWifi
        vm.connectOverWifi("R1")
        wireless.switchResult = SwitchResult.TcpipFailed("error: closed")
        vm.connectOverWifi("R1")
        wireless.switchResult = SwitchResult.ConnectFailed("192.168.1.31:5555", "Connection refused")
        vm.connectOverWifi("R1")
        assertEquals(
            listOf(
                "Pixel 8 Pro isn't on Wi-Fi. Connect it to the same network as this computer.",
                "Couldn't switch Pixel 8 Pro to Wi-Fi: error: closed",
                "Couldn't connect to 192.168.1.31:5555: Connection refused",
            ),
            seen.map { it.text },
        )
    }

    @Test
    fun `connectOverWifi ignores a second click while running`() = runBlocking {
        devices.value = listOf(pixel)
        val gate = CompletableDeferred<Unit>()
        val slow = object : FakeWirelessAdb() {
            override suspend fun switchToWifi(serial: String): SwitchResult {
                calls += "switch $serial"
                gate.await()
                return SwitchResult.NotOnWifi
            }
        }
        val vm = vm(wireless = slow)
        vm.connectOverWifi("R1")
        vm.connectOverWifi("R1")
        assertEquals(1, slow.calls.count { it.startsWith("switch") })
        assertEquals(setOf("R1"), vm.state.value.switching)
        gate.complete(Unit)
        withTimeout(2000) { while (vm.state.value.switching.isNotEmpty()) delay(10) }
    }

    @Test
    fun `notice mirror finds the device under its mdns serial`() = runBlocking {
        val mdns = Device("adb-ABC-x._adb-tls-connect._tcp", DeviceState.Device, "device", null, null, 9, true)
        devices.value = listOf(mdns)
        val vm = vm()
        vm.mirrorFromNotice(WifiNotice("ABC connected over Wi-Fi", "192.168.1.31:37215", mdnsName = "adb-ABC-x"))
        assertEquals("adb-ABC-x._adb-tls-connect._tcp", withTimeout(1000) { mirrorRequests.first() })
    }

    @Test
    fun `blocked connect offers Restart adb and restarting reports back`() {
        devices.value = listOf(pixel)
        wireless.switchResult = SwitchResult.ConnectFailed("10.0.24.47:5555", "No route to host", restartCanFix = true)
        val vm = vm()
        val seen = notices(vm)
        vm.connectOverWifi("R1")
        assertEquals(true, seen.single().restartAdb)
        vm.restartAdb()
        assertEquals("restart", wireless.calls.last())
        assertEquals("Restarted adb. Try connecting again.", seen.last().text)
    }

    @Test
    fun `restart button restarts at once when nothing would be lost`() {
        devices.value = listOf(pixel)
        val vm = vm()
        val seen = notices(vm)
        vm.requestRestartAdb()
        assertNull(vm.state.value.restartConfirm)
        assertEquals(listOf("restart"), wireless.calls)
        assertEquals(true, vm.state.value.restartSucceeded)
        assertEquals("Restarted adb.", seen.single().text)
        assertFalse(vm.state.value.restartingAdb)
    }

    @Test
    fun `restart button asks first and lists open mirrors and wifi devices`() {
        devices.value = listOf(pixel, tcpAbc)
        openMirrors = setOf("R1", "gone-serial")
        val vm = vm()
        vm.requestRestartAdb()
        assertEquals(RestartAdbImpact(mirrors = listOf("Pixel 8 Pro", "gone-serial"), wifiDevices = listOf("P")), vm.state.value.restartConfirm)
        assertTrue(wireless.calls.isEmpty())
    }

    @Test
    fun `restart asks when only a wifi device would drop`() {
        devices.value = listOf(pixel, tcpAbc)
        val vm = vm()
        vm.requestRestartAdb()
        assertEquals(RestartAdbImpact(mirrors = emptyList(), wifiDevices = listOf("P")), vm.state.value.restartConfirm)
    }

    @Test
    fun `cancelling the restart dialog does not restart`() {
        devices.value = listOf(pixel)
        openMirrors = setOf("R1")
        val vm = vm()
        vm.requestRestartAdb()
        vm.cancelRestartAdb()
        assertNull(vm.state.value.restartConfirm)
        assertTrue(wireless.calls.isEmpty())
    }

    @Test
    fun `confirming the restart dialog restarts`() {
        devices.value = listOf(pixel)
        openMirrors = setOf("R1")
        val vm = vm()
        val seen = notices(vm)
        vm.requestRestartAdb()
        vm.confirmRestartAdb()
        assertNull(vm.state.value.restartConfirm)
        assertEquals(listOf("restart"), wireless.calls)
        assertEquals("Restarted adb.", seen.single().text)
    }

    @Test
    fun `restart failure is reported`() {
        devices.value = listOf(pixel)
        wireless.restartError = java.io.IOException("adb start-server failed (1): cannot bind")
        val vm = vm()
        val seen = notices(vm)
        vm.requestRestartAdb()
        assertEquals("Couldn't restart adb: adb start-server failed (1): cannot bind", seen.single().text)
        assertFalse(vm.state.value.restartingAdb)
        assertEquals(false, vm.state.value.restartSucceeded)
    }

    @Test
    fun `restart is ignored while one is running`() = runBlocking {
        devices.value = listOf(pixel)
        val gate = CompletableDeferred<Unit>()
        val slow = object : FakeWirelessAdb() {
            override suspend fun restartServer() {
                super.restartServer()
                gate.await()
            }
        }
        val vm = vm(wireless = slow)
        vm.requestRestartAdb()
        assertTrue(vm.state.value.restartingAdb)
        vm.requestRestartAdb()
        vm.restartAdb()
        gate.complete(Unit)
        withTimeout(2000) { while (vm.state.value.restartingAdb) delay(10) }
        assertEquals(listOf("restart"), slow.calls)
    }

    @Test
    fun `disconnect failure emits notice`() {
        devices.value = listOf(pixel)
        wireless.disconnectResult = AdbOutcome.Failed("no such device")
        val vm = vm()
        val seen = notices(vm)
        vm.disconnect("R1")
        assertEquals(listOf("disconnect R1"), wireless.calls)
        assertEquals(listOf("Couldn't disconnect Pixel 8 Pro: no such device"), seen.map { it.text })
    }

    @Test
    fun `isOnWifi matches the usb device to its wifi twin`() {
        assertTrue(isOnWifi(usbAbc, listOf(usbAbc, tcpAbc)))
        assertFalse(isOnWifi(usbAbc, listOf(usbAbc)))
        assertFalse(isOnWifi(tcpAbc, listOf(usbAbc, tcpAbc)))
    }

    @Test
    fun `first ready device auto-selected`() {
        val vm = vm()
        assertEquals("R1", vm.state.value.selectedSerial)
        assertEquals("R1", vm.selectedSerial.value)
        assertTrue(vm.state.value.canMirror)
    }

    @Test
    fun `selection is kept and falls back when device disappears`() {
        val vm = vm()
        vm.select("R2")
        assertEquals("R2", vm.state.value.selectedSerial)
        devices.value = listOf(ready)
        assertEquals("R1", vm.state.value.selectedSerial)
        devices.value = emptyList()
        assertNull(vm.state.value.selectedSerial)
        assertFalse(vm.state.value.canMirror)
    }

    @Test
    fun `selecting unauthorized device disables mirror`() {
        val vm = vm()
        vm.select("U1")
        assertEquals("U1", vm.state.value.selectedSerial)
        assertFalse(vm.state.value.canMirror)
        vm.mirror()
        assertEquals(emptyList(), mirrorRequests.replayCache)
    }

    @Test
    fun `mirror emits serial of selected device and openDevice selects first`() {
        val vm = vm()
        vm.mirror()
        vm.openDevice("R2")
        assertEquals(listOf("R1", "R2"), mirrorRequests.replayCache)
        assertEquals("R2", vm.state.value.selectedSerial)
    }

    @Test
    fun `search filters by label and flag`() {
        val vm = vm()
        vm.setQuery("touch")
        val flags = vm.state.value.visibleOptions.map { it.flag }
        assertTrue("show-touches" in flags)
        assertTrue("no-audio" !in flags)
        vm.setQuery("NO AUDIO")
        assertTrue("no-audio" in vm.state.value.visibleOptions.map { it.flag })
        vm.setQuery("")
        assertTrue(vm.state.value.visibleOptions.size > 50)
    }

    @Test
    fun `group filter`() {
        val vm = vm()
        vm.setGroup(OptionGroup.Audio)
        val visible = vm.state.value.visibleOptions
        assertTrue(visible.isNotEmpty())
        assertTrue(visible.all { it.group == OptionGroup.Audio })
        vm.setGroup(null)
        assertTrue(vm.state.value.visibleOptions.any { it.group != OptionGroup.Audio })
    }

    @Test
    fun `toggle updates command`() {
        val vm = vm()
        assertEquals("scrcpy -s R1", vm.state.value.command)
        vm.toggle("no-audio", true)
        assertEquals("scrcpy -s R1 --no-audio", vm.state.value.command)
        vm.toggle("max-size", true)
        vm.setValue("max-size", "1024")
        assertEquals("scrcpy -s R1 --max-size=1024 --no-audio", vm.state.value.command)
        vm.toggle("no-audio", false)
        assertEquals("scrcpy -s R1 --max-size=1024", vm.state.value.command)
        assertTrue(vm.state.value.canMirror)
    }

    @Test
    fun `toggling a choice option selects its first value`() {
        val vm = vm()
        vm.toggle("video-codec", true)
        assertEquals("h264", vm.state.value.values.entries["video-codec"])
        assertTrue(vm.state.value.errors.isEmpty())
    }

    @Test
    fun `invalid value disables mirror and shows error`() {
        val vm = vm()
        vm.toggle("max-size", true)
        assertFalse(vm.state.value.canMirror)
        assertTrue("max-size" in vm.state.value.errors)
        vm.setValue("max-size", "abc")
        assertFalse(vm.state.value.canMirror)
        assertEquals("Expected a whole number", vm.state.value.errors["max-size"])
        vm.setValue("max-size", "800")
        assertTrue(vm.state.value.errors.isEmpty())
        assertTrue(vm.state.value.canMirror)
    }

    @Test
    fun `preset label shows modified`() {
        presets.upsert(Preset("q", "QA default", options = OptionValues(mapOf("no-audio" to null))))
        presets.setDefault("q")
        val vm = vm()
        assertEquals("QA default", vm.state.value.presetLabel)
        assertTrue(vm.state.value.hasBasePreset)
        vm.toggle("show-touches", true)
        assertEquals("QA default (modified)", vm.state.value.presetLabel)
        vm.save()
        assertEquals("QA default", vm.state.value.presetLabel)
    }

    @Test
    fun `no preset label and save as new`() {
        val vm = vm()
        assertEquals("No preset", vm.state.value.presetLabel)
        assertFalse(vm.state.value.hasBasePreset)
        vm.toggle("no-audio", true)
        vm.saveAsNew("Mine")
        assertEquals("Mine", vm.state.value.presetLabel)
        assertEquals(1, presets.presets.value.size)
    }

    @Test
    fun `summary and titles`() {
        assertEquals("2 ready · 1 needs attention", deviceSummary(listOf(ready, readyToo, unauthorized)))
        assertEquals("1 ready · 2 need attention", deviceSummary(listOf(ready, unauthorized, device("O", DeviceState.Offline))))
        assertEquals("1 ready", deviceSummary(listOf(ready)))
        assertEquals("No devices", deviceSummary(emptyList()))
        assertEquals("Pixel 8", deviceTitle(device("S", model = "Pixel_6", info = DeviceInfo("Pixel 8", "14", 34))))
        assertEquals("Pixel 6", deviceTitle(device("S", model = "Pixel_6")))
        assertEquals("S", deviceTitle(device("S")))
    }

    @Test
    fun `openDevice on unauthorized selects but emits nothing`() {
        val vm = vm()
        vm.openDevice("U1")
        assertEquals("U1", vm.state.value.selectedSerial)
        assertEquals(emptyList(), mirrorRequests.replayCache)
    }

    @Test
    fun `openDevice on offline device emits nothing`() {
        devices.value = devices.value + device("O1", DeviceState.Offline)
        val vm = vm()
        vm.openDevice("O1")
        assertEquals("O1", vm.state.value.selectedSerial)
        assertEquals(emptyList(), mirrorRequests.replayCache)
    }

    @Test
    fun `invalid value blocks openDevice and mirror`() {
        val vm = vm()
        vm.toggle("max-size", true)
        vm.setValue("max-size", "abc")
        vm.mirror()
        vm.openDevice("R1")
        assertEquals(emptyList(), mirrorRequests.replayCache)
    }

    @Test
    fun `isModified follows edits`() {
        presets.upsert(Preset("q", "QA", options = OptionValues(mapOf("no-audio" to null))))
        presets.setDefault("q")
        val vm = vm()
        assertFalse(vm.state.value.isModified)
        vm.toggle("show-touches", true)
        assertTrue(vm.state.value.isModified)
    }

    private val qaPreset = Preset("q", "QA default", options = OptionValues(mapOf("no-audio" to null)))
    private val recording = Preset("r", "recording", options = OptionValues(mapOf("record" to "out.mp4")))

    @Test
    fun `applyPreset swaps values and label and is not modified`() {
        presets.upsert(qaPreset)
        presets.upsert(recording)
        val vm = vm()
        vm.applyPreset("q")
        vm.toggle("show-touches", true)
        assertTrue(vm.state.value.isModified)
        vm.applyPreset("r")
        val st = vm.state.value
        assertEquals(recording.options, st.values)
        assertEquals("recording", st.presetLabel)
        assertEquals("r", st.basePresetId)
        assertFalse(st.isModified)
        assertEquals(listOf("QA default", "recording"), st.presets.map { it.name })
    }

    @Test
    fun `applyPreset null keeps values and shows No preset`() {
        presets.upsert(qaPreset)
        val vm = vm()
        vm.applyPreset("q")
        vm.applyPreset(null)
        val st = vm.state.value
        assertEquals(qaPreset.options, st.values)
        assertEquals("No preset", st.presetLabel)
        assertNull(st.basePresetId)
        assertFalse(st.hasBasePreset)
    }

    @Test
    fun `applyPreset does nothing with no selection`() {
        presets.upsert(qaPreset)
        devices.value = emptyList()
        val vm = vm()
        vm.applyPreset("q")
        assertNull(vm.state.value.selectedSerial)
        assertEquals("No preset", vm.state.value.presetLabel)
        devices.value = listOf(ready)
        assertEquals(OptionValues(emptyMap()), vm.state.value.values)
    }

    @Test
    fun `a devices json that cannot be saved shows in the state`() {
        // A file where the data directory should be: every save fails.
        val blocker = Files.writeString(dir.resolve("blocker"), "x")
        val broken = DeviceOptionsStore(blocker.resolve("devices.json"), presets, scope)
        val vm = vm(options = broken)
        vm.toggle("no-audio", true)
        assertTrue(vm.state.value.persistenceError?.contains("devices.json") == true, "${vm.state.value.persistenceError}")
    }

    @Test
    fun `device options open in the simple view by default`() {
        assertFalse(vm().state.value.advanced)
    }

    @Test
    fun `choosing the advanced view is saved and kept across device switches`() {
        val vm = vm()
        vm.setAdvanced(true)
        assertTrue(vm.state.value.advanced)
        vm.select(readyToo.serial)
        assertTrue(vm.state.value.advanced)
        assertTrue(AppSettings(dir.resolve("settings.json")).settings.value.advancedDeviceOptions)
        vm.setAdvanced(false)
        assertFalse(vm.state.value.advanced)
        assertFalse(AppSettings(dir.resolve("settings.json")).settings.value.advancedDeviceOptions)
    }
}
