package com.programmersbox.scrcpyviewer.emulator

import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.adb.Device
import com.programmersbox.scrcpyviewer.adb.DeviceInfo
import com.programmersbox.scrcpyviewer.adb.DeviceState
import com.programmersbox.scrcpyviewer.adb.ProcessResult
import com.programmersbox.scrcpyviewer.state.AppSettings
import com.programmersbox.scrcpyviewer.state.EmulatorPrefs
import com.programmersbox.scrcpyviewer.telemetry.FakeTelemetry
import com.programmersbox.scrcpyviewer.telemetry.TelemetryEvent
import com.programmersbox.scrcpyviewer.telemetry.TelemetryEvent.EmulatorStarted
import com.programmersbox.scrcpyviewer.ui.mirror.MirrorRequests
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import com.programmersbox.scrcpyviewer.ui.mirror.MirrorOrigins
import java.awt.Rectangle
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.async
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
    private var uiContext: CoroutineContext = EmptyCoroutineContext
    private val telemetry = FakeTelemetry()

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
        /** False: a destroyed process keeps running until it is killed forcibly. */
        var exitOnDestroy = true
        override fun start(command: List<String>, env: Map<String, String>, log: Path): EmulatorProcess {
            logText?.let { Files.writeString(log, it) }
            return FakeProcess(exitOnDestroy).also { started += Started(command, env, log, it) }
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

    private fun TestScope.launcher(wipeData: (suspend (Path) -> Unit)? = null) = EmulatorLauncher(
        adb = adb,
        devices = devices,
        settings = settings,
        mirrors = MirrorRequests(requested),
        logDir = logDir,
        scope = backgroundScope,
        starter = starter,
        clock = { testScheduler.currentTime },
        isPortFree = { it !in busyPorts },
        uiContext = uiContext,
        // The real deletion, on the test thread (temp folders only).
        wipeData = wipeData ?: { wipeAvdData(it) },
        telemetry = telemetry,
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
        assertEquals(listOf<TelemetryEvent>(EmulatorStarted(coldBoot = false, success = true, bootSeconds = 12)), telemetry.events)
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
    fun `mirror when ready finds the row and requests the mirror on the ui thread`() = runTest {
        val ui = Executors.newSingleThreadExecutor { Thread(it, "test-ui") }
        try {
            uiContext = ui.asCoroutineDispatcher()
            settings.update { it.copy(emulatorPrefs = mapOf(pixel.id to EmulatorPrefs(mirrorWhenReady = true))) }
            val row = Rectangle(40, 300, 220, 56)
            val located = mutableListOf<String>()
            val locate: () -> Rectangle? = { located += Thread.currentThread().name.substringBefore(" @"); row }
            MirrorOrigins.take()
            // The row registers its bounds while Booting, so they are there when the boot ends.
            MirrorOrigins.registerButton("emulator-5554", locate)
            try {
                val launcher = launcher()
                bootPixel(launcher)
                val serial = withContext(Dispatchers.Default) { withTimeout(5_000) { requested.first() } }
                assertEquals("emulator-5554", serial)
                assertEquals(listOf("test-ui"), located)
                // The row's bounds are where the mirror window grows from.
                assertEquals(row, MirrorOrigins.take())
            } finally {
                MirrorOrigins.unregisterButton("emulator-5554", locate)
            }
        } finally {
            ui.shutdownNow()
        }
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
        assertEquals(listOf<TelemetryEvent>(EmulatorStarted(coldBoot = true, success = false, bootSeconds = 3)), telemetry.events)
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
            clock = { testScheduler.currentTime }, isPortFree = { true }, telemetry = telemetry,
        )
        launcher.start(pixel, sdk)
        assertEquals(AvdState.Failed("Couldn't start the emulator: Permission denied", cold = false), launcher.stateOf(pixel.id))
        assertEquals(listOf<TelemetryEvent>(EmulatorStarted(coldBoot = false, success = false, bootSeconds = 0)), telemetry.events)
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
        assertEquals(false, (telemetry.events.single() as EmulatorStarted).success)
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

    @Test
    fun `stop during the ready handoff ends stopped`() = runTest {
        adb.onKill = {}
        settings.update { it.copy(emulatorPrefs = mapOf(pixel.id to EmulatorPrefs(mirrorWhenReady = true))) }
        val launcher = launcher()
        val notices = notices(launcher)
        launcher.start(pixel, sdk)
        runCurrent()
        devices.value = listOf(emulator("emulator-5554", DeviceState.Offline, info = false))
        adb.bootCompleted["emulator-5554"] = "1\n"
        runCurrent()
        // Ready arrives, and Stop is pressed before the boot coroutine runs.
        devices.value = listOf(emulator("emulator-5554"))
        launcher.stop(pixel.id)
        runCurrent()
        assertEquals(AvdState.Stopping("emulator-5554"), launcher.stateOf(pixel.id))
        // Even when the boot gets to ready or exit after the Stop (another thread), the Stop wins.
        launcher.onReady(pixel.id)
        launcher.onExited(pixel.id, 1)
        assertEquals(AvdState.Stopping("emulator-5554"), launcher.stateOf(pixel.id))
        devices.value = emptyList()
        runCurrent()
        assertEquals(AvdState.Stopped, launcher.stateOf(pixel.id))
        assertTrue(notices.isEmpty())
        assertTrue(telemetry.events.isEmpty(), "a stopped boot is neither a success nor a failure")
        assertTrue(requested.replayCache.isEmpty())
        assertNull(settings.settings.value.emulatorPrefs.getValue(pixel.id).lastBootMillis)
    }

    @Test
    fun `cold boot does not save the boot time`() = runTest {
        val launcher = launcher()
        launcher.start(pixel, sdk, cold = true)
        runCurrent()
        devices.value = listOf(emulator("emulator-5554", DeviceState.Offline, info = false))
        adb.bootCompleted["emulator-5554"] = "1\n"
        runCurrent()
        advanceTimeBy(40_000)
        devices.value = listOf(emulator("emulator-5554"))
        runCurrent()
        assertEquals(AvdState.Running("emulator-5554"), launcher.stateOf(pixel.id))
        assertNull(settings.settings.value.emulatorPrefs[pixel.id]?.lastBootMillis)
        assertEquals(listOf<TelemetryEvent>(EmulatorStarted(coldBoot = true, success = true, bootSeconds = 40)), telemetry.events)
    }

    @Test
    fun `a stopped port stays taken until its process exits`() = runTest {
        val launcher = launcher()
        launcher.start(pixel, sdk)
        runCurrent()
        val first = starter.started.single().process
        first.exitOnDestroy = false
        launcher.stop(pixel.id)
        runCurrent()
        launcher.start(tablet, sdk)
        runCurrent()
        assertEquals("5556", starter.started[1].command[4])
        // Forced after the grace period: the exit frees 5554.
        advanceTimeBy(5_001)
        assertTrue(first.forced)
        launcher.start(pixel, sdk)
        runCurrent()
        assertEquals("5554", starter.started[2].command[4])
    }

    @Test
    fun `an emulator of the same avd is matched once our start fails`() = runTest {
        adb.avdNames["emulator-5556"] = pixel.id
        val launcher = launcher()
        launcher.start(pixel, sdk)
        runCurrent()
        // The AVD was already running elsewhere; adb lists it while ours boots.
        devices.value = listOf(emulator("emulator-5556"))
        runCurrent()
        assertTrue(launcher.stateOf(pixel.id) is AvdState.Booting)
        starter.started.single().process.exit.complete(1)
        runCurrent()
        assertEquals(AvdState.Running("emulator-5556"), launcher.stateOf(pixel.id))
        assertEquals(2, adb.execs.count { it == listOf("-s", "emulator-5556", "emu", "avd", "name") })
    }

    // --- Wipe data --------------------------------------------------------------------------------------------------

    private val watch = Avd("Watch_API_34", "Watch", 34, "14", AvdKind.Watch, dir.resolve("Watch_API_34.avd"))

    /** What a wipe leaves in an AVD folder. */
    private val kept = listOf("config.ini", "hardware-qemu.ini", "userdata.img", "sdcard.img", "encryptionkey.img")

    /** [avd]'s folder with what an emulator writes there: the five wipe entries and the files a wipe keeps. */
    private fun avdFolder(avd: Avd): Path {
        val d = Files.createDirectories(avd.dir)
        for (f in kept + WIPE_ENTRIES.filter { it != "snapshots" }) Files.writeString(d.resolve(f), f)
        val snap = Files.createDirectories(d.resolve("snapshots").resolve("default_boot"))
        Files.writeString(snap.resolve("snapshot.pb"), "pb")
        Files.writeString(snap.resolve("ram.bin"), "ram")
        return d
    }

    /** False where symbolic links need rights the test does not have (Windows without developer mode). */
    private fun symlink(link: Path, target: Path): Boolean = try {
        Files.createSymbolicLink(link, target)
        true
    } catch (_: UnsupportedOperationException) {
        false
    } catch (_: java.io.IOException) {
        false
    }

    private fun exists(p: Path) = Files.exists(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)

    @Test
    fun `wipe deletes only data files`() = runTest {
        val launcher = launcher()
        val notices = notices(launcher)
        val d = avdFolder(pixel)
        val other = avdFolder(tablet)
        assertTrue(launcher.wipe(pixel).isSuccess)
        WIPE_ENTRIES.forEach { assertFalse(exists(d.resolve(it)), "$it is deleted") }
        kept.forEach { assertEquals(it, Files.readString(d.resolve(it)), "$it is kept") }
        // Another AVD's folder is untouched.
        (kept + WIPE_ENTRIES).forEach { assertTrue(exists(other.resolve(it)), "the tablet's $it is kept") }
        assertEquals("pb", Files.readString(other.resolve("snapshots").resolve("default_boot").resolve("snapshot.pb")))
        assertEquals(listOf("Wiped Pixel 9. Next start is a cold boot."), notices.map { it.text })
        assertEquals(emptySet(), launcher.wiping.value)
        // Missing entries are fine: a second wipe has nothing to delete and still works.
        assertTrue(launcher.wipe(pixel).isSuccess)
        assertTrue(starter.started.isEmpty(), "a wipe never starts an emulator")
    }

    @Test
    fun `wipe refuses while running or listed`() = runTest {
        val launcher = launcher()
        val notices = notices(launcher)
        val d = avdFolder(pixel)
        suspend fun refused(reason: String) {
            val from = notices.size
            assertTrue(launcher.wipe(pixel).isFailure, reason)
            assertEquals(listOf("Couldn't wipe Pixel 9: $reason"), notices.drop(from).map { it.text })
            assertTrue(exists(d.resolve("userdata-qemu.img")) && exists(d.resolve("snapshots")), "nothing deleted ($reason)")
        }

        // Ours, booting and then running.
        launcher.start(pixel, sdk)
        runCurrent()
        refused("it's running.")
        launcher.stop(pixel.id)
        runCurrent()
        bootPixel(launcher)
        assertEquals(AvdState.Running("emulator-5554"), launcher.stateOf(pixel.id))
        refused("it's running.")
        // Stopping: the emulator is still listed.
        adb.onKill = {}
        launcher.stop(pixel.id, pixel.name)
        runCurrent()
        assertEquals(AvdState.Stopping("emulator-5554"), launcher.stateOf(pixel.id))
        refused("it's still stopping.")
        devices.value = emptyList()
        runCurrent()
        assertEquals(AvdState.Stopped, launcher.stateOf(pixel.id))
        // Unlisted but still saving its snapshot: refused until the process exits.
        refused("it's still stopping.")
        starter.started.last().process.exit.complete(0)
        runCurrent()

        // Started elsewhere and matched by `emu avd name`.
        adb.avdNames["emulator-5556"] = pixel.id
        devices.value = listOf(emulator("emulator-5556"))
        runCurrent()
        assertEquals(AvdState.Running("emulator-5556"), launcher.stateOf(pixel.id))
        refused("it's running.")
        devices.value = emptyList()
        runCurrent()

        // Listed but not matched yet (offline, so not asked): the wipe asks it.
        adb.avdNames["emulator-5558"] = pixel.id
        devices.value = listOf(emulator("emulator-5558", DeviceState.Offline, info = false))
        runCurrent()
        assertEquals(AvdState.Stopped, launcher.stateOf(pixel.id))
        refused("it's running.")
        // One that cannot say which AVD it runs refuses too.
        devices.value = listOf(emulator("emulator-5560", DeviceState.Offline, info = false))
        runCurrent()
        refused("couldn't tell which AVD emulator-5560 runs.")

        // Another AVD's emulator does not stop the wipe.
        adb.avdNames["emulator-5562"] = tablet.id
        devices.value = listOf(emulator("emulator-5562", DeviceState.Offline, info = false))
        runCurrent()
        assertTrue(launcher.wipe(pixel).isSuccess)
        assertFalse(exists(d.resolve("userdata-qemu.img")))
    }

    @Test
    fun `wipe never follows a symlink out of the avd dir`() = runTest {
        val launcher = launcher()
        val notices = notices(launcher)
        val outside = Files.createDirectories(dir.resolve("outside"))
        Files.writeString(outside.resolve("precious.img"), "keep")
        Files.createDirectories(outside.resolve("folder"))
        Files.writeString(outside.resolve("folder").resolve("inner.bin"), "keep")

        // A data image linked to a file outside: refused before anything is deleted.
        val p = avdFolder(pixel)
        Files.delete(p.resolve("userdata-qemu.img"))
        if (!symlink(p.resolve("userdata-qemu.img"), outside.resolve("precious.img"))) return@runTest
        assertTrue(launcher.wipe(pixel).isFailure)
        assertEquals(
            "Couldn't wipe Pixel 9: userdata-qemu.img points outside its AVD folder. Nothing was deleted.",
            notices.last().text,
        )
        assertTrue(Files.isSymbolicLink(p.resolve("userdata-qemu.img")))
        assertTrue(exists(p.resolve("cache.img")) && exists(p.resolve("snapshots")), "nothing deleted")

        // The snapshots folder linked to a folder outside: refused.
        val t = avdFolder(tablet)
        Files.walk(t.resolve("snapshots")).use { s -> s.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        assertTrue(symlink(t.resolve("snapshots"), outside.resolve("folder")))
        assertTrue(launcher.wipe(tablet).isFailure)
        assertTrue(exists(t.resolve("cache.img")))

        // Links inside snapshots, and a data image linked to a file inside the folder: only the links go.
        val w = avdFolder(watch)
        val boot = w.resolve("snapshots").resolve("default_boot")
        assertTrue(symlink(boot.resolve("linked-dir"), outside.resolve("folder")))
        assertTrue(symlink(boot.resolve("linked-file"), outside.resolve("precious.img")))
        Files.delete(w.resolve("cache.img"))
        assertTrue(symlink(w.resolve("cache.img"), w.resolve("sdcard.img")))
        assertTrue(launcher.wipe(watch).isSuccess, notices.last().text)
        WIPE_ENTRIES.forEach { assertFalse(exists(w.resolve(it)), "$it is deleted") }
        assertEquals("sdcard.img", Files.readString(w.resolve("sdcard.img")))

        // Nothing outside was touched.
        assertEquals("keep", Files.readString(outside.resolve("precious.img")))
        assertEquals("keep", Files.readString(outside.resolve("folder").resolve("inner.bin")))
    }

    @Test
    fun `wipe waits for our emulator process to exit after a stop while unlisted`() = runTest {
        starter.exitOnDestroy = false
        val launcher = launcher()
        val notices = notices(launcher)
        val d = avdFolder(pixel)
        launcher.start(pixel, sdk)
        runCurrent()
        launcher.stop(pixel.id)
        runCurrent()
        // The row is Stopped at once, but the process is still going down.
        assertEquals(AvdState.Stopped, launcher.stateOf(pixel.id))
        assertEquals(setOf(pixel.id), launcher.processAlive.value)
        assertTrue(launcher.wipe(pixel).isFailure)
        assertEquals("Couldn't wipe Pixel 9: it's still stopping.", notices.last().text)
        assertTrue(exists(d.resolve("userdata-qemu.img")) && exists(d.resolve("snapshots")))

        // Killed after the grace period: now it can be wiped.
        advanceTimeBy(6_000)
        runCurrent()
        assertTrue(starter.started.single().process.forced)
        assertEquals(emptySet(), launcher.processAlive.value)
        assertTrue(launcher.wipe(pixel).isSuccess)
        assertFalse(exists(d.resolve("userdata-qemu.img")))
    }

    @Test
    fun `stale lock files left by an exited emulator do not stop a wipe`() = runTest {
        val launcher = launcher()
        // Every AVD that ever booted keeps an empty multiinstance.lock; a crash leaves the pid lock behind.
        for (pid in listOf(Long.MAX_VALUE.toString(), "not a pid", "")) {
            val d = avdFolder(pixel)
            Files.writeString(d.resolve(INSTANCE_LOCK), "")
            Files.writeString(d.resolve(PID_LOCK), pid)
            assertTrue(launcher.wipe(pixel).isSuccess, "pid '$pid'")
            assertFalse(exists(d.resolve("userdata-qemu.img")))
            // The lock files are not the wipe's to delete.
            assertTrue(exists(d.resolve(INSTANCE_LOCK)) && exists(d.resolve(PID_LOCK)))
        }
    }

    @Test
    fun `a pid lock of a live process refuses a wipe`() = runTest {
        val launcher = launcher()
        val notices = notices(launcher)
        val d = avdFolder(pixel)
        val live = ProcessHandle.current().pid().toString()
        Files.writeString(d.resolve(PID_LOCK), live)
        assertTrue(launcher.wipe(pixel).isFailure)
        assertEquals("Couldn't wipe Pixel 9: it's running (hardware-qemu.ini.lock is held).", notices.last().text)
        assertTrue(exists(d.resolve("userdata-qemu.img")) && exists(d.resolve("snapshots")), "nothing deleted")
        // Windows writes it as a folder holding a pid file.
        Files.delete(d.resolve(PID_LOCK))
        Files.writeString(Files.createDirectories(d.resolve(PID_LOCK)).resolve("pid"), live)
        assertTrue(launcher.wipe(pixel).isFailure)
        assertEquals(null, heldAvdLock(d) { false })
    }

    @Test
    fun `a held multiinstance lock refuses a wipe`() = runTest {
        val launcher = launcher()
        val notices = notices(launcher)
        val d = avdFolder(pixel)
        Files.writeString(d.resolve(INSTANCE_LOCK), "")
        java.nio.channels.FileChannel.open(d.resolve(INSTANCE_LOCK), java.nio.file.StandardOpenOption.WRITE).use { channel ->
            val lock = channel.lock()
            assertTrue(launcher.wipe(pixel).isFailure)
            assertEquals("Couldn't wipe Pixel 9: it's running (multiinstance.lock is held).", notices.last().text)
            assertTrue(exists(d.resolve("userdata-qemu.img")), "nothing deleted")
            lock.release()
        }
        // Released: the wipe goes ahead.
        assertTrue(launcher.wipe(pixel).isSuccess)
        assertFalse(exists(d.resolve("userdata-qemu.img")))
    }

    @Test
    fun `wipe refuses a folder without config ini`() = runTest {
        val launcher = launcher()
        val notices = notices(launcher)
        val d = avdFolder(pixel)
        Files.delete(d.resolve("config.ini"))
        assertTrue(launcher.wipe(pixel).isFailure)
        assertEquals("Couldn't wipe Pixel 9: its folder has no config.ini. Nothing was deleted.", notices.last().text)
        assertTrue(exists(d.resolve("cache.img")) && exists(d.resolve("snapshots")))
    }

    @Test
    fun `wipe deletes the snapshots first`() {
        assertEquals("snapshots", WIPE_ENTRIES.first())
    }

    @Test
    fun `start during a wipe is refused`() = runTest {
        val hold = CompletableDeferred<Unit>()
        val wiped = mutableListOf<Path>()
        val launcher = launcher(wipeData = { wiped.add(it); hold.await() })
        val notices = notices(launcher)
        val wipe = async { launcher.wipe(pixel) }
        runCurrent()
        assertEquals(listOf(pixel.dir), wiped)
        assertEquals(setOf(pixel.id), launcher.wiping.value)

        // Start and Cold boot are refused while the files are being deleted; another AVD starts.
        launcher.start(pixel, sdk)
        launcher.start(pixel, sdk, cold = true)
        runCurrent()
        assertEquals(AvdState.Stopped, launcher.stateOf(pixel.id))
        assertTrue(starter.started.isEmpty())
        launcher.start(tablet, sdk)
        runCurrent()
        assertEquals(1, starter.started.size)
        // A second wipe of the same AVD is refused.
        assertTrue(launcher.wipe(pixel).isFailure)

        hold.complete(Unit)
        runCurrent()
        assertTrue(wipe.await().isSuccess)
        assertEquals(emptySet(), launcher.wiping.value)
        assertEquals(
            listOf(
                "Pixel 9 is still being wiped. Start it once that finishes.",
                "Pixel 9 is still being wiped. Start it once that finishes.",
                "Couldn't wipe Pixel 9: it's already being wiped.",
                "Wiped Pixel 9. Next start is a cold boot.",
            ),
            notices.map { it.text },
        )
        // Once wiped it starts.
        launcher.start(pixel, sdk)
        runCurrent()
        assertTrue(launcher.stateOf(pixel.id) is AvdState.Booting)
    }

    @Test
    fun `system process destroy reaches its children`() {
        if (com.programmersbox.scrcpyviewer.Os.isWindows) return
        val raw = ProcessBuilder("sh", "-c", "sleep 30 & sleep 30").start()
        val process = SystemEmulatorProcess(raw)
        var children = emptyList<ProcessHandle>()
        val deadline = System.currentTimeMillis() + 5_000
        while (children.size < 2 && System.currentTimeMillis() < deadline) {
            children = raw.descendants().toList()
            Thread.sleep(20)
        }
        assertTrue(children.isNotEmpty(), "sh started no child")
        process.destroy()
        children.forEach { it.onExit().get(5, java.util.concurrent.TimeUnit.SECONDS) }
        assertTrue(children.none { it.isAlive })
        raw.onExit().get(5, java.util.concurrent.TimeUnit.SECONDS)
    }
}
