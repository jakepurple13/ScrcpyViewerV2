package com.programmersbox.scrcpyviewer.ui.actions

import com.programmersbox.scrcpyviewer.actions.ActionCodec
import com.programmersbox.scrcpyviewer.actions.ActionStore
import com.programmersbox.scrcpyviewer.actions.MatchMode
import com.programmersbox.scrcpyviewer.actions.OnWhen
import com.programmersbox.scrcpyviewer.actions.OutputTarget
import com.programmersbox.scrcpyviewer.actions.RunOnceAction
import com.programmersbox.scrcpyviewer.actions.ToggleAction
import com.programmersbox.scrcpyviewer.actions.Variable
import com.programmersbox.scrcpyviewer.actions.VariableKind
import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.adb.DeviceTracker
import com.programmersbox.scrcpyviewer.adb.ShellRunner
import com.programmersbox.scrcpyviewer.presets.ConflictChoice
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Lists [serials] as connected; shell commands answer from [answer], or print forever with [endless], like `logcat`. */
private class FakeAdb(private val serials: List<String>) : AdbClient(adbPath = { error("no real adb in tests") }) {
    var answer: (String) -> String = { "" }
    var endless = false
    /** Prints this many numbered lines at once, then ends, like a large `dumpsys`. */
    var flood = 0
    val commands: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override suspend fun startServer() {}

    override fun trackDevices(): Flow<String> = flow {
        emit(serials.joinToString("\n") { "$it\tdevice" })
        awaitCancellation()
    }

    // The tracker's device info query and the installed-app list use the one-shot shell.
    override suspend fun shell(serial: String, cmd: String): String = when (cmd) {
        "pm list packages -3" -> "package:com.b\npackage:com.a\n"
        "pm list packages" -> "package:com.b\npackage:android\npackage:com.a\n"
        else -> "Pixel 8\n15\n35\n$serial\n"
    }

    override fun shellStream(serial: String, cmd: String): Flow<String> = flow {
        val command = cmd.substringBeforeLast("\necho ")
        commands += "$serial: $command"
        if (flood > 0) {
            emit((1..flood).joinToString("") { "line $it\n" })
            emit("${ShellRunner.EXIT_MARKER}0\n")
            return@flow
        }
        if (endless) {
            while (true) {
                emit("I ActivityManager: line\n")
                delay(5)
            }
        }
        emit(answer(command) + "${ShellRunner.EXIT_MARKER}0\n")
    }
}

/** Runs dispatched coroutines only when [drain] is called, so a test can see what the VM does before state catches up. */
private class ManualDispatcher : CoroutineDispatcher() {
    private val queue = LinkedBlockingQueue<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        queue.add(block)
    }

    /** Runs queued work until [done] or the timeout. */
    fun drain(done: () -> Boolean = { false }) {
        val end = System.currentTimeMillis() + 5_000
        while (!done() && System.currentTimeMillis() < end) queue.poll(10, TimeUnit.MILLISECONDS)?.run()
    }
}

class ActionsViewModelTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val store = ActionStore(Files.createTempDirectory("actions-vm").resolve("actions.json"))

    private val stayAwake = ToggleAction(
        "awake", "Stay awake", on = "svc power stayon true", off = "svc power stayon false",
        read = "settings get global stay_on_while_plugged_in", onWhen = OnWhen(MatchMode.Equals, "7"),
    )
    private val logcat = RunOnceAction("logcat", "Logcat", command = "logcat", output = OutputTarget.Shell)

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun vm(serials: List<String> = listOf("SER1", "SER2"), adb: FakeAdb = FakeAdb(serials)): ActionsViewModel {
        val tracker = DeviceTracker(adb, scope)
        runBlocking { withTimeout(5_000) { tracker.devices.first { it.size == serials.size } } }
        return ActionsViewModel(store, tracker, adb, scope, testTimeoutMs = 300)
    }

    private fun ActionsViewModel.awaitTest() = runBlocking {
        withTimeout(5_000) { state.first { it.test != null && !it.test!!.running } }.test!!
    }

    @Test
    fun `new draft is a toggle with the toggle icon`() {
        val vm = vm()
        vm.new()
        val draft = assertNotNull(vm.state.value.draft)
        assertEquals(ActionType.Toggle, draft.type)
        assertEquals("toggle_on", draft.icon)
        assertNull(draft.id)
        assertFalse(vm.state.value.dirty)
    }

    @Test
    fun `switching type keeps the name and swaps a default icon`() {
        val vm = vm()
        vm.new()
        vm.edit { it.copy(name = "Clear data") }
        vm.setType(ActionType.RunOnce)
        assertEquals("Clear data", vm.state.value.draft!!.name)
        assertEquals("play_arrow", vm.state.value.draft!!.icon)
        vm.edit { it.copy(icon = "bolt") }
        vm.setType(ActionType.Toggle)
        assertEquals("bolt", vm.state.value.draft!!.icon, "a chosen icon stays")
    }

    @Test
    fun `editing the command derives variables and keeps their settings`() {
        val vm = vm()
        vm.new()
        vm.setType(ActionType.RunOnce)
        vm.edit { it.copy(command = "pm clear {package}") }
        assertEquals(mapOf("package" to Variable("package", VariableKind.Text)), vm.state.value.draft!!.variables)

        vm.edit { it.copy(variables = mapOf("package" to Variable("App to clear", VariableKind.App))) }
        vm.edit { it.copy(command = "pm clear --user 0 {package} {{literal}}") }
        assertEquals(mapOf("package" to Variable("App to clear", VariableKind.App)), vm.state.value.draft!!.variables)

        // A variable that briefly disappears while typing keeps its settings when it comes back.
        vm.edit { it.copy(command = "pm clear {packag") }
        assertEquals(emptyMap(), vm.state.value.draft!!.variables)
        vm.edit { it.copy(command = "pm clear {package}") }
        assertEquals("App to clear", vm.state.value.draft!!.variables["package"]?.label)
    }

    @Test
    fun `save validates required and unique fields`() {
        store.upsert(stayAwake)
        val vm = vm()
        vm.new()
        assertFalse(vm.save())
        val errors = vm.state.value.errors
        assertNotNull(errors.name)
        assertNotNull(errors.on)
        assertNotNull(errors.off)
        assertNull(errors.command)

        vm.edit { it.copy(name = "stay AWAKE", on = "a", off = "b") }
        assertFalse(vm.save())
        assertNotNull(vm.state.value.errors.name, "names are unique ignoring case")
        assertNull(vm.state.value.errors.on)

        vm.setType(ActionType.RunOnce)
        vm.edit { it.copy(name = "Other") }
        assertFalse(vm.save())
        assertNotNull(vm.state.value.errors.command)
        assertNull(vm.state.value.errors.on)
        assertEquals(listOf(stayAwake), store.actions.value)
    }

    @Test
    fun `save upserts and clears dirty`() {
        val vm = vm()
        vm.new()
        vm.setType(ActionType.RunOnce)
        vm.edit { it.copy(name = "  Clear data ", command = "pm clear {package}", confirm = true) }
        assertTrue(vm.state.value.dirty)
        assertTrue(vm.save())
        val saved = store.actions.value.single() as RunOnceAction
        assertEquals("Clear data", saved.name)
        assertEquals("play_arrow", saved.icon)
        assertEquals(mapOf("package" to Variable("package")), saved.variables)
        assertTrue(saved.confirm)
        assertEquals(saved.id, vm.state.value.selectedId)
        assertEquals(saved.id, vm.state.value.draft!!.id)
        assertFalse(vm.state.value.dirty)

        // Saving again updates the same action.
        vm.edit { it.copy(name = "Clear app") }
        assertTrue(vm.save())
        assertEquals(listOf("Clear app"), store.actions.value.map { it.name })
    }

    @Test
    fun `a toggle saves its read check only with a read command`() {
        val vm = vm()
        vm.new()
        vm.edit { it.copy(name = "Wifi", on = "svc wifi enable", off = "svc wifi disable", matchValue = "1") }
        assertTrue(vm.save())
        val saved = store.actions.value.single() as ToggleAction
        assertNull(saved.read)
        assertNull(saved.onWhen)
    }

    @Test
    fun `select while dirty is refused and discardAndSelect switches`() {
        store.upsert(stayAwake)
        store.upsert(logcat)
        val vm = vm()
        assertTrue(vm.select("awake"))
        assertEquals("Stay awake", vm.state.value.draft!!.name)
        vm.edit { it.copy(name = "Changed") }
        assertFalse(vm.select("logcat"))
        assertEquals("awake", vm.state.value.selectedId)
        vm.discardAndSelect("logcat")
        assertEquals("logcat", vm.state.value.selectedId)
        assertEquals(ActionType.RunOnce, vm.state.value.draft!!.type)
        assertFalse(vm.state.value.dirty)
        assertEquals(listOf("Stay awake", "Logcat"), store.actions.value.map { it.name })
    }

    @Test
    fun `handle opens an action, a new one, or asks to discard first`() {
        store.upsert(stayAwake)
        store.upsert(logcat)
        val vm = vm()
        vm.handle(ActionsRequest("logcat", new = false))
        assertEquals("logcat", vm.state.value.selectedId)
        vm.handle(ActionsRequest(null, new = true))
        assertNull(vm.state.value.draft!!.id)

        vm.edit { it.copy(name = "Unsaved") }
        vm.handle(ActionsRequest("awake", new = false))
        assertEquals("Unsaved", vm.state.value.draft!!.name)
        assertEquals(ActionsRequest("awake", new = false), vm.pendingDiscard.value)
        vm.discardAndSelect("awake")
        assertNull(vm.pendingDiscard.value)
        assertEquals("awake", vm.state.value.selectedId)

        vm.handle(ActionsRequest(null, new = false))
        assertEquals("awake", vm.state.value.selectedId, "a plain request only shows the screen")
    }

    @Test
    fun `test device defaults to the first connected device`() {
        assertEquals(listOf("SER1", "SER2"), vm().state.value.devices)
        val vm = vm()
        assertEquals("SER1", vm.state.value.testDevice)
        vm.setTestDevice("SER2")
        assertEquals("SER2", vm.state.value.testDevice)
        assertNull(vm(serials = emptyList()).state.value.testDevice)
    }

    @Test
    fun `test read shows the raw output and whether it counts as on`() {
        store.upsert(stayAwake)
        val adb = FakeAdb(listOf("SER1")).apply { answer = { "7\r\n" } }
        val vm = vm(listOf("SER1"), adb)
        vm.select("awake")
        vm.testRead()
        val result = vm.awaitTest()
        assertEquals("Counts as on", result.note)
        assertEquals(true, result.readOn)
        assertEquals("7", result.output)
        assertEquals(0, result.exitCode)
        assertEquals(listOf("SER1: settings get global stay_on_while_plugged_in"), adb.commands)

        adb.answer = { "0\n" }
        vm.testRead()
        val off = vm.awaitTest()
        assertEquals("Counts as off", off.note)
        assertEquals(false, off.readOn)
    }

    @Test
    fun `test read without a read command says so`() {
        val vm = vm()
        vm.new()
        vm.testRead()
        assertEquals("No read command", vm.awaitTest().note)
    }

    @Test
    fun `test run substitutes variables on the chosen device`() {
        val adb = FakeAdb(listOf("SER1", "SER2")).apply { answer = { "Success\n" } }
        val vm = vm(adb = adb)
        vm.new()
        vm.setType(ActionType.RunOnce)
        vm.edit { it.copy(command = "pm clear {package}") }
        vm.setTestDevice("SER2")
        vm.testRun(mapOf("package" to "com.example"))
        val result = vm.awaitTest()
        assertEquals("Success", result.output)
        assertEquals(0, result.exitCode)
        assertNull(result.note)
        assertEquals(listOf("SER2: pm clear com.example"), adb.commands)
    }

    @Test
    fun `test run of a Shell output action stops after the timeout`() {
        store.upsert(logcat)
        val adb = FakeAdb(listOf("SER1")).apply { endless = true }
        val vm = vm(listOf("SER1"), adb)
        vm.select("logcat")
        vm.testRun(emptyMap())
        val result = vm.awaitTest()
        assertEquals("Stopped after 30 s", result.note)
        assertTrue(result.output.startsWith("I ActivityManager: line\n"), result.output)
        assertNull(result.exitCode)
    }

    @Test
    fun `test run of a Toast action that never ends times out`() {
        val adb = FakeAdb(listOf("SER1")).apply { endless = true }
        val vm = vm(listOf("SER1"), adb)
        vm.new()
        vm.setType(ActionType.RunOnce)
        vm.edit { it.copy(command = "sleep 100") }
        vm.testRun(emptyMap())
        assertEquals("Timed out after 30 s; set Output to Shell tab for long commands", vm.awaitTest().note)
    }

    @Test
    fun `delete clears the selection of the deleted action`() {
        store.upsert(stayAwake)
        val vm = vm()
        vm.select("awake")
        vm.delete("awake")
        assertEquals(emptyList(), store.actions.value)
        assertNull(vm.state.value.selectedId)
        assertNull(vm.state.value.draft)
    }

    @Test
    fun `duplicate and move go through the store`() {
        store.upsert(stayAwake)
        store.upsert(logcat)
        val vm = vm()
        vm.duplicate("awake")
        assertEquals(listOf("Stay awake", "Logcat", "Stay awake (2)"), store.actions.value.map { it.name })
        assertEquals("Stay awake (2)", vm.state.value.draft!!.name)
        vm.move("logcat", 0)
        assertEquals(listOf("Logcat", "Stay awake", "Stay awake (2)"), vm.state.value.actions.map { it.name })
    }

    @Test
    fun `export writes only the given actions in list order`() {
        store.upsert(stayAwake)
        store.upsert(logcat)
        val vm = vm()
        assertEquals(listOf(stayAwake, logcat), ActionCodec.decode(vm.exportJson(listOf("logcat", "awake"))))
        assertEquals(listOf(logcat), ActionCodec.decode(vm.exportJson(setOf("logcat", "gone"))))
        assertEquals(emptyList(), ActionCodec.decode(vm.exportJson(emptyList())))
    }

    @Test
    fun `export selection starts with every action and can be changed`() {
        store.upsert(stayAwake)
        store.upsert(logcat)
        val vm = vm()
        assertNull(vm.state.value.exportSelection)

        vm.startExport()
        assertEquals(setOf("awake", "logcat"), vm.state.value.exportSelection)
        vm.toggleExport("awake")
        assertEquals(setOf("logcat"), vm.state.value.exportSelection)
        vm.toggleExport("awake")
        assertEquals(setOf("awake", "logcat"), vm.state.value.exportSelection)
        vm.selectNoExport()
        assertEquals(emptySet(), vm.state.value.exportSelection)
        vm.selectAllExport()
        assertEquals(setOf("awake", "logcat"), vm.state.value.exportSelection)
        vm.cancelExport()
        assertNull(vm.state.value.exportSelection)
    }

    @Test
    fun `a deleted action leaves the export selection`() {
        store.upsert(stayAwake)
        store.upsert(logcat)
        val vm = vm()
        vm.startExport()
        vm.delete("awake")
        assertEquals(setOf("logcat"), vm.state.value.exportSelection)
    }

    @Test
    fun `import of a newer version sets an error and changes nothing`() {
        val vm = vm()
        vm.beginImport("""{"format": "scrcpy-viewer.actions", "version": 2, "actions": []}""")
        val import = assertNotNull(vm.importState.value)
        assertNotNull(import.error)
        assertEquals(emptyList(), import.items)
        vm.confirmImport()
        assertNull(vm.importState.value)
        assertEquals(emptyList(), store.actions.value)
    }

    @Test
    fun `confirmImport applies the plan`() {
        store.upsert(stayAwake)
        val vm = vm()
        val incoming = listOf(
            stayAwake.copy(id = "x", on = "svc power stayon usb"),
            logcat.copy(id = "y"),
            RunOnceAction("z", "Reboot", command = "reboot"),
        )
        vm.beginImport(ActionCodec.encode(incoming))
        val import = assertNotNull(vm.importState.value)
        assertNull(import.error)
        assertEquals(3, import.importCount)
        assertEquals(stayAwake, import.items[0].conflictsWith)
        vm.setChoice(0, ConflictChoice.Replace)
        vm.setInclude(2, false)
        assertEquals(2, vm.importState.value!!.importCount)
        vm.confirmImport()
        assertNull(vm.importState.value)
        val actions = store.actions.value
        assertEquals(listOf("Stay awake", "Logcat"), actions.map { it.name })
        assertEquals("awake", actions[0].id, "a replaced action keeps its id")
        assertEquals("svc power stayon usb", (actions[0] as ToggleAction).on)
        assertTrue(actions[1].id != "y", "an added action gets a new id")
    }

    @Test
    fun `test run with no device connected says so and does not spin`() {
        val vm = vm(serials = emptyList())
        vm.new()
        vm.setType(ActionType.RunOnce)
        vm.edit { it.copy(command = "ls") }
        vm.testRun(emptyMap())
        val test = assertNotNull(vm.state.value.test)
        assertFalse(test.running)
        assertEquals("No device connected", test.note)
    }

    @Test
    fun `test read without a read command stops a running test`() {
        val adb = FakeAdb(listOf("SER1")).apply { endless = true }
        val vm = vm(listOf("SER1"), adb)
        vm.new()
        vm.setType(ActionType.RunOnce)
        vm.edit { it.copy(command = "sleep 100") }
        vm.testRun(emptyMap())
        assertTrue(vm.state.value.test!!.running)
        vm.setType(ActionType.Toggle)
        vm.testRead()
        // Past the 300 ms test limit: a run left going would replace the note with its timeout.
        Thread.sleep(600)
        assertEquals("No read command", vm.state.value.test!!.note)
        assertFalse(vm.state.value.test!!.running)
    }

    @Test
    fun `test run uses the device just chosen even before state updates`() {
        val adb = FakeAdb(listOf("SER1", "SER2")).apply { answer = { "ok\n" } }
        val tracker = DeviceTracker(adb, scope)
        runBlocking { withTimeout(5_000) { tracker.devices.first { it.size == 2 } } }
        val dispatcher = ManualDispatcher()
        val vmScope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val vm = ActionsViewModel(store, tracker, adb, vmScope, testTimeoutMs = 300)
            dispatcher.drain { vm.state.value.testDevice == "SER1" }
            vm.new()
            vm.setType(ActionType.RunOnce)
            vm.edit { it.copy(command = "echo hi") }
            vm.setTestDevice("SER2")
            vm.testRun(emptyMap())
            dispatcher.drain { adb.commands.isNotEmpty() }
            assertEquals(listOf("SER2: echo hi"), adb.commands)
        } finally {
            vmScope.cancel()
        }
    }

    @Test
    fun `handle while dirty ignores a request for what is already open`() {
        store.upsert(stayAwake)
        val vm = vm()
        vm.select("awake")
        vm.edit { it.copy(name = "Changed") }
        vm.handle(ActionsRequest("awake", new = false))
        assertNull(vm.pendingDiscard.value, "the open action is already shown")
        assertEquals("Changed", vm.state.value.draft!!.name)

        vm.discardAndSelect(null)
        vm.edit { it.copy(name = "Draft") }
        vm.handle(ActionsRequest(null, new = true))
        assertNull(vm.pendingDiscard.value, "an unsaved new draft is already a new action")
        assertEquals("Draft", vm.state.value.draft!!.name)

        vm.handle(ActionsRequest("awake", new = false))
        assertEquals(ActionsRequest("awake", new = false), vm.pendingDiscard.value, "another action still asks")
    }

    @Test
    fun `test run with a blank command says there is nothing to run`() {
        val adb = FakeAdb(listOf("SER1"))
        val vm = vm(listOf("SER1"), adb)
        vm.new()
        vm.testRun(emptyMap())
        assertEquals("Nothing to run", vm.state.value.test!!.note, "a toggle without a Turn on command")
        vm.setType(ActionType.RunOnce)
        vm.edit { it.copy(command = "  ") }
        vm.testRun(emptyMap())
        assertEquals("Nothing to run", vm.state.value.test!!.note)
        assertFalse(vm.state.value.test!!.running)
        assertEquals(emptyList(), adb.commands)
    }

    @Test
    fun `installed apps load from the test device`() {
        val vm = vm(listOf("SER1"))
        assertNull(vm.packages.value)
        vm.loadPackages(includeSystem = false)
        assertEquals(listOf("com.a", "com.b"), runBlocking { withTimeout(5_000) { vm.packages.first { it != null } } })
        vm.loadPackages(includeSystem = true)
        assertEquals(listOf("android", "com.a", "com.b"), runBlocking { withTimeout(5_000) { vm.packages.first { it?.size == 3 } } })
    }

    @Test
    fun `streamed test output keeps only the last 1000 lines`() {
        val adb = FakeAdb(listOf("SER1")).apply { flood = 5_000 }
        store.upsert(logcat)
        val vm = vm(listOf("SER1"), adb)
        vm.select("logcat")
        vm.testRun(emptyMap())
        val result = vm.awaitTest()
        val lines = result.output.lines().filter { it.isNotEmpty() }
        assertEquals(1000, lines.size)
        assertEquals("line 4001", lines.first())
        assertEquals("line 5000", lines.last())
        assertEquals(1000, result.shownLines, "the editor says Showing last 1000 lines")
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `buffered test output keeps only the last 1000 lines`() {
        val adb = FakeAdb(listOf("SER1")).apply { answer = { (1..3_000).joinToString("") { "line $it\n" } } }
        val vm = vm(listOf("SER1"), adb)
        vm.new()
        vm.setType(ActionType.RunOnce)
        vm.edit { it.copy(command = "dumpsys") }
        vm.testRun(emptyMap())
        val result = vm.awaitTest()
        assertEquals("line 3000", result.output.lines().last { it.isNotEmpty() })
        assertEquals(1000, result.shownLines)
    }

    @Test
    fun `short test output is not trimmed`() {
        val adb = FakeAdb(listOf("SER1")).apply { answer = { "ok\n" } }
        val vm = vm(listOf("SER1"), adb)
        vm.new()
        vm.setType(ActionType.RunOnce)
        vm.edit { it.copy(command = "echo ok") }
        vm.testRun(emptyMap())
        assertNull(vm.awaitTest().shownLines)
    }

    @Test
    fun `a toggle test run says it ran Turn on`() {
        store.upsert(stayAwake)
        val adb = FakeAdb(listOf("SER1"))
        val vm = vm(listOf("SER1"), adb)
        vm.select("awake")
        vm.testRun(emptyMap())
        assertEquals("Ran Turn on", vm.awaitTest().note)
        assertEquals(listOf("SER1: svc power stayon true"), adb.commands)
    }

    @Test
    fun `the open action deleted elsewhere closes the editor with a message`() {
        store.upsert(stayAwake)
        store.upsert(logcat)
        val vm = vm()
        val messages = Collections.synchronizedList(mutableListOf<String>())
        val collector = scope.launch { vm.messages.collect { messages += it } }
        vm.select("awake")
        vm.edit { it.copy(name = "Renamed") }
        store.delete("awake")
        runBlocking { withTimeout(5_000) { vm.state.first { it.selectedId == null } } }
        assertNull(vm.state.value.draft)
        assertEquals(listOf("Stay awake was deleted"), messages)
        assertFalse(vm.save(), "nothing left to save")
        assertEquals(listOf("Logcat"), store.actions.value.map { it.name })

        // Deleting another action, or deleting from this screen, says nothing.
        vm.select("logcat")
        vm.delete("logcat")
        assertEquals(listOf("Stay awake was deleted"), messages)
        collector.cancel()
    }
}
