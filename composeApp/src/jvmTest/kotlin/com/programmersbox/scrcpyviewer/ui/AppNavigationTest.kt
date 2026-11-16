package com.programmersbox.scrcpyviewer.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.programmersbox.scrcpyviewer.ui.common.ListDetailPanes
import com.programmersbox.scrcpyviewer.ui.common.rememberListDetailPanes
import com.programmersbox.scrcpyviewer.actions.ActionStore
import com.programmersbox.scrcpyviewer.actions.RunOnceAction
import com.programmersbox.scrcpyviewer.ui.actions.ActionsNavigation
import com.programmersbox.scrcpyviewer.ui.actions.ActionsViewModel
import com.programmersbox.scrcpyviewer.ui.actions.ActionsRequest
import com.programmersbox.scrcpyviewer.ui.devices.DevicesViewModel
import com.programmersbox.scrcpyviewer.ui.devices.WifiNotice
import com.programmersbox.scrcpyviewer.ui.navigation.TopLevelNavigator
import com.programmersbox.scrcpyviewer.ui.navigation.TopLevelRoute
import com.programmersbox.scrcpyviewer.withFakeAppKoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.koin.compose.viewmodel.koinViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The whole App on a fake adb: switching rail tabs keeps the device selection, the ViewModel and the dragged divider. */
class AppNavigationTest {
    @Test
    fun `selection and divider survive switching to Presets and back`() = withFakeAppKoin {
        val navigator = TopLevelNavigator()
        var devicesPanes: ListDetailPanes? = null
        val viewModels = mutableListOf<DevicesViewModel>()
        onEdt {
            val driver = SceneDriver(ImageComposeScene(1280, 800, Density(1f)) {
                val panes = rememberListDetailPanes().also { devicesPanes = it }
                val devicesViewModel = koinViewModel<DevicesViewModel>().also { viewModels += it }
                App(navigator = navigator, devicesPanes = panes, devicesViewModel = devicesViewModel)
            })
            try {
                driver.frames(10)
                val vm = viewModels.first()
                vm.select("39121FDJH004AB")
                driver.frames(5)
                val panes = assertNotNull(devicesPanes)
                val before = assertNotNull(panes.listWidthPx, "two-pane layout at 1280 px")
                // The rail is 80 px wide; the handle is centred in the 16 px gap after the list.
                val handleX = 80f + before + 8f
                driver.drag(fromX = handleX, toX = handleX + 150f, y = 400f)
                val dragged = assertNotNull(panes.listWidthPx)
                assertTrue(dragged > before + 100, "divider moved: $before -> $dragged")
                // The dragged width is kept in dp; the px read-out can differ by rounding once the drag ends.
                val draggedWidth = assertNotNull(panes.listWidth)

                navigator.select(TopLevelRoute.Presets)
                driver.frames(40)
                assertEquals(TopLevelRoute.Presets, navigator.current)
                navigator.select(TopLevelRoute.Devices)
                driver.frames(40)

                assertSame(vm, viewModels.last(), "same ViewModel after switching tabs")
                assertEquals("39121FDJH004AB", vm.selectedSerial.value)
                assertEquals(draggedWidth, panes.listWidth)
                assertTrue(kotlin.math.abs(dragged - assertNotNull(panes.listWidthPx)) <= 1, "laid out at the dragged width")
            } finally {
                driver.scene.close()
            }
        }
    }

    @Test
    fun `an Actions request from a mirror window selects the Actions tab and opens the action`() = withFakeAppKoin { koin ->
        val navigator = TopLevelNavigator()
        val actionsNavigation = koin.get<ActionsNavigation>()
        koin.get<ActionStore>().upsert(RunOnceAction("id", "Reboot", command = "reboot"))
        // Built here, not with koinViewModel(): the test window's ViewModelStore outlives a test's Koin graph.
        val vm = ActionsViewModel(koin.get(), koin.get(), koin.get())
        lateinit var driver: SceneDriver
        onEdt {
            driver = SceneDriver(ImageComposeScene(1280, 800, Density(1f)) {
                App(navigator = navigator, actionsViewModel = vm, actionsNavigation = actionsNavigation)
            })
        }
        try {
            onEdt {
                driver.frames(10)
                assertEquals(TopLevelRoute.Devices, navigator.current)
                actionsNavigation.open("id")
                driver.frames(10)
                assertEquals(TopLevelRoute.Actions, navigator.current)
            }
            // The view model's state is derived on the main (EDT) dispatcher, so it updates once the EDT is free.
            runBlocking { withTimeout(5_000) { vm.state.first { it.selectedId == "id" } } }
        } finally {
            onEdt { driver.scene.close() }
        }
    }

    @Test
    fun `Wi-Fi notices and Actions messages show while another tab is up`() = withFakeAppKoin { koin ->
        val navigator = TopLevelNavigator()
        val snackbar = SnackbarHostState()
        // Built here, not with koinViewModel(): the test window's ViewModelStore outlives a test's Koin graph.
        val actionsVm = ActionsViewModel(koin.get(), koin.get(), koin.get())
        lateinit var devicesVm: DevicesViewModel
        lateinit var driver: SceneDriver
        onEdt {
            driver = SceneDriver(ImageComposeScene(1280, 800, Density(1f)) {
                devicesVm = koinViewModel<DevicesViewModel>()
                App(navigator = navigator, devicesViewModel = devicesVm, actionsViewModel = actionsVm, snackbar = snackbar)
            })
        }
        try {
            onEdt {
                driver.frames(10)
                navigator.select(TopLevelRoute.Presets)
                driver.frames(40)
                devicesVm.notify(WifiNotice("Connected to Pixel", mirrorSerial = "10.0.0.5:5555"))
                driver.frames(5)
                val shown = snackbar.currentSnackbarData
                assertEquals("Connected to Pixel", shown?.visuals?.message)
                assertEquals("Mirror", shown?.visuals?.actionLabel)
                shown?.dismiss()
                driver.frames(5)
            }
            // Deleting the open action from elsewhere (a mirror tile) says so, even with Actions not showing.
            val store = koin.get<ActionStore>()
            store.upsert(RunOnceAction("gone", "Reboot", command = "reboot"))
            actionsVm.handle(ActionsRequest("gone", false))
            runBlocking { withTimeout(5_000) { actionsVm.state.first { it.selectedId == "gone" } } }
            store.delete("gone")
            runBlocking {
                withTimeout(5_000) {
                    var message: String? = null
                    while (message != "Reboot was deleted") {
                        delay(20)
                        onEdt {
                            driver.frame()
                            message = snackbar.currentSnackbarData?.visuals?.message
                        }
                    }
                }
            }
        } finally {
            onEdt { driver.scene.close() }
        }
    }

    @Test
    fun `open emits a request with the action id and the new flag`() {
        val nav = ActionsNavigation()
        val got = mutableListOf<ActionsRequest>()
        runBlocking {
            val job = launch(kotlinx.coroutines.Dispatchers.Unconfined) { nav.requests.collect { got += it } }
            nav.open("a")
            nav.open(null, new = true)
            nav.open(null)
            job.cancel()
        }
        assertEquals(listOf(ActionsRequest("a", false), ActionsRequest(null, true), ActionsRequest(null, false)), got)
    }

    @Test
    fun `openNew emits a new action request from the template without the banner`() {
        val nav = ActionsNavigation()
        val got = mutableListOf<ActionsRequest>()
        val t = com.programmersbox.scrcpyviewer.actions.ActionTemplates.all.first()
        runBlocking {
            val job = launch(kotlinx.coroutines.Dispatchers.Unconfined) { nav.requests.collect { got += it } }
            nav.openNew(t)
            job.cancel()
        }
        assertEquals(listOf(ActionsRequest(null, new = true, template = t, banner = false)), got)
    }
}
