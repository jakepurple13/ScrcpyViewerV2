package com.programmersbox.scrcpyviewer.ui.mirror.links

import com.programmersbox.scrcpyviewer.actions.CommandTemplate
import com.programmersbox.scrcpyviewer.actions.OutputTarget
import com.programmersbox.scrcpyviewer.actions.RunOnceAction
import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.adb.ShellResult
import com.programmersbox.scrcpyviewer.adb.ShellRunner
import com.programmersbox.scrcpyviewer.adb.ShellTimeoutException
import com.programmersbox.scrcpyviewer.apps.DeviceApp
import com.programmersbox.scrcpyviewer.links.Before
import com.programmersbox.scrcpyviewer.links.Extra
import com.programmersbox.scrcpyviewer.links.ExtraType
import com.programmersbox.scrcpyviewer.links.LinkRequest
import com.programmersbox.scrcpyviewer.links.LinkStore
import com.programmersbox.scrcpyviewer.links.deviceCommand
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class LinksStateTest {
    private val store = LinkStore(Files.createTempDirectory("links").resolve("links.json"))
    private val apps = listOf(
        DeviceApp("com.android.chrome", "Chrome", system = true, versionName = null, versionCode = null),
        DeviceApp("com.google.android.youtube", "YouTube", system = true, versionName = null, versionCode = null),
    )

    private class FakeRunner(val answer: suspend (String) -> String) :
        ShellRunner(object : AdbClient(adbPath = { error("no adb") }) {}, "s") {
        val ran = mutableListOf<String>()
        override suspend fun run(cmd: String, timeoutMs: Long): ShellResult {
            ran += cmd
            return ShellResult(answer(cmd), 0, 1)
        }
    }

    private fun state(runner: ShellRunner?, scope: CoroutineScope, serial: String = "d1") =
        LinksState(serial, runner, store, { apps }, scope, clock = { 1000L })

    private val coldChrome = """
        Starting: Intent { act=android.intent.action.VIEW dat=https://example.com/... }
        Status: ok
        LaunchState: COLD
        Activity: com.android.chrome/com.google.android.apps.chrome.Main
        TotalTime: 412
        WaitTime: 420
        Complete
    """.trimIndent()

    private val unresolved = """
        Starting: Intent { act=android.intent.action.VIEW dat=myapp://x }
        Error: Activity not started, unable to resolve Intent { act=android.intent.action.VIEW dat=myapp://x flg=0x10000000 }
    """.trimIndent()

    private fun TestScope.opened(text: String, answer: String, pkg: String? = null): Pair<LinksState, FakeRunner> {
        val runner = FakeRunner { answer }
        val s = state(runner, this)
        s.onEvent(LinksEvent.SetText(text))
        s.onEvent(LinksEvent.SetPackage(pkg))
        s.onEvent(LinksEvent.Open)
        runCurrent()
        return s to runner
    }

    @Test
    fun `open success runs the command and records an ok entry with the app label`() = runTest {
        val (s, runner) = opened("https://example.com/orders/1042", coldChrome)
        assertEquals(listOf(deviceCommand(LinkRequest("https://example.com/orders/1042", action = LinksState.DEFAULT_ACTION))), runner.ran)
        assertFalse(s.running)
        val outcome = assertNotNull(s.outcome)
        assertTrue(outcome.result.ok)
        assertEquals("Opened in Chrome · cold start 412 ms", outcome.headline)
        assertEquals("Status: ok · LaunchState: COLD · TotalTime: 412 · WaitTime: 420", outcome.raw)
        val entry = s.history.recent.single()
        assertEquals("https://example.com/orders/1042", entry.link)
        assertNull(entry.packageName)
        assertEquals("Chrome", entry.appLabel)
        assertTrue(entry.ok)
        assertEquals(1000L, entry.at)
        assertEquals(entry, store.state.value.history["d1"]!!.single())
    }

    @Test
    fun `open failure records a not-ok entry and names the chosen app`() = runTest {
        val (s, _) = opened("myapp://x", unresolved, pkg = "com.google.android.youtube")
        val outcome = assertNotNull(s.outcome)
        assertFalse(outcome.result.ok)
        assertEquals("No app on this device opens this link with YouTube", outcome.headline)
        assertTrue(outcome.raw!!.startsWith("Error: Activity not started"))
        val entry = s.history.recent.single()
        assertFalse(entry.ok)
        assertNull(entry.appLabel)
        assertEquals("com.google.android.youtube", entry.packageName)
        assertEquals(outcome.raw, entry.detail)
    }

    @Test
    fun `failure without a chosen app has no with suffix`() = runTest {
        val (s, _) = opened("myapp://x", unresolved)
        assertEquals("No app on this device opens this link", s.outcome!!.headline)
    }

    @Test
    fun `running is set while am start runs, and a hung command ends in an error after 30 s`() = runTest {
        val runner = FakeRunner { awaitCancellation() } // ignores its own timeout
        val s = state(runner, this)
        s.onEvent(LinksEvent.SetText("https://example.com"))
        s.open()
        runCurrent()
        assertTrue(s.running)
        assertFalse(s.canOpen)
        s.open() // ignored while running
        runCurrent()
        assertEquals(1, runner.ran.size)
        advanceTimeBy(29_999)
        runCurrent()
        assertTrue(s.running)
        advanceTimeBy(2)
        runCurrent()
        assertFalse(s.running)
        val outcome = assertNotNull(s.outcome)
        assertFalse(outcome.result.ok)
        assertEquals("Timed out after 30 s", outcome.headline)
        assertFalse(s.history.recent.single().ok)
        assertTrue(s.canOpen)
    }

    @Test
    fun `the runner's own timeout is an error result too`() = runTest {
        val s = state(FakeRunner { throw ShellTimeoutException() }, this)
        s.onEvent(LinksEvent.SetText("https://example.com"))
        s.open()
        runCurrent()
        assertFalse(s.running)
        assertEquals("Timed out after 30 s", s.outcome!!.headline)
    }

    @Test
    fun `not a link does not run`() = runTest {
        val runner = FakeRunner { coldChrome }
        val s = state(runner, this)
        s.onEvent(LinksEvent.SetText("hello"))
        s.open()
        runCurrent()
        assertEquals(emptyList(), runner.ran)
        assertFalse(s.canOpen)
    }

    @Test
    fun `category and extra only while intent details is open`() = runTest {
        val s = state(FakeRunner { coldChrome }, this)
        s.onEvent(LinksEvent.SetText("https://example.com"))
        s.onEvent(LinksEvent.SetExtra(Extra(ExtraType.String, "source", "qa")))
        assertEquals(LinkRequest("https://example.com", action = LinksState.DEFAULT_ACTION), s.request)
        s.onEvent(LinksEvent.ToggleDetails)
        assertEquals(
            LinkRequest("https://example.com", action = LinksState.DEFAULT_ACTION, category = LinksState.DEFAULT_CATEGORY, extra = Extra(ExtraType.String, "source", "qa")),
            s.request,
        )
        assertEquals("adb -s 'd1' shell " + com.programmersbox.scrcpyviewer.apps.DeviceApps.quote(deviceCommand(s.request)), s.preview)
        assertTrue("android.intent.category.BROWSABLE" in s.preview, s.preview)
    }

    @Test
    fun `force-stop without a chosen app does nothing`() = runTest {
        val s = state(null, this)
        s.onEvent(LinksEvent.SetText("https://example.com"))
        s.onEvent(LinksEvent.SetBefore(Before.ForceStop))
        assertEquals(Before.None, s.request.before)
        s.onEvent(LinksEvent.SetPackage("com.android.chrome"))
        assertEquals(Before.ForceStop, s.request.before)
    }

    @Test
    fun `star and unstar move a link between starred and recent`() = runTest {
        val (s, _) = opened("https://example.com", coldChrome)
        val entry = s.history.recent.single()
        s.toggleStar(entry)
        assertTrue(s.isStarred(entry))
        assertEquals(listOf(entry), s.history.starred)
        assertEquals(emptyList(), s.history.recent, "a starred link shows once")
        s.toggleStar(entry)
        assertFalse(s.isStarred(entry))
        assertEquals(emptyList(), s.history.starred)
        assertEquals(listOf(entry), s.history.recent)
    }

    @Test
    fun `clear keeps stars`() = runTest {
        val runner = FakeRunner { coldChrome }
        val s = state(runner, this)
        for (link in listOf("https://a.example", "https://b.example")) {
            s.onEvent(LinksEvent.SetText(link))
            s.open()
            runCurrent()
        }
        val a = s.history.recent.first { it.link == "https://a.example" }
        s.toggleStar(a)
        s.clear()
        assertEquals(emptyList(), s.history.recent)
        assertEquals(listOf(a), s.history.starred)
    }

    @Test
    fun `another device sees its own history but the shared stars`() = runTest {
        val (s, _) = opened("https://example.com", coldChrome)
        s.onEvent(LinksEvent.SetText("https://other.example"))
        s.open()
        runCurrent()
        s.toggleStar(s.history.recent.first { it.link == "https://example.com" })
        val other = state(FakeRunner { coldChrome }, this, serial = "d2")
        assertEquals(emptyList(), other.history.recent)
        assertEquals(listOf("https://example.com"), other.history.starred.map { it.link })
        assertEquals(listOf("https://other.example"), s.history.recent.map { it.link })
    }

    @Test
    fun `reopen and fill take the row's link and app`() = runTest {
        val runner = FakeRunner { coldChrome }
        val s = state(runner, this)
        val entry = com.programmersbox.scrcpyviewer.links.LinkEntry("https://x.example", "com.android.chrome", "Chrome", ok = true, at = 1)
        s.fill(entry)
        assertEquals("https://x.example", s.text)
        assertEquals("com.android.chrome", s.packageName)
        assertEquals(1, s.fills)
        s.onEvent(LinksEvent.SetText(""))
        s.reopen(entry)
        runCurrent()
        assertEquals(listOf(deviceCommand(LinkRequest("https://x.example", "com.android.chrome", action = LinksState.DEFAULT_ACTION))), runner.ran)
    }

    @Test
    fun `save template carries the command with literal braces`() = runTest {
        val s = state(null, this)
        s.onEvent(LinksEvent.SetText("https://shop.example.com/p?id={x}"))
        s.onEvent(LinksEvent.SetPackage("com.android.chrome"))
        s.onEvent(LinksEvent.SetBefore(Before.ForceStop))
        val t = assertNotNull(s.saveTemplate())
        assertEquals("Open shop.example.com", t.name)
        val action = t.action as RunOnceAction
        assertEquals("Open shop.example.com", action.name)
        assertEquals("open_in_new", action.icon)
        assertEquals(OutputTarget.Toast, action.output)
        assertFalse(action.confirm)
        assertEquals(emptyList(), CommandTemplate.variables(action.command))
        assertEquals(deviceCommand(s.request), CommandTemplate.substitute(action.command, emptyMap()))
        assertTrue(action.command.startsWith("am force-stop 'com.android.chrome' && am start -W"), action.command)
    }

    @Test
    fun `save names a non-web link by its scheme, and refuses a non-link`() = runTest {
        val s = state(null, this)
        s.onEvent(LinksEvent.SetText("myapp://settings/profile"))
        assertEquals("Open myapp", s.saveTemplate()!!.name)
        s.onEvent(LinksEvent.SetText("geo:40.7,-111.8"))
        assertEquals("Open geo", s.saveTemplate()!!.name)
        s.onEvent(LinksEvent.SetText("nope"))
        assertNull(s.saveTemplate())
    }

    @Test
    fun `the sheet forwards Links events, and Save opens the editor with the link's template`() = runTest {
        val links = state(null, this)
        val opened = mutableListOf<com.programmersbox.scrcpyviewer.actions.ActionTemplate>()
        val qa = com.programmersbox.scrcpyviewer.ui.mirror.QuickActions(device = null, scope = this, links = links, onNewAction = { opened += it }) {}
        qa.onEvent(com.programmersbox.scrcpyviewer.ui.mirror.QuickActionEvent.Links(LinksEvent.SetText("https://example.com")))
        assertEquals("https://example.com", links.text)
        qa.onEvent(com.programmersbox.scrcpyviewer.ui.mirror.QuickActionEvent.Links(LinksEvent.Save))
        assertEquals(listOf("Open example.com"), opened.map { it.name })
    }

    @Test
    fun `a changed action is used only while Intent details is open`() = runTest {
        val s = state(null, this)
        s.onEvent(LinksEvent.SetText("https://example.com"))
        s.onEvent(LinksEvent.ToggleDetails)
        s.onEvent(LinksEvent.SetAction("android.intent.action.SEND"))
        assertEquals("android.intent.action.SEND", s.request.action)
        s.onEvent(LinksEvent.ToggleDetails)
        assertEquals(LinksState.DEFAULT_ACTION, s.request.action)
        assertEquals("android.intent.action.SEND", s.action, "the field keeps what was typed")
    }

    @Test
    fun `editing the link clears the last result, and filling a different link does too`() = runTest {
        val (s, _) = opened("myapp://x", unresolved)
        assertNotNull(s.outcome)
        s.onEvent(LinksEvent.SetText("myapp://x"))
        assertNotNull(s.outcome, "the same text keeps it")
        s.onEvent(LinksEvent.SetText("myapp://xy"))
        assertNull(s.outcome)
        s.onEvent(LinksEvent.Open)
        runCurrent()
        assertNotNull(s.outcome)
        s.onEvent(LinksEvent.Fill(s.history.recent.last()))
        assertNull(s.outcome)
    }

    @Test
    fun `an intent URI is recorded without the Open with app it ignores`() = runTest {
        val intent = "intent://scan/#Intent;scheme=zxing;package=com.google.zxing.client.android;end"
        val (s, _) = opened(intent, coldChrome, pkg = "com.google.android.youtube")
        val entry = s.history.recent.single()
        assertNull(entry.packageName)
        assertEquals("Chrome", entry.appLabel)
    }
}
