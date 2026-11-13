package com.programmersbox.scrcpyviewer.ui.mirror.files

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.programmersbox.scrcpyviewer.Os
import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.options.OptionValues
import com.programmersbox.scrcpyviewer.scrcpy.LocalPairLauncher
import com.programmersbox.scrcpyviewer.scrcpy.ScrcpySession
import com.programmersbox.scrcpyviewer.state.AppSettings
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.mirror.FileTransfers
import com.programmersbox.scrcpyviewer.ui.mirror.QuickActionsMemory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenOnComputerTest {
    private val file = Paths.get(System.getProperty("java.io.tmpdir")).toAbsolutePath().resolve("my 写真 x.jpg")

    @Test
    fun `each platform runs its own opener with the path as one argument`() {
        val p = file.toString()
        assertEquals(listOf("open", p), openCommand(file, OpenPlatform.Mac))
        assertEquals(listOf("explorer.exe", p), openCommand(file, OpenPlatform.Windows))
        assertEquals(listOf("xdg-open", p), openCommand(file, OpenPlatform.Linux))
    }

    @Test
    fun `run receives the platform command and a started process is true`() {
        val seen = mutableListOf<List<String>>()
        val process = startDetached(listOf(if (Os.isWindows) "cmd" else "true"))
        assertNotNull(process)
        assertTrue(openOnComputer(file, OpenPlatform.Linux) { seen += it; process })
        assertEquals(listOf(listOf("xdg-open", file.toString())), seen)
        process.destroy()
    }

    @Test
    fun `a run that cannot start returns false`() {
        assertFalse(openOnComputer(file, OpenPlatform.Mac) { null })
    }

    @Test
    fun `a run that throws returns false`() {
        assertFalse(openOnComputer(file, OpenPlatform.Windows) { error("no explorer") })
        assertFalse(openOnComputer(file) { throw java.io.IOException("no opener") })
    }

    @Test
    fun `a missing opener program is null, not a throw`() {
        assertNull(startDetached(listOf("scrcpy-viewer-no-such-opener")))
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `the Files tab browser composes with no LocalUriHandler provided`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = ScrcpySession("SER", OptionValues(emptyMap()), LocalPairLauncher(), { _, _ -> null }, { _, _ -> null }, scope)
        val adb = object : AdbClient(adbPath = { error("no adb") }) {}
        val settings = AppSettings(Files.createTempDirectory("open-computer").resolve("settings.json"))
        val transfers = FileTransfers(queue = null, pushTarget = "/sdcard/Download/") {}
        var browser: FilesBrowser? = null
        val driver = SceneDriver(ImageComposeScene(200, 200, Density(1f)) {
            // No LocalUriHandler is provided here. (ImageComposeScene still supplies a default, unlike the Tao window,
            // so this guards the composition path, not the absence itself: the code must not need a handler.)
            browser = rememberFilesBrowser("SER", session, adb, transfers, QuickActionsMemory(), settings)
        })
        try {
            driver.frames(5)
            assertNotNull(browser)
        } finally {
            driver.scene.close()
            scope.cancel()
        }
    }
}
