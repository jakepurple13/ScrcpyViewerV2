package com.programmersbox.scrcpyviewer.ui.mirror

import com.programmersbox.scrcpyviewer.actions.ActionStore
import com.programmersbox.scrcpyviewer.actions.MatchMode
import com.programmersbox.scrcpyviewer.actions.OnWhen
import com.programmersbox.scrcpyviewer.actions.OutputTarget
import com.programmersbox.scrcpyviewer.actions.RunOnceAction
import com.programmersbox.scrcpyviewer.actions.ToggleAction
import com.programmersbox.scrcpyviewer.actions.Variable
import com.programmersbox.scrcpyviewer.actions.VariableKind
import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.adb.ShellEvent
import com.programmersbox.scrcpyviewer.adb.ShellResult
import com.programmersbox.scrcpyviewer.adb.ShellRunner
import com.programmersbox.scrcpyviewer.adb.ShellTimeoutException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.io.IOException
import kotlin.test.assertFailsWith
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Answers shell commands from [answer]; with [endless] a streamed command prints forever, like `logcat`. */
private class ShellAdb(private val endless: Boolean = false, private val answer: (String) -> String) :
    AdbClient(adbPath = { error("no real adb in tests") }) {
    val commands = mutableListOf<String>()
    val streams = mutableListOf<String>()

    @Volatile var streamCancelled = false

    /** When set, a streamed command answers only once it completes. */
    var streamGate: CompletableDeferred<Unit>? = null

    override suspend fun shell(serial: String, cmd: String): String {
        commands += cmd
        return answer(cmd)
    }

    override fun shellStream(serial: String, cmd: String): Flow<String> = flow {
        streams += cmd
        if (!endless) {
            streamGate?.await()
            emit(answer(cmd))
            return@flow
        }
        try {
            while (true) {
                emit("I ActivityManager: line\n")
                delay(1)
            }
        } finally {
            streamCancelled = true
        }
    }
}

/** Buffered runs answer from [result] (or wait for [gate]); streamed runs never end, like `logcat`. */
private class ScriptedRunner : ShellRunner(ShellAdb { "" }, "SER") {
    val runs = mutableListOf<String>()
    val streams = mutableListOf<String>()
    var result: () -> ShellResult = { ShellResult("", 0, 1) }
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun run(cmd: String, timeoutMs: Long): ShellResult {
        runs += cmd
        gate?.await()
        return result()
    }

    override fun stream(cmd: String): Flow<ShellEvent> {
        streams += cmd
        return flow { awaitCancellation() }
    }
}

class QuickActionsCustomTest {
    // Launches run at once, up to their first suspension; a completed deferred resumes them inline.
    private val eager = CoroutineScope(Dispatchers.Unconfined)
    private val dir = Files.createTempDirectory("quick-actions-custom")
    private val store = ActionStore(dir.resolve("actions.json"))
    private val messages = mutableListOf<String>()
    private val runner = ScriptedRunner()
    private val console = ShellConsole(runner, ShellHistory(), eager, { messages += it })

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    private val stayAwake = ToggleAction(
        id = "stay", name = "Stay awake", on = "settings put global stay_on_while_plugged_in 7",
        off = "settings put global stay_on_while_plugged_in 0", read = "settings get global stay_on_while_plugged_in",
        onWhen = OnWhen(MatchMode.Equals, "7"),
    )
    private val noRead = ToggleAction(id = "plain", name = "Plain", on = "echo {on}", off = "echo off")

    private fun quickActions(
        adb: ShellAdb = ShellAdb { "" },
        memory: QuickActionsMemory = QuickActionsMemory(),
        managed: MutableList<String?> = mutableListOf(),
        readTimeoutMs: Long = 30_000,
    ): QuickActions {
        val qa = QuickActions(
            DeviceQuickActions(adb, "SER", readTimeoutMs), eager, serial = "SER", store = store, runner = runner, console = console,
            memory = memory, onManage = { managed += it },
        ) { messages += it }
        eager.launch { qa.watchActions() }
        return qa
    }

    @Test
    fun `batched read includes custom read commands`() {
        store.upsert(stayAwake)
        store.upsert(noRead)
        val builtIns = QuickToggle.entries.joinToString("\n__scrcpyviewer_sep__\n") { "0" }
        val adb = ShellAdb { "$builtIns\n__scrcpyviewer_sep__\n7\r\n" }
        val qa = quickActions(adb)

        qa.toggleSheet()

        assertTrue(adb.commands.isEmpty())
        val cmd = adb.streams.single()
        assertTrue(cmd.startsWith(QuickToggle.DarkMode.readCommand))
        assertTrue(cmd.endsWith("settings get global stay_on_while_plugged_in"))
        assertEquals(true, qa.uiState.customState["stay"])
        assertNull(qa.uiState.customState["plain"])
        assertEquals(false, qa.uiState.toggles[QuickToggle.WiFi])
        assertEquals(listOf(stayAwake, noRead), qa.uiState.custom)
    }

    @Test
    fun `a read that never ends gives up and stops the device command`() = runBlocking {
        val adb = ShellAdb(endless = true) { "" }
        val e = assertFailsWith<IOException> { DeviceQuickActions(adb, "SER", readTimeoutMs = 50).read(listOf(stayAwake)) }
        assertEquals("Reading the toggles timed out", e.message)
        assertTrue(adb.streamCancelled)
    }

    @Test
    fun `toggles stay usable while a read never ends, and its states stay unknown`() = runBlocking {
        store.upsert(stayAwake)
        val adb = ShellAdb(endless = true) { "__scrcpyviewer_exit=0\n" }
        val qa = quickActions(adb, readTimeoutMs = 100)

        qa.toggleSheet()
        qa.onEvent(QuickActionEvent.CustomToggle("stay", true))
        qa.onEvent(QuickActionEvent.Toggle(QuickToggle.WiFi, true))
        assertTrue(qa.uiState.customPending.isEmpty())
        assertTrue(qa.uiState.pending.isEmpty())

        withTimeout(5_000) { while (!adb.streamCancelled) delay(10) }
        assertEquals(true, qa.uiState.customState["stay"])
        assertEquals(true, qa.uiState.toggles[QuickToggle.WiFi])
        assertNull(qa.uiState.toggles[QuickToggle.DarkMode])
        assertTrue(messages.isEmpty())
    }

    @Test
    fun `refresh keeps a pending custom toggle's optimistic state`() {
        store.upsert(stayAwake)
        val builtIns = QuickToggle.entries.joinToString("\n__scrcpyviewer_sep__\n") { "0" }
        val qa = quickActions(ShellAdb { "$builtIns\n__scrcpyviewer_sep__\n0\n" })
        runner.gate = CompletableDeferred()

        qa.onEvent(QuickActionEvent.CustomToggle("stay", true))
        qa.toggleSheet()
        assertEquals(true, qa.uiState.customState["stay"])
        runner.gate!!.complete(Unit)
        assertEquals(true, qa.uiState.customState["stay"])
        assertTrue(qa.uiState.customPending.isEmpty())
    }

    @Test
    fun `a toggle deleted during the read does not get its state back`() {
        store.upsert(stayAwake)
        val builtIns = QuickToggle.entries.joinToString("\n__scrcpyviewer_sep__\n") { "0" }
        val adb = ShellAdb { "$builtIns\n__scrcpyviewer_sep__\n7\n" }
        adb.streamGate = CompletableDeferred()
        val qa = quickActions(adb)

        qa.toggleSheet()
        store.delete("stay")
        adb.streamGate!!.complete(Unit)

        assertEquals(false, qa.uiState.toggles[QuickToggle.WiFi]) // the read did answer
        assertTrue(qa.uiState.custom.isEmpty())
        assertFalse("stay" in qa.uiState.customState)
    }

    @Test
    fun `flip failure reverts and notifies`() {
        store.upsert(stayAwake)
        val qa = quickActions()
        runner.gate = CompletableDeferred()
        runner.result = { ShellResult("Error: denied\n", 255, 3) }

        qa.onEvent(QuickActionEvent.CustomToggle("stay", true))
        assertEquals(true, qa.uiState.customState["stay"]) // optimistic
        assertEquals(setOf("stay"), qa.uiState.customPending)
        runner.gate!!.complete(Unit)

        assertNull(qa.uiState.customState["stay"])
        assertTrue(qa.uiState.customPending.isEmpty())
        assertEquals(listOf("Could not turn Stay awake on: Error: denied"), messages)
        assertEquals(listOf("settings put global stay_on_while_plugged_in 7"), runner.runs)
    }

    @Test
    fun `toggle without read remembers its state for the app lifetime and keeps braces literal`() {
        store.upsert(noRead)
        val memory = QuickActionsMemory()
        quickActions(memory = memory).onEvent(QuickActionEvent.CustomToggle("plain", true))
        assertEquals(listOf("echo {on}"), runner.runs)

        val again = quickActions(memory = memory)
        assertEquals(true, again.uiState.customState["plain"])
    }

    @Test
    fun `toast shows Done for empty output and the first non-blank line otherwise`() {
        store.upsert(RunOnceAction(id = "r", name = "Run", command = "true"))
        val qa = quickActions()

        qa.onEvent(QuickActionEvent.RunCustom("r"))
        runner.result = { ShellResult("\n  \nhello\nworld", 0, 2) }
        qa.onEvent(QuickActionEvent.RunCustom("r"))

        assertEquals(listOf("Done", "hello"), messages)
    }

    @Test
    fun `none output stays quiet unless it fails`() {
        store.upsert(RunOnceAction(id = "n", name = "Quiet", command = "x", output = OutputTarget.None))
        val qa = quickActions()

        qa.onEvent(QuickActionEvent.RunCustom("n"))
        assertTrue(messages.isEmpty())
        runner.result = { ShellResult("sh: x: not found", 127, 2) }
        qa.onEvent(QuickActionEvent.RunCustom("n"))
        assertEquals(listOf("Could not run Quiet: sh: x: not found"), messages)
    }

    @Test
    fun `timeout notifies the exact timeout text`() {
        store.upsert(RunOnceAction(id = "r", name = "Slow", command = "sleep 60"))
        runner.result = { throw ShellTimeoutException() }
        val qa = quickActions()

        qa.onEvent(QuickActionEvent.RunCustom("r"))

        assertEquals(listOf("Timed out after 30 s; set Output to Shell tab for long commands"), messages)
        assertTrue(qa.uiState.customPending.isEmpty())
    }

    @Test
    fun `shell output switches to the Shell tab and names the action in the console`() {
        store.upsert(RunOnceAction(id = "s", name = "Logcat", command = "logcat {tag}", output = OutputTarget.Shell,
            variables = mapOf("tag" to Variable("Tag", default = "ActivityManager"))))
        val qa = quickActions()
        qa.toggleSheet()
        assertEquals(SheetTab.Actions, qa.uiState.tab)

        qa.onEvent(QuickActionEvent.RunCustom("s"))
        qa.onEvent(QuickActionEvent.PromptValues(qa.uiState.prompt!!.values))

        assertEquals(SheetTab.Shell, qa.uiState.tab)
        assertEquals(ConsoleLine.Header("▶ Logcat", typed = false), console.lines.first())
        assertEquals(listOf("logcat ActivityManager"), runner.streams)
        assertTrue(qa.uiState.consoleRunning)
    }

    @Test
    fun `chip forces a toast action into the console`() {
        store.upsert(RunOnceAction(id = "r", name = "Props", command = "getprop"))
        val qa = quickActions()

        qa.onEvent(QuickActionEvent.RunCustom("r", fromChip = true))

        assertEquals(SheetTab.Shell, qa.uiState.tab)
        assertEquals(listOf("getprop"), runner.streams)
        assertTrue(runner.runs.isEmpty())
    }

    @Test
    fun `run while the console is busy is refused`() {
        store.upsert(RunOnceAction(id = "s", name = "Logcat", command = "logcat", output = OutputTarget.Shell))
        val qa = quickActions()
        console.input = "top"
        console.submit()

        qa.onEvent(QuickActionEvent.RunCustom("s"))

        assertEquals(listOf("Stop the running command first"), messages)
        assertEquals(listOf("top"), runner.streams)
        assertNull(qa.uiState.prompt)
    }

    @Test
    fun `console busy by the time the prompt is confirmed is refused without switching tabs`() {
        store.upsert(RunOnceAction(id = "s", name = "Logcat", command = "logcat", output = OutputTarget.Shell, confirm = true))
        val qa = quickActions()
        qa.toggleSheet()

        qa.onEvent(QuickActionEvent.RunCustom("s"))
        console.input = "top"
        console.submit()
        qa.onEvent(QuickActionEvent.PromptConfirm)

        assertEquals(listOf("Stop the running command first"), messages)
        assertEquals(SheetTab.Actions, qa.uiState.tab)
        assertEquals(listOf("top"), runner.streams)
        assertNull(qa.uiState.prompt)
    }

    @Test
    fun `deleting or changing the action while its prompt is open clears the prompt`() {
        val action = RunOnceAction(id = "c", name = "Clear", command = "pm clear {pkg}", confirm = true)
        store.upsert(action)
        val qa = quickActions()

        qa.onEvent(QuickActionEvent.RunCustom("c"))
        assertNotNull(qa.uiState.prompt)
        store.delete("c")
        assertNull(qa.uiState.prompt)
        qa.onEvent(QuickActionEvent.PromptConfirm)
        assertTrue(runner.runs.isEmpty())

        store.upsert(action)
        qa.onEvent(QuickActionEvent.RunCustom("c"))
        store.upsert(action.copy(command = "pm clear -f {pkg}"))
        assertNull(qa.uiState.prompt)
    }

    @Test
    fun `prompt then confirm then run, and last values prefill the next prompt`() {
        store.upsert(RunOnceAction(id = "c", name = "Clear", command = "pm clear {pkg}", confirm = true,
            variables = mapOf("pkg" to Variable("Package", default = "com.example"))))
        val memory = QuickActionsMemory()
        val qa = quickActions(memory = memory)

        qa.onEvent(QuickActionEvent.RunCustom("c"))
        assertEquals(PendingRun(store.actions.value.single() as RunOnceAction, needsValues = true, needsConfirm = true,
            values = mapOf("pkg" to "com.example")), qa.uiState.prompt)
        qa.onEvent(QuickActionEvent.PromptValues(mapOf("pkg" to "com.other")))
        assertEquals(false, qa.uiState.prompt!!.needsValues)
        assertTrue(runner.runs.isEmpty())
        qa.onEvent(QuickActionEvent.PromptConfirm)
        assertNull(qa.uiState.prompt)
        assertEquals(listOf("pm clear com.other"), runner.runs)

        val other = quickActions(memory = memory)
        other.onEvent(QuickActionEvent.RunCustom("c"))
        assertEquals(mapOf("pkg" to "com.other"), other.uiState.prompt!!.values)
        other.onEvent(QuickActionEvent.PromptCancel)
        assertNull(other.uiState.prompt)
        assertEquals(1, runner.runs.size)
    }

    @Test
    fun `close stops the console and the tab is remembered across windows`() {
        store.upsert(RunOnceAction(id = "s", name = "Logcat", command = "logcat", output = OutputTarget.Shell))
        val memory = QuickActionsMemory()
        val qa = quickActions(memory = memory)
        qa.toggleSheet()
        qa.onEvent(QuickActionEvent.RunCustom("s"))
        assertTrue(console.running)

        qa.close()

        assertFalse(console.running)
        assertEquals(ConsoleLine.Exit(null, 0), console.lines.last())
        val next = quickActions(memory = memory)
        next.toggleSheet()
        assertEquals(SheetTab.Shell, next.uiState.tab)
        next.onEvent(QuickActionEvent.SelectTab(SheetTab.Actions))
        assertEquals(SheetTab.Actions, memory.tab)
    }

    @Test
    fun `manage, duplicate and delete`() {
        store.upsert(noRead)
        val managed = mutableListOf<String?>()
        val qa = quickActions(managed = managed)

        qa.onEvent(QuickActionEvent.Manage(null))
        qa.onEvent(QuickActionEvent.Manage("plain"))
        qa.onEvent(QuickActionEvent.Duplicate("plain"))
        assertEquals(listOf("Plain", "Plain (2)"), qa.uiState.custom.map { it.name })
        qa.onEvent(QuickActionEvent.Delete("plain"))
        assertEquals(listOf("Plain (2)"), qa.uiState.custom.map { it.name })
        assertEquals(listOf(null, "plain"), managed)

        qa.onEvent(QuickActionEvent.Duplicate("gone"))
        qa.onEvent(QuickActionEvent.Delete("gone"))
        assertTrue(messages.single().startsWith("Could not duplicate the action: "))
    }

    @Test
    fun `loads the package list for the prompt on request`() {
        val adb = ShellAdb { "package:com.z\npackage:com.a\n" }
        val qa = quickActions(adb)
        qa.toggleSheet()
        assertNull(qa.uiState.packages)

        qa.onEvent(QuickActionEvent.LoadPackages(includeSystem = true))

        assertEquals(listOf("com.a", "com.z"), qa.uiState.packages)
        assertEquals("pm list packages", adb.commands.last())
    }

    @Test
    fun `each variable prompt starts without the previous package list`() {
        store.upsert(RunOnceAction(id = "c", name = "Clear", command = "pm clear {pkg}",
            variables = mapOf("pkg" to Variable("App", kind = VariableKind.App))))
        val qa = quickActions(ShellAdb { "package:com.a\n" })
        qa.toggleSheet()
        qa.onEvent(QuickActionEvent.RunCustom("c"))
        qa.onEvent(QuickActionEvent.LoadPackages(includeSystem = true))
        assertEquals(listOf("com.a"), qa.uiState.packages)
        qa.onEvent(QuickActionEvent.PromptCancel)

        qa.onEvent(QuickActionEvent.RunCustom("c"))

        assertNull(qa.uiState.packages)
    }
}
