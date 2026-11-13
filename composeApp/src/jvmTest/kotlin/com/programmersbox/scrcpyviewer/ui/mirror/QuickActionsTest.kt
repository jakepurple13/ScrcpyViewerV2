package com.programmersbox.scrcpyviewer.ui.mirror

import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.adb.ProcessResult
import com.programmersbox.scrcpyviewer.adb.ShellResult
import com.programmersbox.scrcpyviewer.adb.ShellRunner
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** Answers shell commands from [answer] and records them. */
private class ScriptedAdb(private val answer: (String) -> String) : AdbClient(adbPath = { error("no real adb in tests") }) {
    val commands = mutableListOf<String>()
    val execs = mutableListOf<List<String>>()
    var execResult = ProcessResult(0, "", "")

    override suspend fun shell(serial: String, cmd: String): String {
        commands += cmd
        return answer(cmd)
    }

    override suspend fun exec(vararg args: String): ProcessResult {
        execs += args.toList()
        return execResult
    }
}

class QuickActionsTest {
    // Launches run at once, up to their first suspension; a completed deferred resumes them inline.
    private val eager = CoroutineScope(Dispatchers.Unconfined)

    @Test
    fun `parses each setting`() {
        assertEquals(true, QuickToggle.DarkMode.parse("Night mode: yes\n"))
        assertEquals(false, QuickToggle.DarkMode.parse("Night mode: no"))
        assertEquals(false, QuickToggle.DarkMode.parse("Night mode: auto"))
        assertNull(QuickToggle.DarkMode.parse("cmd: Can't find service: uimode"))
        assertEquals(true, QuickToggle.WiFi.parse("1"))
        assertEquals(true, QuickToggle.WiFi.parse("2"))
        assertEquals(false, QuickToggle.WiFi.parse("3"))
        assertEquals(false, QuickToggle.WiFi.parse("0\n"))
        assertEquals(false, QuickToggle.AirplaneMode.parse("null"))
        assertEquals(true, QuickToggle.ShowTaps.parse("1"))
        assertNull(QuickToggle.ShowTaps.parse(""))
        assertEquals(true, QuickToggle.AutoRotate.parse("1\n"))
        assertEquals(false, QuickToggle.AutoRotate.parse("0"))
        assertNull(QuickToggle.AutoRotate.parse("null"))
    }

    @Test
    fun `set commands`() {
        assertEquals("cmd uimode night yes", QuickToggle.DarkMode.setCommand(true))
        assertEquals("svc wifi disable", QuickToggle.WiFi.setCommand(false))
        assertEquals("cmd connectivity airplane-mode enable", QuickToggle.AirplaneMode.setCommand(true))
        assertEquals("settings put system show_touches 0", QuickToggle.ShowTaps.setCommand(false))
        assertEquals("settings put system accelerometer_rotation 1", QuickToggle.AutoRotate.setCommand(true))
    }

    @Test
    fun `reads every toggle in one shell call`() = runBlocking {
        val adb = ScriptedAdb { "Night mode: yes\n__scrcpyviewer_sep__\n1\n__scrcpyviewer_sep__\n0\n__scrcpyviewer_sep__\nnull\n__scrcpyviewer_sep__\n0\n" }
        val states = DeviceQuickActions(adb, "SER").read()
        assertEquals(
            mapOf(
                QuickToggle.DarkMode to true, QuickToggle.WiFi to true, QuickToggle.AirplaneMode to false, QuickToggle.ShowTaps to false,
                QuickToggle.AutoRotate to false,
            ),
            states,
        )
        assertEquals(1, adb.commands.size)
    }

    @Test
    fun `set checks the exit code`() = runBlocking {
        val ok = ScriptedAdb { "__scrcpyviewer_exit=0\n" }
        DeviceQuickActions(ok, "SER").set(QuickToggle.WiFi, true)
        assertEquals("svc wifi enable; echo __scrcpyviewer_exit=$?", ok.commands.single())

        val failing = ScriptedAdb { "Exception occurred while executing 'airplane-mode':\nSecurityException: denied\n__scrcpyviewer_exit=255\n" }
        val e = assertFailsWith<IOException> { DeviceQuickActions(failing, "SER").set(QuickToggle.AirplaneMode, true) }
        assertEquals("SecurityException: denied", e.message)
    }

    @Test
    fun `developer options fails on an unresolved intent`() = runBlocking {
        val adb = ScriptedAdb { "Starting: Intent { act=x }\nError: Activity not started, unable to resolve Intent\n__scrcpyviewer_exit=0\n" }
        val e = assertFailsWith<IOException> { DeviceQuickActions(adb, "SER").openDeveloperOptions() }
        assertEquals("Error: Activity not started, unable to resolve Intent", e.message)
    }

    @Test
    fun `reboot runs adb reboot`() = runBlocking {
        val adb = ScriptedAdb { "" }
        DeviceQuickActions(adb, "SER").reboot()
        assertEquals(listOf("-s", "SER", "reboot"), adb.execs.single())
        adb.execResult = ProcessResult(1, "", "error: device offline\n")
        assertEquals("error: device offline", assertFailsWith<IOException> { DeviceQuickActions(adb, "SER").reboot() }.message)
    }

    @Test
    fun `lists packages sorted without the prefix and third-party only by default`() = runBlocking {
        val adb = ScriptedAdb { "package:com.b.app\r\npackage:com.a.app\n\npackage:com.b.app\n" }
        assertEquals(listOf("com.a.app", "com.b.app"), DeviceQuickActions(adb, "SER").packages(includeSystem = false))
        assertEquals("pm list packages -3", adb.commands.single())
        DeviceQuickActions(adb, "SER").packages(includeSystem = true)
        assertEquals("pm list packages", adb.commands.last())
    }

    /** A [DeviceQuickActions] whose set waits for [setResult]. */
    private class FakeDevice : DeviceQuickActions(ScriptedAdb { "" }, "SER") {
        var states = mapOf<QuickToggle, Boolean?>(QuickToggle.WiFi to true, QuickToggle.ShowTaps to false)
        var setResult = CompletableDeferred<Unit>()
        var reads = 0

        override suspend fun read(): Map<QuickToggle, Boolean?> {
            reads++
            return states
        }

        override suspend fun set(toggle: QuickToggle, on: Boolean) = setResult.await()
    }

    @Test
    fun `opening reads states and toggles flip back on failure`() = runBlocking {
        val device = FakeDevice()
        val messages = mutableListOf<String>()
        val actions = QuickActions(device, eager) { messages += it }

        actions.toggleSheet()
        assertTrue(actions.open)
        assertEquals(1, device.reads)
        assertEquals(true, actions.uiState.toggles[QuickToggle.WiFi])

        actions.onEvent(QuickActionEvent.Toggle(QuickToggle.WiFi, false))
        assertEquals(false, actions.uiState.toggles[QuickToggle.WiFi]) // optimistic
        assertEquals(setOf(QuickToggle.WiFi), actions.uiState.pending)
        device.setResult.completeExceptionally(IOException("denied"))
        assertEquals(true, actions.uiState.toggles[QuickToggle.WiFi])
        assertTrue(actions.uiState.pending.isEmpty())
        assertEquals(listOf("Could not turn Wi-Fi off: denied"), messages)

        device.setResult = CompletableDeferred(Unit)
        actions.onEvent(QuickActionEvent.Toggle(QuickToggle.ShowTaps, true))
        assertEquals(true, actions.uiState.toggles[QuickToggle.ShowTaps])

        actions.toggleSheet()
        assertFalse(actions.open)
    }

    @Test
    fun `disabled without adb or while disconnected`() = runBlocking {
        val none = QuickActions(null, eager) {}
        none.toggleSheet()
        assertFalse(none.open)
        assertFalse(none.uiState.enabled)

        val actions = QuickActions(FakeDevice(), eager) {}
        actions.connected = false
        actions.toggleSheet()
        assertFalse(actions.open)
    }

    /** Records buffered runs; answers with [result]. */
    private class FollowerRunner : ShellRunner(ScriptedAdb { "" }, "F") {
        val runs = mutableListOf<String>()
        var result: () -> ShellResult = { ShellResult("", 0, 1) }

        override suspend fun run(cmd: String, timeoutMs: Long): ShellResult {
            runs += cmd
            return result()
        }
    }

    @Test
    fun `a built-in toggle sets followers to the leader's state instead of flipping them`() = runBlocking {
        val device = FakeDevice().apply { setResult = CompletableDeferred(Unit) }
        val pixel = FollowerRunner()
        val offline = FollowerRunner().apply { result = { throw IOException("device offline") } }
        val messages = mutableListOf<String>()
        val actions = QuickActions(
            device, eager, title = { "Galaxy A15" },
            followers = { listOf(FollowRunner("Pixel 9", pixel, "P9"), FollowRunner("Pixel Tablet", offline, "TAB")) },
        ) { messages += it }

        // The follower may already read "on": it is set, not flipped, so it still gets the "on" command.
        actions.onEvent(QuickActionEvent.Toggle(QuickToggle.WiFi, true))

        assertEquals(listOf("svc wifi enable"), pixel.runs)
        assertEquals(listOf("svc wifi enable"), offline.runs)
        assertEquals(listOf("Ran on 2 of 3 devices · Pixel Tablet failed: device offline"), messages)
    }

    @Test
    fun `a built-in toggle that fails on the leader is not sent to followers`() = runBlocking {
        val device = FakeDevice()
        val pixel = FollowerRunner()
        val messages = mutableListOf<String>()
        val actions = QuickActions(device, eager, followers = { listOf(FollowRunner("Pixel 9", pixel, "P9")) }) { messages += it }

        actions.onEvent(QuickActionEvent.Toggle(QuickToggle.WiFi, false))
        device.setResult.completeExceptionally(IOException("denied"))

        assertTrue(pixel.runs.isEmpty())
        assertEquals(listOf("Could not turn Wi-Fi off: denied"), messages)
    }

    @Test
    fun `a built-in toggle without followers shows nothing on success`() = runBlocking {
        val device = FakeDevice().apply { setResult = CompletableDeferred(Unit) }
        val messages = mutableListOf<String>()
        val actions = QuickActions(device, eager) { messages += it }

        actions.onEvent(QuickActionEvent.Toggle(QuickToggle.WiFi, true))

        assertTrue(messages.isEmpty())
    }
}
