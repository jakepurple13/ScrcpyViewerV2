package com.programmersbox.scrcpyviewer.ui.mirror

import com.programmersbox.scrcpyviewer.actions.ToggleAction
import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.apps.DeviceApp
import com.programmersbox.scrcpyviewer.logcat.LogcatLine
import com.programmersbox.scrcpyviewer.logcat.LogcatSource
import com.programmersbox.scrcpyviewer.logcat.queryTerms
import com.programmersbox.scrcpyviewer.scrcpy.SessionStatus
import com.programmersbox.scrcpyviewer.ui.mirror.logcat.LogcatController
import com.programmersbox.scrcpyviewer.ui.mirror.logcat.LogcatRun
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private val CHROME = DeviceApp("com.android.chrome", "Chrome", false, "131", 677)

/** A stream that stays open until cancelled; counts how often it was started. */
private class OpenSource : LogcatSource(object : AdbClient(adbPath = { error("no adb") }) {}, "SER") {
    var starts = 0

    override fun lines(since: String?): Flow<LogcatLine> = flow {
        starts++
        awaitCancellation()
    }

    override suspend fun processes(): Map<Int, String> = emptyMap()
}

class QuickActionsLogcatTest {
    private class Fixture(val qa: QuickActions, val logcat: LogcatController, val source: OpenSource, val memory: QuickActionsMemory)

    private fun TestScope.fixture(): Fixture {
        val source = OpenSource()
        val logcat = LogcatController(source, backgroundScope, {}, io = kotlin.coroutines.EmptyCoroutineContext)
        val memory = QuickActionsMemory()
        val qa = QuickActions(device = null, scope = backgroundScope, memory = memory, logcat = logcat) {}
        return Fixture(qa, logcat, source, memory)
    }

    private fun TestScope.playOnLogcat(f: Fixture) {
        f.qa.onEvent(QuickActionEvent.SelectTab(SheetTab.Logcat))
        f.logcat.play()
        runCurrent()
        assertEquals(LogcatRun.Running, f.logcat.run)
    }

    @Test
    fun `leaving the Logcat tab stops logcat`() = runTest {
        val f = fixture()
        playOnLogcat(f)
        f.qa.onEvent(QuickActionEvent.SelectTab(SheetTab.Shell))
        assertEquals(LogcatRun.StoppedByLeaving, f.logcat.run)
    }

    @Test
    fun `closing the sheet stops it`() = runTest {
        val f = fixture()
        playOnLogcat(f)
        f.qa.onEvent(QuickActionEvent.Close)
        assertEquals(LogcatRun.StoppedByLeaving, f.logcat.run)
    }

    @Test
    fun `disconnect stops it`() = runTest {
        val f = fixture()
        playOnLogcat(f)
        f.qa.onSessionStatus(SessionStatus.Disconnected)
        assertEquals(LogcatRun.StoppedByLeaving, f.logcat.run)
        assertEquals(false, f.qa.connected)
    }

    @Test
    fun `returning to Logcat while stopped bumps nudge`() = runTest {
        val f = fixture()
        // Never started: nothing to resume, no pulse.
        f.qa.onEvent(QuickActionEvent.SelectTab(SheetTab.Logcat))
        assertEquals(0, f.logcat.nudge)

        f.logcat.play()
        runCurrent()
        f.qa.onEvent(QuickActionEvent.SelectTab(SheetTab.Apps))
        f.qa.onEvent(QuickActionEvent.SelectTab(SheetTab.Logcat))
        assertEquals(1, f.logcat.nudge)

        // Paused by the user, then away and back: still a pulse, and leaving keeps it Paused.
        f.logcat.play()
        runCurrent()
        f.logcat.pause()
        f.qa.onEvent(QuickActionEvent.SelectTab(SheetTab.Actions))
        assertEquals(LogcatRun.Paused, f.logcat.run)
        f.qa.onEvent(QuickActionEvent.SelectTab(SheetTab.Logcat))
        assertEquals(2, f.logcat.nudge)
    }

    @Test
    fun `reopening the sheet on a stopped Logcat tab bumps nudge`() = runTest {
        val source = OpenSource()
        val logcat = LogcatController(source, backgroundScope, {}, io = kotlin.coroutines.EmptyCoroutineContext)
        val device = object : DeviceQuickActions(object : AdbClient(adbPath = { error("no adb") }) {}, "SER") {
            override suspend fun read(custom: List<ToggleAction>) = ToggleStates(emptyMap(), emptyMap())
        }
        val qa = QuickActions(device = device, scope = backgroundScope, logcat = logcat) {}
        qa.toggleSheet()
        qa.onEvent(QuickActionEvent.SelectTab(SheetTab.Logcat))
        logcat.play()
        runCurrent()
        qa.toggleSheet() // closes: stops for leaving
        assertEquals(LogcatRun.StoppedByLeaving, logcat.run)
        assertEquals(0, logcat.nudge)
        qa.toggleSheet() // opens again on Logcat
        assertEquals(SheetTab.Logcat, qa.uiState.tab)
        assertEquals(1, logcat.nudge)
    }

    @Test
    fun `ShowInLogcat picks the app, adds package mine once, and selects Logcat without starting`() = runTest {
        val f = fixture()
        f.logcat.query = "level:error"
        f.qa.onEvent(QuickActionEvent.Apps(AppsEvent.ShowInLogcat(CHROME)))
        assertEquals("com.android.chrome", f.logcat.mine)
        assertEquals(listOf("level:error", "package:mine"), queryTerms(f.logcat.query))
        assertEquals(SheetTab.Logcat, f.qa.uiState.tab)
        assertEquals(SheetTab.Logcat, f.memory.tab)
        assertEquals(LogcatRun.NotStarted, f.logcat.run)
        assertEquals(0, f.source.starts)
        assertEquals(1, f.logcat.nudge)
        assertEquals("com.android.chrome", f.qa.uiState.apps.logcatPackage)

        // Again from Apps: the term is not added twice.
        f.qa.onEvent(QuickActionEvent.SelectTab(SheetTab.Apps))
        f.qa.onEvent(QuickActionEvent.Apps(AppsEvent.ShowInLogcat(CHROME)))
        assertEquals(listOf("level:error", "package:mine"), queryTerms(f.logcat.query))
    }

    @Test
    fun `ShowInLogcat while running keeps it running without a pulse`() = runTest {
        val f = fixture()
        playOnLogcat(f)
        f.qa.onEvent(QuickActionEvent.SelectTab(SheetTab.Apps))
        f.logcat.play() // resumed by hand from elsewhere (own window, later): still running
        runCurrent()
        val nudge = f.logcat.nudge
        f.qa.onEvent(QuickActionEvent.Apps(AppsEvent.ShowInLogcat(CHROME)))
        assertEquals(LogcatRun.Running, f.logcat.run)
        assertEquals(nudge, f.logcat.nudge)
        assertEquals(SheetTab.Logcat, f.qa.uiState.tab)
    }

    @Test
    fun `without logcat the Apps state has no picked package and the tab still selects`() = runTest {
        val qa = QuickActions(device = null, scope = backgroundScope) {}
        qa.onEvent(QuickActionEvent.Apps(AppsEvent.ShowInLogcat(CHROME)))
        assertEquals(SheetTab.Logcat, qa.uiState.tab)
        assertNull(qa.uiState.apps.logcatPackage)
    }
}
