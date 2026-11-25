package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.apps.DeviceApp
import com.programmersbox.scrcpyviewer.database.RootState
import com.programmersbox.scrcpyviewer.ui.mirror.database.DatabaseController
import com.programmersbox.scrcpyviewer.ui.mirror.database.FakeDevice
import com.programmersbox.scrcpyviewer.ui.mirror.database.FakeProbe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val NOTES = DeviceApp("com.example.notes", "Notes", false, "1", 1, debuggable = true)

@OptIn(ExperimentalCoroutinesApi::class)
class QuickActionsDatabaseTest {
    private class Fixture(val qa: QuickActions, val db: DatabaseController, val memory: QuickActionsMemory)

    private fun TestScope.fixture(root: RootState = RootState.None): Fixture {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val temp = Files.createTempDirectory("qadb")
        val device = FakeDevice(Files.createTempDirectory(temp, "fx"), "CREATE TABLE t (v TEXT)")
        val db = DatabaseController(
            devices = device, probe = FakeProbe(root), apps = { listOf(NOTES) }, tempRoot = temp.resolve("work"),
            parentScope = kotlinx.coroutines.CoroutineScope(dispatcher), notify = {}, launch = {}, io = dispatcher,
        )
        val memory = QuickActionsMemory()
        return Fixture(QuickActions(device = null, scope = backgroundScope, memory = memory, database = db) {}, db, memory)
    }

    @Test
    fun `tabs run Actions, Apps, Files, Data, Shell, Links, Logcat`() {
        assertEquals(listOf("Actions", "Apps", "Files", "Data", "Shell", "Links", "Logcat"), SheetTab.entries.map { it.label() })
    }

    @Test
    fun `Databases on an app opens the Data tab on it`() = runTest {
        val f = fixture()
        f.qa.onEvent(QuickActionEvent.Apps(AppsEvent.ShowDatabases(NOTES)))
        advanceUntilIdle()
        assertEquals(SheetTab.Database, f.qa.uiState.tab)
        assertEquals(SheetTab.Database, f.memory.tab)
        assertEquals(NOTES, f.db.app)
    }

    @Test
    fun `Databases raises the own window instead of switching tabs`() = runTest {
        val f = fixture()
        f.db.popOut()
        f.qa.onEvent(QuickActionEvent.Apps(AppsEvent.ShowDatabases(NOTES)))
        advanceUntilIdle()
        assertEquals(1, f.db.raiseRequests)
        assertEquals(SheetTab.Actions, f.qa.uiState.tab)
        assertTrue(f.qa.uiState.databasePopped)
    }

    @Test
    fun `docking shows the Data tab`() = runTest {
        val f = fixture()
        f.qa.onSessionStatus(com.programmersbox.scrcpyviewer.scrcpy.SessionStatus.Running)
        f.db.popOut()
        f.db.dock()
        assertEquals(SheetTab.Database, f.qa.uiState.tab)
    }

    @Test
    fun `Show debuggable apps docks and opens Apps`() = runTest {
        val f = fixture()
        f.db.popOut()
        f.qa.onEvent(QuickActionEvent.ShowDebuggableApps)
        assertFalse(f.db.popped)
        assertEquals(SheetTab.Apps, f.qa.uiState.tab)
    }

    @Test
    fun `root reaches the Apps tab state and AskRoot asks`() = runTest {
        val f = fixture(RootState.Available)
        f.db.ensureRoot()
        advanceUntilIdle()
        assertEquals(RootState.Available, f.qa.uiState.apps.root)
        assertEquals(RootState.Available, f.qa.uiState.root)
        f.qa.onEvent(QuickActionEvent.AskRoot)
        advanceUntilIdle()
        assertEquals(RootState.Denied, f.qa.uiState.root)
    }

    @Test
    fun `database width defaults, clamps and switches by tab`() {
        assertEquals(620.dp, SheetWidths.default(SheetTab.Database))
        assertEquals(700.dp, SheetWidths().with(SheetTab.Database, 700.dp)[SheetTab.Database])
        assertEquals(620.dp, SheetWidths.fromSettings(280, 400, 480, 440, database = 100)[SheetTab.Database])
        assertEquals(900.dp, SheetWidths.fromSettings(280, 400, 480, 440, database = 900)[SheetTab.Database])
    }
}
