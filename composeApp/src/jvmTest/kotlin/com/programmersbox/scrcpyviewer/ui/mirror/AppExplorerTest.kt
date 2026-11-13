package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.apps.AppFilter
import com.programmersbox.scrcpyviewer.apps.DeviceApp
import com.programmersbox.scrcpyviewer.apps.DeviceApps
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

private val CHROME = DeviceApp("com.android.chrome", "Chrome", false, "131", 677)
private val SETTINGS = DeviceApp("com.android.settings", "Settings", true, "14", 34)

/** Lists [apps] once [gate] completes and records the commands it is asked to run. */
private class FakeDeviceApps(
    var apps: List<DeviceApp> = listOf(CHROME, SETTINGS),
    val gate: CompletableDeferred<Unit> = CompletableDeferred(Unit),
    var fail: String? = null,
) : DeviceApps(object : AdbClient(adbPath = { error("no adb") }) {}, "SER", { null }, "5.0") {
    var lists = 0
    val commands = mutableListOf<String>()

    override suspend fun list(): List<DeviceApp> {
        lists++
        gate.await()
        fail?.let { throw IOException(it) }
        return apps
    }

    override suspend fun launch(packageName: String) {
        commands += "launch $packageName"
    }

    override suspend fun forceStop(packageName: String) {
        commands += "stop $packageName"
    }

    override suspend fun uninstall(packageName: String, system: Boolean) {
        commands += "uninstall $packageName system=$system"
        fail?.let { throw IOException(it) }
    }
}

class AppExplorerTest {
    private val eager = CoroutineScope(Dispatchers.Unconfined)

    @Test
    fun `lists once, shows loading meanwhile, and reloads on request`() {
        val gate = CompletableDeferred<Unit>()
        val device = FakeDeviceApps(gate = gate)
        val explorer = AppExplorer(device, null, eager) {}
        explorer.ensureLoaded()
        explorer.ensureLoaded()
        assertTrue(explorer.uiState.loading)
        assertNull(explorer.uiState.apps)
        gate.complete(Unit)
        assertEquals(listOf(CHROME, SETTINGS), explorer.uiState.apps)
        assertEquals(false, explorer.uiState.loading)
        explorer.ensureLoaded()
        assertEquals(1, device.lists)
        explorer.onEvent(AppsEvent.Reload)
        assertEquals(2, device.lists)
    }

    @Test
    fun `search and filter narrow the visible apps and the counts`() {
        val explorer = AppExplorer(FakeDeviceApps(), null, eager) {}
        explorer.ensureLoaded()
        explorer.onEvent(AppsEvent.Filter(AppFilter.System))
        assertEquals(listOf(SETTINGS), explorer.uiState.visible)
        explorer.onEvent(AppsEvent.Filter(AppFilter.All))
        explorer.onEvent(AppsEvent.Search("chrome"))
        assertEquals(listOf(CHROME), explorer.uiState.visible)
        assertEquals(1, explorer.uiState.count(AppFilter.User))
        assertEquals(0, explorer.uiState.count(AppFilter.System))
    }

    @Test
    fun `a failed first listing shows no apps and says why`() {
        val messages = mutableListOf<String>()
        val explorer = AppExplorer(FakeDeviceApps(fail = "device offline"), null, eager) { messages += it }
        explorer.ensureLoaded()
        assertEquals(emptyList(), explorer.uiState.apps)
        assertEquals(false, explorer.uiState.loading)
        assertEquals(listOf("Could not list apps: device offline"), messages)
    }

    @Test
    fun `commands run on the device and report back`() {
        val messages = mutableListOf<String>()
        val device = FakeDeviceApps()
        val explorer = AppExplorer(device, null, eager) { messages += it }
        explorer.ensureLoaded()
        explorer.onEvent(AppsEvent.Launch(CHROME))
        explorer.onEvent(AppsEvent.ForceStop(CHROME))
        explorer.onEvent(AppsEvent.Copied(CHROME))
        explorer.onEvent(AppsEvent.Uninstall(SETTINGS))
        assertEquals(listOf("launch com.android.chrome", "stop com.android.chrome", "uninstall com.android.settings system=true"), device.commands)
        assertEquals(listOf("Launched Chrome", "Force stopped Chrome", "Copied com.android.chrome", "Uninstalled Settings"), messages)
        assertEquals(listOf(CHROME), explorer.uiState.apps)
        assertEquals(emptySet(), explorer.uiState.busy)
    }

    @Test
    fun `a failed uninstall keeps the app`() {
        val messages = mutableListOf<String>()
        val device = FakeDeviceApps()
        val explorer = AppExplorer(device, null, eager) { messages += it }
        explorer.ensureLoaded()
        device.fail = "Failure [DELETE_FAILED_INTERNAL_ERROR]"
        explorer.onEvent(AppsEvent.Uninstall(CHROME))
        assertEquals(listOf(CHROME, SETTINGS), explorer.uiState.apps)
        assertEquals(listOf("Could not uninstall Chrome: Failure [DELETE_FAILED_INTERNAL_ERROR]"), messages)
    }

    @Test
    fun `the Apps tab lists on first show and keeps its own width`() {
        val device = FakeDeviceApps()
        val saved = mutableListOf<Pair<SheetTab, androidx.compose.ui.unit.Dp>>()
        val qa = QuickActions(
            device = object : DeviceQuickActions(object : AdbClient(adbPath = { error("no adb") }) {}, "SER") {
                override suspend fun read(): Map<QuickToggle, Boolean?> = emptyMap()
            },
            scope = eager,
            saveWidth = { tab, width -> saved += tab to width },
            apps = AppExplorer(device, null, eager) {},
        ) {}
        assertEquals(0, device.lists)
        qa.onEvent(QuickActionEvent.SelectTab(SheetTab.Apps))
        assertEquals(1, device.lists)
        assertEquals(480.dp, qa.uiState.widths[SheetTab.Apps])
        qa.onEvent(QuickActionEvent.Resize(560.dp))
        qa.onEvent(QuickActionEvent.ResizeDone(SheetTab.Apps))
        assertEquals(listOf(SheetTab.Apps to 560.dp), saved)
        assertEquals(280.dp, qa.uiState.widths[SheetTab.Actions])
        qa.onEvent(QuickActionEvent.Apps(AppsEvent.Search("set")))
        assertEquals(listOf(SETTINGS), qa.uiState.apps.visible)
    }
}
