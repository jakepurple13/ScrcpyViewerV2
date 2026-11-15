package com.programmersbox.scrcpyviewer.ui.devices.emulators

import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.adb.Device
import com.programmersbox.scrcpyviewer.adb.DeviceInfo
import com.programmersbox.scrcpyviewer.adb.DeviceState
import com.programmersbox.scrcpyviewer.adb.ProcessResult
import com.programmersbox.scrcpyviewer.emulator.AvdState
import com.programmersbox.scrcpyviewer.emulator.BootStage
import com.programmersbox.scrcpyviewer.emulator.EmulatorLauncher
import com.programmersbox.scrcpyviewer.emulator.EmulatorNotice
import com.programmersbox.scrcpyviewer.emulator.EmulatorProcess
import com.programmersbox.scrcpyviewer.emulator.ProcessStarter
import com.programmersbox.scrcpyviewer.emulator.SdkLookup
import com.programmersbox.scrcpyviewer.state.AppSettings
import com.programmersbox.scrcpyviewer.state.EmulatorPrefs
import com.programmersbox.scrcpyviewer.ui.common.OpenPlatform
import com.programmersbox.scrcpyviewer.ui.mirror.MirrorRequests
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EmulatorsViewModelTest {
    private val tmp: Path = Files.createTempDirectory("emulators-vm")
    private val home = tmp.resolve("home")
    private val avdHome = tmp.resolve("avd")
    private val sdkRoot = tmp.resolve("sdk")
    private val env = mutableMapOf<String, String>()
    private val settings = AppSettings(tmp.resolve("settings.json"))
    private val devices = MutableStateFlow<List<Device>>(emptyList())
    private val requested = MutableSharedFlow<String>(replay = 16)
    private val adb = FakeAdb()
    private val starter = FakeStarter()
    private val measured = mutableListOf<String>()

    init {
        Files.createDirectories(home)
    }

    private class FakeProcess : EmulatorProcess {
        override val exit = CompletableDeferred<Int>()
        override fun destroy() {
            exit.complete(143)
        }

        override fun destroyForcibly() {
            exit.complete(137)
        }
    }

    private class FakeStarter : ProcessStarter {
        val commands = mutableListOf<List<String>>()
        val processes = mutableListOf<FakeProcess>()
        override fun start(command: List<String>, env: Map<String, String>, log: Path): EmulatorProcess {
            commands += command
            return FakeProcess().also { processes += it }
        }
    }

    private inner class FakeAdb : AdbClient(adbPath = { error("no real adb in tests") }) {
        val avdNames = mutableMapOf<String, String>()
        var kills = true
        override suspend fun shell(serial: String, cmd: String): String = ""
        override suspend fun exec(vararg args: String): ProcessResult {
            val serial = args[1]
            return when (args.drop(2)) {
                listOf("emu", "kill") -> {
                    if (kills) devices.value = devices.value.filterNot { it.serial == serial }
                    ProcessResult(0, "OK\n", "")
                }
                listOf("emu", "avd", "name") ->
                    avdNames[serial]?.let { ProcessResult(0, "$it\nOK\n", "") } ?: ProcessResult(1, "", "error")
                else -> ProcessResult(1, "", "unexpected")
            }
        }
    }

    private fun sdk(withEmulator: Boolean = true) {
        Files.createDirectories(sdkRoot)
        if (withEmulator) {
            val dir = Files.createDirectories(sdkRoot.resolve("emulator"))
            Files.createFile(dir.resolve("emulator"))
            Files.writeString(dir.resolve("source.properties"), "Pkg.Revision=37.3.9\n")
        }
        env["ANDROID_HOME"] = sdkRoot.toString()
    }

    private fun avd(id: String, name: String, api: Int = 36) {
        val dir = Files.createDirectories(avdHome.resolve("$id.avd"))
        Files.writeString(
            dir.resolve("config.ini"),
            "avd.ini.displayname=$name\nimage.sysdir.1=system-images/android-$api/google_apis/arm64-v8a/\ntag.id=google_apis\n",
        )
        Files.writeString(avdHome.resolve("$id.ini"), "path=$dir\n")
        env["ANDROID_AVD_HOME"] = avdHome.toString()
    }

    private fun emulator(serial: String) = Device(serial, DeviceState.Device, "device", null, null, 1, false, DeviceInfo("sdk_gphone64_arm64", "16", 36))

    private fun TestScope.launcher() = EmulatorLauncher(
        adb = adb, devices = devices, settings = settings, mirrors = MirrorRequests(requested),
        logDir = tmp.resolve("logs"), scope = backgroundScope, starter = starter,
        clock = { testScheduler.currentTime }, isPortFree = { true }, uiContext = EmptyCoroutineContext,
    )

    private fun TestScope.viewModel(
        launcher: EmulatorLauncher = launcher(),
        io: CoroutineContext = UnconfinedTestDispatcher(testScheduler),
        sizeOf: (Path) -> Long? = { measured += it.fileName.toString(); 8_804_682_956L },
    ) = EmulatorsViewModel(
        launcher, settings, backgroundScope, env = env::get, home = home, os = OpenPlatform.Linux, io = io, sizeOf = sizeOf,
    ).also {
        it.onEvent(EmulatorEvent.Reload)
        runCurrent()
    }

    private fun EmulatorsViewModel.row(id: String) = state.value.rows.first { it.avd.id == id }

    @Test
    fun `no sdk shows the platform default`() = runTest {
        val vm = viewModel()
        assertEquals(SdkLookup.NotFound("~/Android/Sdk"), vm.state.value.sdk)
        assertTrue(vm.state.value.rows.isEmpty())
    }

    @Test
    fun `an sdk without the emulator says so`() = runTest {
        sdk(withEmulator = false)
        avd("Pixel_9_API_36", "Pixel 9")
        val vm = viewModel()
        assertEquals(SdkLookup.NoEmulator(sdkRoot), vm.state.value.sdk)
        assertTrue(vm.state.value.rows.isEmpty())
    }

    @Test
    fun `an sdk with no avds has no rows`() = runTest {
        sdk()
        val vm = viewModel()
        val found = assertIs<SdkLookup.Found>(vm.state.value.sdk)
        assertEquals("37.3.9", found.sdk.emulatorVersion)
        assertTrue(vm.state.value.rows.isEmpty())
    }

    @Test
    fun `rows are the catalog avds with their state, size and prefs`() = runTest {
        sdk()
        avd("Pixel_9_API_36", "Pixel 9")
        avd("heads_up_api34", "heads_up_api34", api = 34)
        settings.update { it.copy(emulatorPrefs = mapOf("Pixel_9_API_36" to EmulatorPrefs(mirrorWhenReady = true))) }
        val launcher = launcher()
        // One emulator of a catalog AVD, one of an AVD the catalog doesn't have (a Gradle managed device).
        adb.avdNames["emulator-5554"] = "Pixel_9_API_36"
        adb.avdNames["emulator-5556"] = "dev34_aosp_atd"
        devices.value = listOf(emulator("emulator-5554"), emulator("emulator-5556"))
        runCurrent()
        assertEquals(setOf("Pixel_9_API_36", "dev34_aosp_atd"), launcher.states.value.keys)

        val vm = viewModel(launcher)
        val rows = vm.state.value.rows
        assertEquals(listOf("heads_up_api34", "Pixel_9_API_36"), rows.map { it.avd.id })
        assertEquals(AvdState.Running("emulator-5554"), vm.row("Pixel_9_API_36").state)
        assertEquals(AvdState.Stopped, vm.row("heads_up_api34").state)
        assertEquals(8_804_682_956L, vm.row("heads_up_api34").size)
        assertTrue(vm.row("Pixel_9_API_36").prefs.mirrorWhenReady)
        assertEquals(listOf("heads_up_api34.avd", "Pixel_9_API_36.avd"), measured)
    }

    @Test
    fun `sizes are measured off the calling thread`() = runTest {
        sdk()
        avd("Pixel_9_API_36", "Pixel 9")
        val io = Executors.newSingleThreadExecutor { Thread(it, "test-io") }
        try {
            val threads = mutableListOf<String>()
            val vm = viewModel(io = io.asCoroutineDispatcher(), sizeOf = { threads += Thread.currentThread().name.substringBefore(" @"); 1024L * 1024 * 512 })
            withContext(Dispatchers.Default) { withTimeout(5_000) { vm.state.first { s -> s.rows.any { it.size != null } } } }
            assertEquals(listOf("test-io"), threads)
            assertEquals("API 36 · Android 16 · 512.0 MB", subtitle(vm.row("Pixel_9_API_36")).text)
        } finally {
            io.shutdownNow()
        }
    }

    @Test
    fun `choosing an sdk folder saves it and reloads`() = runTest {
        val vm = viewModel()
        assertIs<SdkLookup.NotFound>(vm.state.value.sdk)
        sdk()
        env.remove("ANDROID_HOME")
        avd("Pixel_9_API_36", "Pixel 9")
        vm.onEvent(EmulatorEvent.SdkChosen(sdkRoot.toString()))
        runCurrent()
        assertEquals(sdkRoot.toString(), settings.settings.value.androidSdkPath)
        assertIs<SdkLookup.Found>(vm.state.value.sdk)
        assertEquals(listOf("Pixel 9"), vm.state.value.rows.map { it.avd.name })

        // A folder that isn't an SDK is still saved; the section says what it found there.
        val notSdk = Files.createDirectories(tmp.resolve("photos"))
        vm.onEvent(EmulatorEvent.SdkChosen(notSdk.toString()))
        runCurrent()
        assertEquals(notSdk.toString(), settings.settings.value.androidSdkPath)
        assertEquals(SdkLookup.NoEmulator(notSdk), vm.state.value.sdk)
    }

    @Test
    fun `toggles and expanded are saved`() = runTest {
        sdk()
        avd("Pixel_9_API_36", "Pixel 9")
        val vm = viewModel()
        assertTrue(vm.state.value.expanded)
        vm.onEvent(EmulatorEvent.ToggleMirror("Pixel_9_API_36"))
        vm.onEvent(EmulatorEvent.ToggleWindow("Pixel_9_API_36"))
        vm.onEvent(EmulatorEvent.SetExpanded(false))
        runCurrent()
        assertEquals(EmulatorPrefs(mirrorWhenReady = true, showWindow = true), settings.settings.value.emulatorPrefs["Pixel_9_API_36"])
        assertEquals(false, settings.settings.value.emulatorsExpanded)
        assertEquals(EmulatorPrefs(mirrorWhenReady = true, showWindow = true), vm.row("Pixel_9_API_36").prefs)
        assertEquals(false, vm.state.value.expanded)
        vm.onEvent(EmulatorEvent.ToggleMirror("Pixel_9_API_36"))
        runCurrent()
        assertEquals(false, settings.settings.value.emulatorPrefs.getValue("Pixel_9_API_36").mirrorWhenReady)
    }

    @Test
    fun `booting serials are hidden from the connected rows until ready`() = runTest {
        sdk()
        avd("Pixel_9_API_36", "Pixel 9")
        val vm = viewModel()
        vm.onEvent(EmulatorEvent.Start("Pixel_9_API_36"))
        runCurrent()
        assertEquals(listOf("-avd", "Pixel_9_API_36"), starter.commands.single().subList(1, 3))
        assertEquals(BootStage.Starting, assertIs<AvdState.Booting>(vm.row("Pixel_9_API_36").state).stage)
        assertEquals(setOf("emulator-5554"), vm.state.value.hiddenSerials)
        devices.value = listOf(emulator("emulator-5554").copy(state = DeviceState.Offline, rawState = "offline", info = null))
        runCurrent()
        assertEquals(setOf("emulator-5554"), vm.state.value.hiddenSerials)
    }

    @Test
    fun `stop names the row in its notice`() = runTest {
        sdk()
        avd("Pixel_9_API_36", "Pixel 9")
        adb.kills = false
        adb.avdNames["emulator-5554"] = "Pixel_9_API_36"
        devices.value = listOf(emulator("emulator-5554"))
        val launcher = launcher()
        val notices = mutableListOf<EmulatorNotice>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { launcher.notices.toList(notices) }
        val vm = viewModel(launcher)
        assertEquals(AvdState.Running("emulator-5554"), vm.row("Pixel_9_API_36").state)
        vm.onEvent(EmulatorEvent.Stop("Pixel_9_API_36"))
        runCurrent()
        assertEquals(AvdState.Stopping("emulator-5554"), vm.row("Pixel_9_API_36").state)
        advanceTimeBy(25_000)
        assertEquals(AvdState.Running("emulator-5554"), vm.row("Pixel_9_API_36").state)
        assertEquals(listOf(EmulatorNotice("Couldn't stop Pixel 9.")), notices)
    }

    @Test
    fun `retry repeats a cold boot cold`() = runTest {
        sdk()
        avd("Pixel_9_API_36", "Pixel 9")
        val vm = viewModel()
        vm.onEvent(EmulatorEvent.ColdBoot("Pixel_9_API_36"))
        runCurrent()
        assertTrue("-no-snapshot-load" in starter.commands.single())
        starter.processes.single().exit.complete(1)
        runCurrent()
        assertIs<AvdState.Failed>(vm.row("Pixel_9_API_36").state)
        vm.onEvent(EmulatorEvent.Retry("Pixel_9_API_36"))
        runCurrent()
        assertEquals(2, starter.commands.size)
        assertTrue("-no-snapshot-load" in starter.commands.last())
    }

    @Test
    fun `a stop reads the catalog again`() = runTest {
        sdk()
        avd("Pixel_9_API_36", "Pixel 9")
        adb.avdNames["emulator-5554"] = "Pixel_9_API_36"
        devices.value = listOf(emulator("emulator-5554"))
        val vm = viewModel()
        assertEquals(1, measured.size)
        vm.onEvent(EmulatorEvent.Stop("Pixel_9_API_36"))
        runCurrent()
        assertEquals(AvdState.Stopped, vm.row("Pixel_9_API_36").state)
        assertEquals(2, measured.size)
    }
}
