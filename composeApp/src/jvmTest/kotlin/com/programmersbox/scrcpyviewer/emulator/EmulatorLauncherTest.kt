package com.programmersbox.scrcpyviewer.emulator

import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.adb.Device
import com.programmersbox.scrcpyviewer.adb.DeviceInfo
import com.programmersbox.scrcpyviewer.adb.DeviceState
import com.programmersbox.scrcpyviewer.adb.ProcessResult
import com.programmersbox.scrcpyviewer.state.AppSettings
import com.programmersbox.scrcpyviewer.state.EmulatorPrefs
import com.programmersbox.scrcpyviewer.ui.mirror.MirrorRequests
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmulatorLauncherTest {
    private val dir: Path = Files.createTempDirectory("emulator-launcher")
    private val sdkRoot = dir.resolve("sdk")
    private val sdk = AndroidSdk(sdkRoot, sdkRoot.resolve("emulator").resolve("emulator"), "37.3.9")
    private val pixel = Avd("Pixel_9_API_36", "Pixel 9", 36, "16", AvdKind.Phone, dir.resolve("Pixel_9_API_36.avd"))
    private val tablet = Avd("Tablet_API_35", "Tablet", 35, "15", AvdKind.Tablet, dir.resolve("Tablet_API_35.avd"))
    private val logDir = dir.resolve("emulator-logs")
    private val devices = MutableStateFlow<List<Device>>(emptyList())
    private val settings = AppSettings(dir.resolve("settings.json"))
    private val requested = MutableSharedFlow<String>(replay = 16)
    private val adb = FakeAdb()
    private val starter = FakeStarter()
    private var busyPorts = emptySet<Int>()

    private class FakeProcess(var exitOnDestroy: Boolean = true) : EmulatorProcess {
        override val exit = CompletableDeferred<Int>()
        var destroyed = false
        var forced = false
        override fun destroy() {
            destroyed = true
            if (exitOnDestroy) exit.complete(143)
        }

        override fun destroyForcibly() {
            forced = true
            exit.complete(137)
        }
    }

    private class Started(val command: List<String>, val env: Map<String, String>, val log: Path, val process: FakeProcess)

    private class FakeStarter : ProcessStarter {
        val started = mutableListOf<Started>()
        var logText: String? = null
        override fun start(command: List<String>, env: Map<String, String>, log: Path): EmulatorProcess {
            logText?.let { Files.writeString(log, it) }
            return FakeProcess().also { started += Started(command, env, log, it) }
        }
    }

    private inner class FakeAdb : AdbClient(adbPath = { error("no real adb in tests") }) {
        val bootCompleted = mutableMapOf<String, String>()
        val avdNames = mutableMapOf<String, String>()
        val execs = mutableListOf<List<String>>()
        var shellCalls = 0
        /** What `emu kill` does; by default the emulator leaves the device list. */
        var onKill: (String) -> Unit = { serial -> devices.value = devices.value.filterNot { it.serial == serial } }

        override suspend fun shell(serial: String, cmd: String): String {
            shellCalls++
            return if (cmd == "getprop sys.boot_completed") bootCompleted[serial].orEmpty() else ""
        }

        override suspend fun exec(vararg args: String): ProcessResult {
            execs += args.toList()
            val serial = args[1]
            return when (args.drop(2)) {
                listOf("emu", "kill") -> {
                    onKill(serial)
                    ProcessResult(0, "OK: killing emulator, bye bye\nOK\n", "")
                }
                listOf("emu", "avd", "name") ->
                    avdNames[serial]?.let { ProcessResult(0, "$it\r\nOK\r\n", "") } ?: ProcessResult(1, "", "error")
                else -> ProcessResult(1, "", "unexpected")
            }
        }
    }

    private fun emulator(serial: String, state: DeviceState = DeviceState.Device, info: Boolean = true, transportId: Long = 1) =
        Device(
            serial, state, if (state == DeviceState.Device) "device" else "offline", null, null, transportId, false,
            if (info) DeviceInfo("sdk_gphone64_arm64", "16", 36) else null,
        )

    private fun TestScope.launcher() = EmulatorLauncher(
        adb = adb,
        devices = devices,
        settings = settings,
        mirrors = MirrorRequests(requested),
        logDir = logDir,
        scope = backgroundScope,
        starter = starter,
        clock = { testScheduler.currentTime },
        isPortFree = { it !in busyPorts },
    ).also { runCurrent() }

    private fun TestScope.notices(launcher: EmulatorLauncher): List<EmulatorNotice> {
        val list = mutableListOf<EmulatorNotice>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { launcher.notices.toList(list) }
        return list
    }

    /** Starts [pixel] and brings it to Running after [bootMs]. */
    private fun TestScope.bootPixel(launcher: EmulatorLauncher, bootMs: Long = 12_000) {
        launcher.start(pixel, sdk)
        runCurrent()
        devices.value = listOf(emulator("emulator-5554", DeviceState.Offline, info = false))
        adb.bootCompleted["emulator-5554"] = "1\n"
        runCurrent()
        advanceTimeBy(bootMs)
        devices.value = listOf(emulator("emulator-5554"))
        runCurrent()
    }

    @Test
    fun `start builds the command and env`() = runTest {
        val launcher = launcher()
        launcher.start(pixel, sdk)
        runCurrent()
        val started = starter.started.single()
        assertEquals(
            listOf(sdk.emulator.toString(), "-avd", "Pixel_9_API_36", "-port", "5554", "-no-window"),
            started.command,
        )
        assertEquals(mapOf("ANDROID_SDK_ROOT" to sdkRoot.toString(), "ANDROID_HOME" to sdkRoot.toString()), started.env)
        assertEquals(logDir.resolve("Pixel_9_API_36.log"), started.log)
        assertTrue(Files.isDirectory(logDir))
        assertEquals(AvdState.Booting("emulator-5554", BootStage.Starting, 0, cold = false), launcher.stateOf(pixel.id))
        assertEquals(setOf("emulator-5554"), launcher.bootingSerials.value)
    }

    @Test
    fun `cold boot adds no-snapshot-load and show window drops no-window`() = runTest {
        assertEquals(
            listOf(sdk.emulator.toString(), "-avd", "Pixel_9_API_36", "-port", "5556", "-no-window", "-no-snapshot-load"),
            emulatorCommand(sdk, pixel, 5556, showWindow = false, cold = true),
        )
        settings.update { it.copy(emulatorPrefs = mapOf(pixel.id to EmulatorPrefs(showWindow = true))) }
        val launcher = launcher()
        launcher.start(pixel, sdk, cold = true)
        runCurrent()
        assertEquals(
            listOf(sdk.emulator.toString(), "-avd", "Pixel_9_API_36", "-port", "5554", "-no-snapshot-load"),
            starter.started.single().command,
        )
        assertEquals(true, (launcher.stateOf(pixel.id) as AvdState.Booting).cold)
    }

    @Test
    fun `port skips connected and bound ports`() = runTest {
        assertEquals(5554, pickPort(emptySet()) { true })
        // 5554 is connected, 5556's console is free but its adb port 5557 is bound.
        assertEquals(5558, pickPort(setOf("emulator-5554")) { it != 5557 })
        assertNull(pickPort(emptySet()) { false })

        devices.value = listOf(emulator("emulator-5554"))
        busyPorts = setOf(5556)
        val launcher = launcher()
        launcher.start(pixel, sdk)
        runCurrent()
        assertEquals("5558", starter.started.single().command[4])
    }

    @Test
    fun `two starts get different ports`() = runTest {
        val launcher = launcher()
        launcher.start(pixel, sdk)
        launcher.start(tablet, sdk)
        launcher.start(pixel, sdk) // already booting: ignored
        runCurrent()
        assertEquals(listOf("5554", "5556"), starter.started.map { it.command[4] })
        assertEquals(setOf("emulator-5554", "emulator-5556"), launcher.bootingSerials.value)
    }

    @Test
    fun `stages go starting booting waiting then running`() = runTest {
        val launcher = launcher()
        launcher.start(pixel, sdk)
        runCurrent()
        assertEquals(BootStage.Starting, (launcher.stateOf(pixel.id) as AvdState.Booting).stage)

        advanceTimeBy(5_000)
        devices.value = listOf(emulator("emulator-5554", DeviceState.Offline, info = false))
        runCurrent()
        assertEquals(BootStage.Booting, (launcher.stateOf(pixel.id) as AvdState.Booting).stage)

        advanceTimeBy(3_000)
        assertEquals(BootStage.Booting, (launcher.stateOf(pixel.id) as AvdState.Booting).stage)
        adb.bootCompleted["emulator-5554"] = "1\n"
        advanceTimeBy(1_001)
        assertEquals(BootStage.WaitingForAdb, (launcher.stateOf(pixel.id) as AvdState.Booting).stage)

        // Listed as a device, but its info is not read yet.
        devices.value = listOf(emulator("emulator-5554", info = false))
        runCurrent()
        assertEquals(BootStage.WaitingForAdb, (launcher.stateOf(pixel.id) as AvdState.Booting).stage)
        assertEquals(setOf("emulator-5554"), launcher.bootingSerials.value)

        devices.value = listOf(emulator("emulator-5554"))
        runCurrent()
        assertEquals(AvdState.Running("emulator-5554"), launcher.stateOf(pixel.id))
        assertEquals(emptySet(), launcher.bootingSerials.value)
        assertFalse(starter.started.single().process.destroyed)
        // Ours: never asked for its AVD name.
        assertTrue(adb.execs.none { "avd" in it })
    }

    @Test
    fun `ready saves last boot time and says ready`() = runTest {
        val launcher = launcher()
        val notices = notices(launcher)
        bootPixel(launcher, bootMs = 12_000)
        assertEquals(AvdState.Running("emulator-5554"), launcher.stateOf(pixel.id))
        assertEquals(12_000, settings.settings.value.emulatorPrefs.getValue(pixel.id).lastBootMillis)
        assertEquals(listOf(EmulatorNotice("Pixel 9 is ready.")), notices)
        assertTrue(requested.replayCache.isEmpty())
    }

    @Test
    fun `mirror when ready requests a mirror`() = runTest {
        settings.update { it.copy(emulatorPrefs = mapOf(pixel.id to EmulatorPrefs(mirrorWhenReady = true))) }
        val launcher = launcher()
        val notices = notices(launcher)
        bootPixel(launcher)
        assertEquals(listOf("emulator-5554"), requested.replayCache)
        assertEquals(
            listOf(EmulatorNotice("Pixel 9 is ready. Opening its mirror window…", mirrorSerial = "emulator-5554")),
            notices,
        )
        // The saved boot time keeps the mirror preference.
        assertTrue(settings.settings.value.emulatorPrefs.getValue(pixel.id).mirrorWhenReady)
    }

    @Test
    fun `process exit fails with the log reason`() = runTest {
        starter.logText = "INFO    | Android emulator version 37.3.9\n" +
            "ERROR   | Not enough disk space for the data image.\n" +
            "INFO    | Shutting down\n"
        val launcher = launcher()
        val notices = notices(launcher)
        launcher.start(pixel, sdk, cold = true)
        runCurrent()
        devices.value = listOf(emulator("emulator-5554", DeviceState.Offline, info = false))
        advanceTimeBy(3_000)
        val started = starter.started.single()
        started.process.exit.complete(1)
        runCurrent()
        assertEquals(
            AvdState.Failed("Emulator exited (code 1). Not enough disk space for the data image.", cold = true),
            launcher.stateOf(pixel.id),
        )
        assertEquals(listOf(EmulatorNotice("Pixel 9 didn't start.", logFile = started.log)), notices)
        assertEquals(emptySet(), launcher.bootingSerials.value)
        // No polling outlives the failure.
        val calls = adb.shellCalls
        advanceTimeBy(30_000)
        assertEquals(calls, adb.shellCalls)

        // Retry is allowed from Failed.
        launcher.start(pixel, sdk, cold = true)
        runCurrent()
        assertEquals(2, starter.started.size)
    }

    @Test
    fun `failure reason reads the last error line`() {
        val log = dir.resolve("x.log")
        Files.writeString(log, "ERROR   | first\nFATAL   | Running multiple emulators with the same AVD\nINFO | ok\n")
        assertEquals("Running multiple emulators with the same AVD", failureReason(log))
        Files.writeString(log, "PANIC: Missing emulator engine program for 'x86' CPU.\n")
        assertEquals("Missing emulator engine program for 'x86' CPU.", failureReason(log))
        Files.writeString(log, "INFO | nothing wrong\n")
        assertNull(failureReason(log))
        assertNull(failureReason(dir.resolve("missing.log")))
    }

    @Test
    fun `a process that cannot start fails`() = runTest {
        val launcher = EmulatorLauncher(
            adb, devices, settings, MirrorRequests(requested), logDir, backgroundScope,
            starter = { _, _, _ -> throw java.io.IOException("Permission denied") },
            clock = { testScheduler.currentTime }, isPortFree = { true },
        )
        launcher.start(pixel, sdk)
        assertEquals(AvdState.Failed("Couldn't start the emulator: Permission denied", cold = false), launcher.stateOf(pixel.id))
    }

    @Test
    fun `boot timeout kills and fails`() = runTest {
        adb.onKill = {} // emu kill does nothing: the process has to be destroyed
        val launcher = launcher()
        val notices = notices(launcher)
        launcher.start(pixel, sdk)
        runCurrent()
        devices.value = listOf(emulator("emulator-5554", DeviceState.Offline, info = false))
        advanceTimeBy(BOOT_TIMEOUT_MS - 1)
        assertTrue(launcher.stateOf(pixel.id) is AvdState.Booting)
        advanceTimeBy(2)
        assertEquals(AvdState.Stopping("emulator-5554"), launcher.stateOf(pixel.id))
        assertEquals(listOf("-s", "emulator-5554", "emu", "kill"), adb.execs.single())
        val process = starter.started.single().process
        assertFalse(process.destroyed)

        advanceTimeBy(STOP_WAIT_MS + 1)
        assertTrue(process.destroyed)
        assertEquals(AvdState.Failed(BOOT_TIMEOUT_REASON, cold = false), launcher.stateOf(pixel.id))
        assertEquals("Android didn't finish booting in 4 minutes.", BOOT_TIMEOUT_REASON)
        assertEquals(listOf(EmulatorNotice("Pixel 9 didn't start.", logFile = logDir.resolve("Pixel_9_API_36.log"))), notices)
        val calls = adb.shellCalls
        advanceTimeBy(30_000)
        assertEquals(calls, adb.shellCalls)
    }

    @Test
    fun `boot timeout before adb lists it destroys the process`() = runTest {
        val launcher = launcher()
        launcher.start(pixel, sdk)
        runCurrent()
        advanceTimeBy(BOOT_TIMEOUT_MS + 1)
        assertTrue(starter.started.single().process.destroyed)
        assertTrue(adb.execs.isEmpty())
        assertEquals(AvdState.Failed(BOOT_TIMEOUT_REASON, cold = false), launcher.stateOf(pixel.id))
    }

    @Test
    fun `stop before adb lists it destroys the process`() = runTest {
        val launcher = launcher()
        launcher.start(pixel, sdk)
        runCurrent()
        val process = starter.started.single().process
        process.exitOnDestroy = false
        launcher.stop(pixel.id)
        assertEquals(AvdState.Stopped, launcher.stateOf(pixel.id))
        assertEquals(emptyMap(), launcher.states.value)
        assertEquals(emptySet(), launcher.bootingSerials.value)
        runCurrent()
        assertTrue(process.destroyed)
        assertFalse(process.forced)
        advanceTimeBy(5_001)
        assertTrue(process.forced)
        assertTrue(adb.execs.isEmpty())
        // The cancelled boot never reports a failure later.
        advanceTimeBy(BOOT_TIMEOUT_MS)
        assertEquals(AvdState.Stopped, launcher.stateOf(pixel.id))
    }

    @Test
    fun `stop sends emu kill and waits for the serial to leave`() = runTest {
        adb.onKill = {}
        val launcher = launcher()
        bootPixel(launcher)
        launcher.stop(pixel.id)
        assertEquals(AvdState.Stopping("emulator-5554"), launcher.stateOf(pixel.id))
        runCurrent()
        assertEquals(listOf("-s", "emulator-5554", "emu", "kill"), adb.execs.single())
        advanceTimeBy(3_000)
        assertEquals(AvdState.Stopping("emulator-5554"), launcher.stateOf(pixel.id))
        devices.value = emptyList()
        runCurrent()
        assertEquals(AvdState.Stopped, launcher.stateOf(pixel.id))
        assertFalse(starter.started.single().process.destroyed)
    }

    @Test
    fun `stop that never finishes returns to running`() = runTest {
        adb.onKill = {}
        val launcher = launcher()
        val notices = notices(launcher)
        // Started elsewhere: back to Running with a notice.
        adb.avdNames["emulator-5556"] = tablet.id
        devices.value = listOf(emulator("emulator-5556"))
        runCurrent()
        launcher.stop(tablet.id, tablet.name)
        runCurrent()
        advanceTimeBy(STOP_WAIT_MS - 1)
        assertEquals(AvdState.Stopping("emulator-5556"), launcher.stateOf(tablet.id))
        advanceTimeBy(2)
        assertEquals(AvdState.Running("emulator-5556"), launcher.stateOf(tablet.id))
        assertEquals(listOf(EmulatorNotice("Couldn't stop Tablet.")), notices)

        // Ours: the process is destroyed instead.
        bootPixel(launcher)
        devices.value = listOf(emulator("emulator-5554"), emulator("emulator-5556"))
        runCurrent()
        val before = notices.size
        launcher.stop(pixel.id)
        advanceTimeBy(STOP_WAIT_MS + 1)
        assertTrue(starter.started.single().process.destroyed)
        assertEquals(AvdState.Stopped, launcher.stateOf(pixel.id))
        assertEquals(before, notices.size)
    }

    @Test
    fun `external emulator is matched by emu avd name and can be stopped`() = runTest {
        adb.avdNames["emulator-5554"] = pixel.id
        // Already running when the app starts (left running by the last quit).
        devices.value = listOf(emulator("emulator-5554"), emulator("emulator-5560"))
        val launcher = launcher()
        assertEquals(mapOf(pixel.id to AvdState.Running("emulator-5554")), launcher.states.value)
        assertEquals(listOf("-s", "emulator-5554", "emu", "avd", "name"), adb.execs.first())
        // Asked once per connection, also when the list changes.
        devices.value = devices.value + emulator("39121FDJH004AB")
        runCurrent()
        assertEquals(1, adb.execs.count { it[1] == "emulator-5554" })
        // A started-elsewhere emulator is not ours to boot: Start is not offered (ignored).
        launcher.start(pixel, sdk)
        assertTrue(starter.started.isEmpty())

        launcher.stop(pixel.id)
        assertEquals(AvdState.Stopping("emulator-5554"), launcher.stateOf(pixel.id))
        runCurrent()
        assertTrue(listOf("-s", "emulator-5554", "emu", "kill") in adb.execs)
        assertEquals(AvdState.Stopped, launcher.stateOf(pixel.id))

        // Started and stopped elsewhere: follows the tracker.
        devices.value = listOf(emulator("emulator-5554", transportId = 7))
        runCurrent()
        assertEquals(AvdState.Running("emulator-5554"), launcher.stateOf(pixel.id))
        devices.value = emptyList()
        runCurrent()
        assertEquals(AvdState.Stopped, launcher.stateOf(pixel.id))
    }
}
