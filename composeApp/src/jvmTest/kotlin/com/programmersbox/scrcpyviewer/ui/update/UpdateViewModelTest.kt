package com.programmersbox.scrcpyviewer.ui.update

import kotlin.test.assertNotNull
import java.io.File
import dev.nucleusframework.updater.DownloadProgress
import kotlinx.coroutines.CompletableDeferred
import com.programmersbox.scrcpyviewer.update.FakeUpdateBackend
import com.programmersbox.scrcpyviewer.update.UpdateManager
import com.programmersbox.scrcpyviewer.update.UpdateState
import dev.nucleusframework.updater.UpdateEvent
import dev.nucleusframework.updater.UpdateLevel
import dev.nucleusframework.updater.UpdateResult
import dev.nucleusframework.updater.exception.NoMatchingFileException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateViewModelTest {
    private val managerScope = CoroutineScope(Job())
    private val vmScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @AfterTest
    fun cleanup() {
        managerScope.cancel()
        vmScope.cancel()
    }

    private fun vm(
        canInstall: Boolean = true,
        macNotInApplications: Boolean = false,
        event: UpdateEvent? = null,
        currentVersion: String = "1.5.0",
        autoDownload: Boolean = false,
        backend: FakeUpdateBackend =
            FakeUpdateBackend(canInstall = canInstall, event = event) { FakeUpdateBackend.available("1.6.0") },
    ): UpdateViewModel {
        val manager = UpdateManager(
            backend = backend,
            scope = managerScope,
            currentVersion = currentVersion,
            bundledAdbInUse = { null },
            macNotInApplications = macNotInApplications,
            repo = "jakepurple13/ScrcpyViewerV2",
            autoDownload = MutableStateFlow(autoDownload),
        )
        return UpdateViewModel(manager, vmScope)
    }

    private fun UpdateViewModel.checkAndAwaitAvailable(): UpdateUiState {
        check()
        return runBlocking { withTimeout(5_000) { state.first { it.state is UpdateState.Available } } }
    }

    @Test
    fun `popup shows for a newer release and Later hides it`() {
        val vm = vm()
        assertFalse(vm.state.value.showPopup)
        assertFalse(vm.state.value.canSelfUpdate, "nothing to update to yet")
        val ui = vm.checkAndAwaitAvailable()
        assertTrue(ui.showPopup)
        assertEquals("1.6.0", ui.available?.version)
        assertEquals("1.5.0", ui.currentVersion)
        assertTrue(ui.canSelfUpdate)
        vm.later()
        // The manager emits on Dispatchers.Default, so wait for the dismissal instead of reading state.value at once.
        val dismissed = runBlocking { withTimeout(5_000) { vm.state.first { !it.showPopup } } }
        assertEquals("1.6.0", dismissed.available?.version)
    }

    @Test
    fun `dev run or unsupported package offers GitHub instead`() {
        val ui = vm(canInstall = false).checkAndAwaitAvailable()
        assertTrue(ui.showPopup)
        assertFalse(ui.canSelfUpdate)
        assertFalse(ui.macNotInApplications)
    }

    @Test
    fun `release with no file for this OS offers GitHub and shows its notes`() {
        val manager = UpdateManager(
            backend = FakeUpdateBackend(latest = { "1.6.0" }, notes = { "- Notes" }) {
                UpdateResult.Error(NoMatchingFileException("Linux", "ARM64", "deb"))
            },
            scope = managerScope,
            currentVersion = "1.5.0",
            bundledAdbInUse = { null },
            macNotInApplications = false,
        )
        val ui = UpdateViewModel(manager, vmScope).checkAndAwaitAvailable()
        assertTrue(ui.showPopup)
        assertFalse(ui.canSelfUpdate)
        assertEquals("- Notes", ui.available?.body)
    }

    @Test
    fun `mac outside Applications is flagged and cannot self update`() {
        val vm = vm(macNotInApplications = true)
        assertTrue(vm.state.value.macNotInApplications)
        assertFalse(vm.checkAndAwaitAvailable().canSelfUpdate)
    }

    @Test
    fun `previous failure reaches the ui state`() {
        val event = UpdateEvent("1.5.0", "1.6.0", UpdateLevel.MINOR)
        assertEquals("Updating to 1.6.0 did not complete.", vm(event = event).state.value.previousFailure)
    }

    @Test
    fun `completed update reaches the ui state`() {
        val event = UpdateEvent("1.5.0", "1.6.0", UpdateLevel.MINOR)
        val ui = vm(event = event, currentVersion = "1.6.0").state.value
        assertEquals("1.5.0", ui.updatedFrom)
        assertEquals(null, ui.previousFailure)
    }

    @Test
    fun `release page url is the release or the latest page`() {
        val vm = vm()
        assertEquals("https://github.com/jakepurple13/ScrcpyViewerV2/releases/latest", vm.state.value.releasePageUrl)
        assertEquals(
            "https://github.com/jakepurple13/ScrcpyViewerV2/releases/tag/v1.6.0",
            vm.checkAndAwaitAvailable().releasePageUrl,
        )
    }

    @Test
    fun `background download hides the popup until Ready`() {
        val finish = CompletableDeferred<Unit>()
        val backend = FakeUpdateBackend(downloadBlock = { emit ->
            emit(DownloadProgress(40, 100, 40.0))
            finish.await()
            emit(DownloadProgress(100, 100, 100.0, file = File("a.zip")))
        }) { FakeUpdateBackend.available("1.6.0") }
        val vm = vm(autoDownload = true, backend = backend)
        vm.check()
        val downloading = runBlocking { withTimeout(5_000) { vm.state.first { it.state is UpdateState.Downloading } } }
        assertFalse(downloading.showPopup)
        assertNotNull(downloading.available, "the badge still shows")
        finish.complete(Unit)
        val ready = runBlocking { withTimeout(5_000) { vm.state.first { it.state is UpdateState.Ready } } }
        assertTrue(ready.showPopup)
    }

    @Test
    fun `canInstall reaches the ui state`() {
        assertTrue(vm().state.value.canInstall)
        assertFalse(vm(canInstall = false).state.value.canInstall)
        assertFalse(vm(macNotInApplications = true).state.value.canInstall)
    }
}
