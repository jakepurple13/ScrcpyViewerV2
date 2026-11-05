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

/** Smoke test: the Actions screen composes and renders with both groups and an AI draft open. Group rules are in ActionGroupsTest. */
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
    fun `screen with both groups and an AI draft open renders without error`() {
        store.upsert(run("a").withSource(ActionSource.Ai))
        store.upsert(run("u1"))
        show()
        onEdt { vm.select("a") }
        settle()
        var image: org.jetbrains.skia.Image? = null
        onEdt { image = driver!!.scene.render(1_000_000_000L) }
        assertEquals(900, image!!.width)
        assertEquals(1000, image!!.height)
    }
}
