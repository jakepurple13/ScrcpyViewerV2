package com.programmersbox.scrcpyviewer.ui.tray

import com.programmersbox.scrcpyviewer.ui.mirror.MirrorRequests
import com.programmersbox.scrcpyviewer.ui.navigation.MainWindowRequest
import com.programmersbox.scrcpyviewer.ui.navigation.MainWindowRequests
import com.programmersbox.scrcpyviewer.ui.navigation.TopLevelRoute
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

class TrayCommandsTest {
    private val mirrorRequests = MirrorRequests()
    private val mainWindow = MainWindowRequests()
    private val calls = ArrayList<String>()
    private var mirrorable = true

    private val commands = TrayCommands(
        mirrorRequests = mirrorRequests,
        mainWindow = mainWindow,
        canMirror = { mirrorable },
        focus = { calls += "focus $it" },
        select = { calls += "select $it" },
        quit = { calls += "quit" },
    )

    /** The first value [flow] gets while [action] runs. */
    private fun <T> firstOf(flow: Flow<T>, action: () -> Unit): T = runBlocking {
        val first = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { flow.first() } }
        action()
        first.await()
    }

    @Test
    fun `mirror goes through MirrorRequests like the Devices Mirror button`() {
        assertEquals("a", firstOf(mirrorRequests) { commands.dispatch(TrayAction.Mirror, "a") })
        assertEquals(emptyList(), calls)
    }

    @Test
    fun `mirror with invalid options selects the device and shows the Devices screen`() {
        mirrorable = false
        val request = firstOf(mainWindow.requests) { commands.dispatch(TrayAction.Mirror, "a") }
        assertEquals(MainWindowRequest(TopLevelRoute.Devices), request)
        assertEquals(listOf("select a"), calls)
    }

    @Test
    fun `focus asks the mirror window forward`() {
        commands.dispatch(TrayAction.Focus, "a")
        assertEquals(listOf("focus a"), calls)
    }

    @Test
    fun `open brings the main window forward without changing screen`() {
        assertEquals(MainWindowRequest(null), firstOf(mainWindow.requests) { commands.dispatch(TrayAction.OpenMain, null) })
    }

    @Test
    fun `settings brings the main window forward on Settings`() {
        assertEquals(
            MainWindowRequest(TopLevelRoute.Settings),
            firstOf(mainWindow.requests) { commands.dispatch(TrayAction.Settings, null) },
        )
    }

    @Test
    fun `quit uses the shutdown path`() {
        commands.dispatch(TrayAction.Quit, null)
        assertEquals(listOf("quit"), calls)
    }

    @Test
    fun `device actions without a serial do nothing`() {
        commands.dispatch(TrayAction.Mirror, null)
        commands.dispatch(TrayAction.Focus, null)
        assertEquals(emptyList(), calls)
    }
}
