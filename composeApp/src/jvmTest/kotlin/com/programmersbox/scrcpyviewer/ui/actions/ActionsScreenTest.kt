package com.programmersbox.scrcpyviewer.ui.actions

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
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
        override suspend fun chooseExportFile(suggestedName: String): Path? = null
        override suspend fun chooseImportFile(): Path? = null
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
                        ActionsScreen(vm, NoFiles, introSeen = true, onIntroSeen = {}, panes = screenshotPanes(pushed = true))
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
        image!!.close()
    }

    @Test
    fun `a drag carries on when another action is deleted mid-drag`() {
        store.upsert(run("a"))
        store.upsert(run("b"))
        store.upsert(run("c"))
        show()
        fun handles(): List<Offset> {
            val all = mutableListOf<SemanticsNode>()
            fun walk(n: SemanticsNode) {
                all += n
                n.children.forEach(::walk)
            }
            onEdt { driver!!.scene.semanticsOwners.forEach { walk(it.unmergedRootSemanticsNode) } }
            return all.filter { it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { d -> d == "Drag to reorder" } == true }
                .map { it.boundsInRoot.center }.sortedBy { it.y }
        }
        val before = handles()
        assertEquals(3, before.size)
        val step = before[1].y - before[0].y
        val start = before[2]
        fun move(to: Offset) = onEdt {
            driver!!.scene.sendPointerEvent(PointerEventType.Move, to, type = PointerType.Mouse)
            driver!!.frame()
        }
        onEdt {
            driver!!.scene.sendPointerEvent(PointerEventType.Move, start, type = PointerType.Mouse)
            driver!!.scene.sendPointerEvent(PointerEventType.Press, start, type = PointerType.Mouse, button = PointerButton.Primary)
            driver!!.frame()
        }
        // Half a row up, in small steps (past the touch slop).
        for (i in 1..10) move(Offset(start.x, start.y - step * 0.5f * i / 10))
        // Deleted elsewhere (a mirror tile) while c is held: c's row moves up a slot but keeps its gesture.
        store.delete("a")
        settle()
        for (i in 1..10) move(Offset(start.x, start.y - step * 0.5f - step * 0.5f * i / 10))
        onEdt {
            driver!!.scene.sendPointerEvent(PointerEventType.Release, Offset(start.x, start.y - step), type = PointerType.Mouse, button = PointerButton.Primary)
        }
        settle()
        assertEquals(listOf("c", "b"), store.actions.value.map { it.id }, "the drag still dropped c above b")
        // Nothing is left displaced: the rows sit one step apart again.
        val after = handles()
        assertEquals(2, after.size)
        assertEquals(step, after[1].y - after[0].y, 0.5f)
    }
}
