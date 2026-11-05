package com.programmersbox.scrcpyviewer.ui.actions

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import com.programmersbox.scrcpyviewer.actions.ActionSource
import com.programmersbox.scrcpyviewer.actions.ActionStore
import com.programmersbox.scrcpyviewer.actions.RunOnceAction
import com.programmersbox.scrcpyviewer.actions.withSource
import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.adb.DeviceTracker
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.common.defaultViewModelScope
import com.programmersbox.scrcpyviewer.ui.onEdt
import com.programmersbox.scrcpyviewer.ui.presets.PresetFileDialogs
import com.programmersbox.scrcpyviewer.ui.screenshotPanes
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The Actions screen with AI-made actions, rendered on a real scene (no crash) with the view model's group rules. */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class ActionsScreenTest {
    private class NoDevices : AdbClient(adbPath = { error("no adb") }) {
        override suspend fun startServer() {}
        override fun trackDevices(): Flow<String> = flow { awaitCancellation() }
    }

    private object NoFiles : PresetFileDialogs {
        override fun chooseExportFile(suggestedName: String): Path? = null
        override fun chooseImportFile(): Path? = null
    }

    private val trackerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val vmScope = defaultViewModelScope()
    private val store = ActionStore(Files.createTempDirectory("actions-screen").resolve("actions.json"))
    private val vm = ActionsViewModel(store, DeviceTracker(NoDevices(), trackerScope), NoDevices(), vmScope)
    private var driver: SceneDriver? = null

    @AfterTest
    fun tearDown() {
        onEdt { driver?.scene?.close() }
        vmScope.cancel()
        trackerScope.cancel()
    }

    private fun show() {
        onEdt {
            driver = SceneDriver(ImageComposeScene(900, 1000, Density(1f)) {
                ScrcpyViewerTheme(ThemeMode.Light) {
                    Surface(Modifier.fillMaxSize()) {
                        ActionsScreen(vm, NoFiles, introSeen = true, onIntroSeen = {}, panes = screenshotPanes(detailOpen = true))
                    }
                }
            })
        }
        settle()
    }

    private fun settle() = repeat(3) { onEdt { driver!!.frames(20) } }

    private fun run(id: String) = RunOnceAction(id, id, command = "echo $id")

    @Test
    fun `list with AI actions renders and a move stays inside the group`() {
        store.upsert(run("a").withSource(ActionSource.Ai))
        store.upsert(run("u1"))
        store.upsert(run("b").withSource(ActionSource.Ai))
        store.upsert(run("u2"))
        show()
        // The drag handler reports a group-relative index.
        vm.move("b", 0)
        settle()
        assertEquals(listOf("b", "a"), store.actions.value.filter { it.source == ActionSource.Ai }.map { it.id })
        assertEquals(listOf("u1", "u2"), store.actions.value.filter { it.source == ActionSource.User }.map { it.id })
    }

    @Test
    fun `editor of an AI action renders and Move to Your actions updates the draft`() {
        store.upsert(run("a").withSource(ActionSource.Ai))
        show()
        onEdt { vm.select("a") }
        settle()
        assertEquals(ActionSource.Ai, vm.state.value.draft!!.source)
        onEdt { vm.markAsUser("a") }
        settle()
        assertEquals(ActionSource.User, vm.state.value.draft!!.source)
    }
}
