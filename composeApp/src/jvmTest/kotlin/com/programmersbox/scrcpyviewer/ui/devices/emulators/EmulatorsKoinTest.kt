package com.programmersbox.scrcpyviewer.ui.devices.emulators

import com.programmersbox.scrcpyviewer.emulator.EmulatorEnvironment
import com.programmersbox.scrcpyviewer.emulator.SdkLookup
import com.programmersbox.scrcpyviewer.withFakeAppKoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** App-level tests see a fixed Emulators section: nothing on this machine is read and no `du` runs. */
class EmulatorsKoinTest {
    @Test
    fun `the app graph in tests reads no real sdk`() {
        val dataDir = Files.createTempDirectory("emulators-koin")
        withFakeAppKoin(dataDir) { koin ->
            val environment = koin.get<EmulatorEnvironment>()
            assertNull(environment.env("ANDROID_HOME"))
            assertNull(environment.env("ANDROID_AVD_HOME"))
            assertTrue(environment.home.startsWith(dataDir), "home ${environment.home}")
            val vm = koin.get<EmulatorsViewModel>()
            vm.onEvent(EmulatorEvent.Reload)
            val state = runBlocking { withTimeout(5_000) { vm.state.first { it.sdk != null } } }
            assertIs<SdkLookup.NotFound>(state.sdk)
            assertTrue(state.rows.isEmpty())
        }
    }
}
