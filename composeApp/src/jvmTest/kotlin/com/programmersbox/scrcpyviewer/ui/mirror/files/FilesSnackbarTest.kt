package com.programmersbox.scrcpyviewer.ui.mirror.files

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.adb.ShellRunner
import com.programmersbox.scrcpyviewer.files.DeviceFiles
import com.programmersbox.scrcpyviewer.files.DeviceSpace
import com.programmersbox.scrcpyviewer.files.Listing
import com.programmersbox.scrcpyviewer.files.TransferJob
import com.programmersbox.scrcpyviewer.state.AppSettings
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.mirror.FileTransfers
import com.programmersbox.scrcpyviewer.ui.mirror.QuickActionsMemory
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class FilesSnackbarTest {
    private class NoFiles : DeviceFiles(ShellRunner(object : AdbClient(adbPath = { error("no adb") }) {}, "serial"), {}) {
        override suspend fun list(dir: String): Listing = Listing.Entries(emptyList())
        override suspend fun counts(dir: String, folders: List<String>): Map<String, Int> = emptyMap()
        override suspend fun freeSpace() = DeviceSpace(1, 2)
    }

    /** Opening a folder clears the selection; the lift springs past 0 and must not reach padding negative. */
    @Test
    fun `clearing the selection with motion does not crash`() {
        val browser = FilesBrowser(
            "SER", NoFiles(), FileTransfers(queue = null, pushTarget = "/sdcard/Download/") {}, QuickActionsMemory(),
            AppSettings(Files.createTempDirectory("snack").resolve("settings.json")), "/sdcard/Download",
            CoroutineScope(UnconfinedTestDispatcher(TestCoroutineScheduler())), enqueue = { emptyList() },
            jobs = MutableStateFlow<List<TransferJob>>(emptyList()),
        )
        // A recomposition failure lands here, not in the test thread.
        val errors = mutableListOf<Throwable>()
        val catching = Dispatchers.Unconfined + CoroutineExceptionHandler { _, t -> errors += t }
        val d = SceneDriver(ImageComposeScene(440, 400, Density(1f), catching) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                Surface(Modifier.fillMaxSize()) { FilesSnackbar(browser, motion = true) }
            }
        })
        d.frames(5)
        browser.toggle("a.txt")
        check(browser.selection.names.isNotEmpty()) { "the selection bar should show" }
        d.frames(60)
        browser.clearSelection()
        d.frames(60)
        assertEquals(emptyList(), errors.map { it.toString() })
    }
}
