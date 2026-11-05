package com.programmersbox.scrcpyviewer.actions

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ActionInboxTest {
    private val root: Path = Files.createTempDirectory("inbox")
    private val dir = root.resolve("actions-inbox")
    private val store = ActionStore(root.resolve("actions.json"))
    private var now = 0L
    private val inbox = ActionInbox(dir, store) { now }

    init {
        Files.createDirectories(dir)
    }

    private fun actionJson(id: String = "bt", name: String = "Bluetooth", command: String = "svc bluetooth enable") =
        """{ "id": "$id", "name": "$name", "type": "once", "command": "$command" }"""

    private fun fileText(vararg actions: String, author: String? = null): String {
        val a = author?.let { "\"author\": \"$it\"," } ?: ""
        return """{ "format": "scrcpy-viewer.actions", "version": 1, $a "actions": [${actions.joinToString(",")}] }"""
    }

    private fun drop(name: String, text: String, modified: Long? = null): Path {
        val f = dir.resolve(name)
        f.writeText(text)
        if (modified != null) Files.setLastModifiedTime(f, FileTime.fromMillis(modified))
        now = f.getLastModifiedTime().toMillis() + 10_000
        return f
    }

    private fun TestScope.collect(): MutableList<String> {
        val out = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { inbox.messages.collect { out += it } }
        return out
    }

    @Test
    fun `accepted file is applied, deleted and announced`() = runTest {
        val msgs = collect()
        val f = drop("x.json", fileText(actionJson(), author = "Claude Code"))
        inbox.processOnce()
        assertEquals(listOf("Bluetooth"), store.actions.value.map { it.name })
        assertEquals(ActionSource.Ai, store.actions.value.single().source)
        assertFalse(f.exists())
        assertEquals(listOf("✦ Claude Code added Bluetooth"), msgs)
    }

    @Test
    fun `no author says AI`() = runTest {
        val msgs = collect()
        drop("x.json", fileText(actionJson()))
        inbox.processOnce()
        assertEquals(listOf("✦ AI added Bluetooth"), msgs)
    }

    @Test
    fun `update message`() = runTest {
        store.upsert(RunOnceAction("bt", "Bluetooth", command = "old"))
        val msgs = collect()
        drop("x.json", fileText(actionJson()))
        inbox.processOnce()
        assertEquals(listOf("✦ AI updated Bluetooth"), msgs)
    }

    @Test
    fun `tmp files are ignored`() = runTest {
        val msgs = collect()
        val f = drop("x.json.tmp", fileText(actionJson()))
        inbox.processOnce()
        assertTrue(f.exists())
        assertTrue(store.actions.value.isEmpty())
        assertTrue(msgs.isEmpty())
    }

    @Test
    fun `invalid file is moved to rejected with reason`() = runTest {
        val msgs = collect()
        val f = drop("x.json", fileText(actionJson(command = " ")))
        inbox.processOnce()
        assertFalse(f.exists())
        assertTrue(dir.resolve("rejected/x.json").exists())
        assertTrue(dir.resolve("rejected/x.json.error.txt").readText().contains("Action 1: command is required"))
        assertEquals(listOf("Couldn't add actions from x.json: Action 1: command is required"), msgs)
        assertTrue(store.actions.value.isEmpty())
    }

    @Test
    fun `rename clash rejects whole file`() = runTest {
        store.upsert(RunOnceAction("other", "Wifi", command = "x"))
        store.upsert(RunOnceAction("bt", "Bluetooth", command = "x"))
        val msgs = collect()
        drop("x.json", fileText(actionJson("new", "Fresh"), actionJson("bt", "wifi")))
        inbox.processOnce()
        assertEquals(listOf("Wifi", "Bluetooth"), store.actions.value.map { it.name })
        assertEquals(listOf("Couldn't add actions from x.json: Action 2: another action is already named wifi"), msgs)
    }

    @Test
    fun `half-written fresh file is retried, not rejected`() = runTest {
        val f = drop("x.json", """{"format": "scr""")
        val modified = f.getLastModifiedTime().toMillis()
        now = modified + 500
        inbox.processOnce()
        assertTrue(f.exists())
        assertFalse(dir.resolve("rejected/x.json").exists())
        now = modified + 2500
        inbox.processOnce()
        assertFalse(f.exists())
        assertTrue(dir.resolve("rejected/x.json").exists())
    }

    @Test
    fun `second rejection of same name overwrites`() = runTest {
        drop("x.json", fileText(actionJson(command = "")))
        inbox.processOnce()
        drop("x.json", fileText(actionJson(name = " ")))
        inbox.processOnce()
        assertTrue(dir.resolve("rejected/x.json.error.txt").readText().contains("Action 1: name is required"))
        assertFalse(dir.resolve("rejected/x.json.error.txt").readText().contains("command"))
    }

    @Test
    fun `duplicate ids within one file both added`() = runTest {
        drop("x.json", fileText(actionJson("d", "One"), actionJson("d", "Two")))
        inbox.processOnce()
        assertEquals(listOf("One", "Two"), store.actions.value.map { it.name })
    }

    @Test
    fun `backlog processed oldest first`() = runTest {
        drop("b.json", fileText(actionJson("b", "Bluetooth 2")), modified = 2_000_000)
        drop("a.json", fileText(actionJson("b", "Bluetooth")), modified = 1_000_000)
        now = 10_000_000
        inbox.processOnce()
        assertEquals(listOf("Bluetooth 2"), store.actions.value.map { it.name })
    }

    @Test
    fun `names are trimmed before validation and apply`() = runTest {
        store.upsert(RunOnceAction("other", "bluetooth", command = "x"))
        drop("x.json", fileText(actionJson("bt", " Bluetooth ")))
        inbox.processOnce()
        assertEquals(listOf("bluetooth", "Bluetooth (2)"), store.actions.value.map { it.name })
    }

    @Test
    fun `trimmed name is stored without spaces`() = runTest {
        drop("x.json", fileText(actionJson("bt", " Bluetooth ")))
        inbox.processOnce()
        assertEquals(listOf("Bluetooth"), store.actions.value.map { it.name })
    }

    @Test
    fun `start does not throw when the inbox can't be created`() = runTest {
        val blocked = root.resolve("blocked")
        blocked.writeText("not a folder")
        val job = Job()
        ActionInbox(blocked, store) { now }.start(CoroutineScope(job))
        job.cancel()
    }

    @Test
    fun `validateInbox reports each rule`() {
        fun v(a: CustomAction) = validateInbox(InboxFile(null, listOf(a)), emptyList())
        assertEquals("Action 1: on is required", v(ToggleAction("t", "T", on = "", off = "x")))
        assertEquals("Action 1: off is required", v(ToggleAction("t", "T", on = "x", off = "")))
        assertEquals(
            "Action 1: onWhen regex is invalid",
            v(ToggleAction("t", "T", on = "x", off = "y", onWhen = OnWhen(MatchMode.Regex, "("))),
        )
    }
}
