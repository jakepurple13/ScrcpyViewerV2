package com.programmersbox.scrcpyviewer.emulator

import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.adb.ProcessResult
import com.programmersbox.scrcpyviewer.di.AppScope
import com.programmersbox.scrcpyviewer.di.startAppKoin
import com.programmersbox.scrcpyviewer.update.FakeUpdateBackend
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.koin.core.context.stopKoin
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertTrue

class EmulatorLauncherKoinTest {
    @Test
    fun `the launcher is created at start and matches running emulators unasked`() {
        val execs = CopyOnWriteArrayList<List<String>>()
        val adb = object : AdbClient(adbPath = { error("no real adb in tests") }) {
            override fun trackDevices(): Flow<String> = flow {
                emit("emulator-5554 device product:sdk_gphone64_arm64 model:sdk_gphone64_arm64 transport_id:4\n")
                awaitCancellation()
            }

            override suspend fun startServer() {}
            override suspend fun shell(serial: String, cmd: String): String = ""
            override suspend fun exec(vararg args: String): ProcessResult {
                execs += args.toList()
                return ProcessResult(0, "Pixel_9_API_36\nOK\n", "")
            }

            override fun startProcess(vararg args: String): Process = error("no real adb in tests")
        }
        val koin = Files.createTempDirectory("launcher-koin").let { dir ->
            startAppKoin(dir, adbClientFactory = { adb }, updateBackend = FakeUpdateBackend(), emulatorEnvironment = com.programmersbox.scrcpyviewer.fakeEmulatorEnvironment(dir))
        }
        try {
            // Nothing asks Koin for the launcher: only an eager singleton sends `emu avd name`.
            val deadline = System.currentTimeMillis() + 5_000
            while (execs.none { it == listOf("-s", "emulator-5554", "emu", "avd", "name") } && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
            assertTrue(execs.any { it == listOf("-s", "emulator-5554", "emu", "avd", "name") }, "execs: $execs")
        } finally {
            koin.get<AppScope>().cancel()
            stopKoin()
        }
    }
}
