package com.programmersbox.scrcpyviewer.ui

import androidx.compose.foundation.layout.Box
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import com.programmersbox.scrcpyviewer.ui.update.UpdateDialog
import com.programmersbox.scrcpyviewer.ui.update.UpdateUiState
import com.programmersbox.scrcpyviewer.ui.update.UpdateViewModel
import com.programmersbox.scrcpyviewer.update.Release
import com.programmersbox.scrcpyviewer.update.FakeUpdateBackend
import com.programmersbox.scrcpyviewer.update.UpdateManager
import com.programmersbox.scrcpyviewer.update.UpdateState
import com.programmersbox.scrcpyviewer.withFakeAppKoin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertTrue

/** Excluded from the default `jvmTest` run; run with `./gradlew :composeApp:jvmTest -Pscreenshots`. */
class UpdateScreenshotTest {
    private val outDir = Paths.get(System.getProperty("user.dir")).resolve("build/screenshots")

    private val release = Release(
        version = "1.6.0",
        body = "- Auto-update\n- Fix Wi-Fi pairing timeout",
        htmlUrl = "https://github.com/jakepurple13/ScrcpyViewerV2/releases/tag/v1.6.0",
    )

    private fun ui(state: UpdateState, canSelfUpdate: Boolean = true) = UpdateUiState(
        state = state,
        available = release,
        currentVersion = "1.5.0",
        showPopup = true,
        canSelfUpdate = canSelfUpdate,
    )

    private fun render(name: String, ui: UpdateUiState) = withFakeAppKoin {
        val out = outDir.resolve(name)
        renderToPng(1280, 800, out, settleMillis = 500) {
            Box {
                App()
                ScrcpyViewerTheme(ThemeMode.Light) { UpdateDialog(ui, {}, {}, {}) }
            }
        }
        assertTrue(Files.size(out) > 10_000, "$name should not be blank")
    }

    @Test
    fun `update available`() = render("update-available.png", ui(UpdateState.Available(release)))

    @Test
    fun `update downloading`() =
        render("update-downloading.png", ui(UpdateState.Downloading(release, 31_000_000, 50_000_000)))

    @Test
    fun `update ready`() = render("update-ready.png", ui(UpdateState.Ready(release)))

    @Test
    fun `update failed`() =
        render("update-failed.png", ui(UpdateState.Failed("Download failed: connection reset", release)))

    @Test
    fun `update previous failure`() = render(
        "update-previous-failure.png",
        ui(UpdateState.Available(release)).copy(previousFailure = "Updating to 1.6.0 did not complete."),
    )

    @Test
    fun `update without self update`() =
        render("update-no-self-update.png", ui(UpdateState.Available(release), canSelfUpdate = false))

    @Test
    fun `rail badge`() = withFakeAppKoin(updateBackend = FakeUpdateBackend { FakeUpdateBackend.available("9.9.9") }) { koin ->
        val manager = koin.get<UpdateManager>()
        manager.check(false)
        runBlocking { withTimeout(5_000) { manager.state.first { it is UpdateState.Available } } }
        manager.dismissPopup()
        val out = outDir.resolve("rail-badge.png")
        // Unconfined, so the ViewModel's state is current without the (blocked) main thread running.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val viewModel = UpdateViewModel(manager, scope = scope)
        try {
            renderToPng(1280, 800, out, settleMillis = 500) { App(updateViewModel = viewModel) }
        } finally {
            scope.cancel()
        }
        assertTrue(Files.size(out) > 10_000, "rail-badge.png should not be blank")
    }
}
