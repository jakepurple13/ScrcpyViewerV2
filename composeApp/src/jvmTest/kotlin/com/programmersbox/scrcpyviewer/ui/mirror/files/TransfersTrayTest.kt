package com.programmersbox.scrcpyviewer.ui.mirror.files

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.adb.ShellRunner
import com.programmersbox.scrcpyviewer.files.DeviceFiles
import com.programmersbox.scrcpyviewer.files.DeviceSpace
import com.programmersbox.scrcpyviewer.files.JobState
import com.programmersbox.scrcpyviewer.files.Listing
import com.programmersbox.scrcpyviewer.files.TransferJob
import com.programmersbox.scrcpyviewer.files.TransferKind
import com.programmersbox.scrcpyviewer.files.TransferRequest
import com.programmersbox.scrcpyviewer.state.AppSettings
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.mirror.FileTransfers
import com.programmersbox.scrcpyviewer.ui.mirror.QuickActionsMemory
import com.programmersbox.scrcpyviewer.ui.onEdt
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val DOWNLOADS: Path = Paths.get("/home/me/Downloads")

private fun job(
    id: Long,
    kind: TransferKind = TransferKind.Push,
    state: JobState = JobState.Running,
    bytes: Long = 0,
    total: Long? = null,
    speed: Long? = null,
    name: String = "movie.mp4",
    deviceDir: String? = null,
    path: Path = Paths.get("/home/me/$name"),
    opensAfter: Boolean = false,
    hostResult: Path? = null,
) = TransferJob(
    id, TransferRequest(path, kind, deviceDir, name.takeIf { kind != TransferKind.Push }, opensAfter),
    state, bytes, total, speed, hostResult,
)

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class TransfersTrayTest {
    private var jobs by mutableStateOf<List<TransferJob>>(emptyList())
    private val cancelled = mutableListOf<Long>()
    private val dismissed = mutableListOf<Long>()

    private fun tray(motion: Boolean = false): SceneDriver =
        SceneDriver(ImageComposeScene(440, 300, Density(1f)) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                Surface(Modifier.fillMaxSize()) {
                    TransfersTray(
                        jobs, pushTarget = "/sdcard/Download/", downloads = DOWNLOADS,
                        onCancel = { cancelled += it },
                        onDismiss = { id ->
                            dismissed += id
                            jobs = jobs.filter { it.id != id }
                        },
                        motion = motion,
                    )
                }
            }
        }).also { it.frames(30) }

    private fun SceneDriver.nodes(): List<SemanticsNode> {
        val all = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) {
            all += node
            node.children.forEach(::walk)
        }
        scene.semanticsOwners.forEach { walk(it.unmergedRootSemanticsNode) }
        return all
    }

    private fun SemanticsNode.text() = config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text
    private fun SceneDriver.texts(): List<String> = nodes().mapNotNull { it.text() }
    private fun SceneDriver.described(d: String) =
        nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(d) == true }

    private fun SceneDriver.click(node: SemanticsNode) {
        val at = node.boundsInRoot.center
        scene.sendPointerEvent(PointerEventType.Move, at, type = PointerType.Mouse)
        scene.sendPointerEvent(PointerEventType.Press, at, type = PointerType.Mouse)
        scene.sendPointerEvent(PointerEventType.Release, at, type = PointerType.Mouse)
        frames(30)
    }

    @Test
    fun `idle tray shows only its header line`() = onEdt {
        val d = tray()
        assertEquals(listOf("Nothing transferring. Pulls, pushes and installs show here."), d.texts())
    }

    @Test
    fun `a running push shows its name, target, bytes and speed`() = onEdt {
        jobs = listOf(job(1, bytes = 50_529_075, total = 193_429_504, speed = 36_500_000))
        val texts = tray().texts()
        assertTrue("Transfers · 1 running" in texts, "$texts")
        assertTrue("movie.mp4" in texts)
        assertTrue("· To Download" in texts, "push target name: $texts")
        assertTrue(texts.any { it.contains(" of ") && it.endsWith("MB/s") }, "progress text: $texts")
    }

    @Test
    fun `speed shows only when known`() = onEdt {
        jobs = listOf(job(1, bytes = 1_000_000, total = 2_000_000, speed = null))
        val d = tray()
        assertTrue(d.texts().any { it.contains(" of ") && !it.contains("/s") }, "${d.texts()}")
    }

    @Test
    fun `a job without a total shows an indeterminate bar`() = onEdt {
        jobs = listOf(job(1, total = null))
        val d = tray()
        val bars = d.nodes().mapNotNull { it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) }
        assertTrue(bars.any { it === ProgressBarRangeInfo.Indeterminate }, "$bars")
        jobs = listOf(job(1, bytes = 5, total = 10))
        d.frames(30)
        val after = d.nodes().mapNotNull { it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) }
        assertTrue(after.none { it === ProgressBarRangeInfo.Indeterminate }, "$after")
    }

    @Test
    fun `cancel calls cancel with the job id`() = onEdt {
        jobs = listOf(job(7, bytes = 1, total = 10))
        val d = tray()
        d.click(checkNotNull(d.described("Cancel movie.mp4")))
        assertEquals(listOf(7L), cancelled)
    }

    @Test
    fun `labels say where pulls, installs and opens go`() = onEdt {
        jobs = listOf(
            job(1, TransferKind.Pull, path = DOWNLOADS, name = "a.jpg"),
            job(2, TransferKind.Pull, path = Paths.get("/home/me/Pictures"), name = "b.jpg"),
            job(3, TransferKind.Install, name = "app.apk"),
            job(4, TransferKind.Pull, name = "c.pdf", opensAfter = true),
            job(5, TransferKind.Push, deviceDir = "/sdcard", name = "d.txt"),
            job(6, TransferKind.Push, deviceDir = "/sdcard/Documents", name = "e.txt"),
        )
        val t = tray().texts()
        for (label in listOf("· To Downloads", "· To Pictures", "· Installing", "· Opening", "· To Internal storage", "· To Documents")) {
            assertTrue(label in t, "$label in $t")
        }
    }

    @Test
    fun `a done row shows done and leaves 2_5 s later through dismiss`() = onEdt {
        jobs = listOf(job(1, bytes = 5, total = 10), job(2, name = "other.bin"))
        val d = tray()
        jobs = listOf(job(1, state = JobState.Done, bytes = 10, total = 10), job(2, name = "other.bin"))
        d.frames(100)
        assertTrue("done" in d.texts(), "${d.texts()}")
        assertTrue(dismissed.isEmpty(), "about 1.6 s in")
        d.frames(100)
        assertEquals(listOf(1L), dismissed)
        assertFalse("movie.mp4" in d.texts())
        assertTrue("other.bin" in d.texts())
    }

    @Test
    fun `failed rows stay with the reason until dismissed`() = onEdt {
        jobs = listOf(job(1, state = JobState.Failed("adb: device offline")))
        val d = tray()
        d.frames(400)
        assertTrue("adb: device offline" in d.texts(), "${d.texts()}")
        assertTrue(dismissed.isEmpty())
        d.click(checkNotNull(d.described("Dismiss movie.mp4")))
        assertEquals(listOf(1L), dismissed)
    }

    @Test
    fun `the header chevron collapses the rows`() = onEdt {
        jobs = listOf(job(1, bytes = 1, total = 10))
        val d = tray()
        assertTrue("movie.mp4" in d.texts())
        d.click(checkNotNull(d.described("Collapse transfers")))
        assertFalse("movie.mp4" in d.texts(), "${d.texts()}")
        assertTrue("Transfers · 1 running" in d.texts())
        d.click(checkNotNull(d.described("Expand transfers")))
        assertTrue("movie.mp4" in d.texts())
    }

    private class NoFiles : DeviceFiles(ShellRunner(object : AdbClient(adbPath = { error("no adb") }) {}, "serial"), {}) {
        override suspend fun list(dir: String): Listing = Listing.Entries(emptyList())
        override suspend fun counts(dir: String, folders: List<String>): Map<String, Int> = emptyMap()
        override suspend fun freeSpace() = DeviceSpace(1, 2)
    }

    private fun browser(flow: MutableStateFlow<List<TransferJob>>, opener: (Path) -> Boolean) = FilesBrowser(
        "SER", NoFiles(), FileTransfers(queue = null, pushTarget = "/sdcard/Download/") {}, QuickActionsMemory(),
        AppSettings(Files.createTempDirectory("tray").resolve("settings.json")), "/sdcard/Download",
        CoroutineScope(UnconfinedTestDispatcher(TestCoroutineScheduler())), enqueue = { emptyList() }, jobs = flow,
        openOnComputer = opener,
    )

    @Test
    fun `a finished opensAfter pull opens the temp file once and says so`() {
        val flow = MutableStateFlow<List<TransferJob>>(emptyList())
        val opened = mutableListOf<Path>()
        val browser = browser(flow) { opened.add(it); true }
        val temp = Paths.get("/tmp/scrcpy-vwr/SER/open/c.pdf")
        flow.value = listOf(job(1, TransferKind.Pull, JobState.Running, name = "c.pdf", opensAfter = true))
        assertTrue(opened.isEmpty())
        val done = job(1, TransferKind.Pull, JobState.Done, name = "c.pdf", opensAfter = true, hostResult = temp)
        flow.value = listOf(done)
        flow.value = listOf(done, job(2))
        assertEquals(listOf(temp), opened)
        assertEquals("Opened c.pdf on your computer", browser.snack?.message)
    }

    @Test
    fun `an opensAfter pull that fails or is cancelled opens nothing`() {
        val flow = MutableStateFlow<List<TransferJob>>(emptyList())
        val opened = mutableListOf<Path>()
        browser(flow) { opened.add(it); true }
        flow.value = listOf(
            job(1, TransferKind.Pull, JobState.Failed("x"), opensAfter = true),
            job(2, TransferKind.Pull, JobState.Cancelled, opensAfter = true),
        )
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `an opener that fails says it could not open`() {
        val flow = MutableStateFlow<List<TransferJob>>(emptyList())
        val browser = browser(flow) { false }
        flow.value = listOf(job(1, TransferKind.Pull, JobState.Done, name = "c.pdf", opensAfter = true, hostResult = Paths.get("/tmp/c.pdf")))
        assertEquals("Could not open c.pdf", browser.snack?.message)
    }

    @Test
    fun `the Files tab docks the tray under the list with the snackbar above it`() = onEdt {
        val flow = MutableStateFlow<List<TransferJob>>(emptyList())
        val transfers = FileTransfers(queue = null, pushTarget = "/sdcard/Download/") {}
        val browser = FilesBrowser(
            "SER", NoFiles(), transfers, QuickActionsMemory(),
            AppSettings(Files.createTempDirectory("tray").resolve("settings.json")), "/sdcard/Download",
            CoroutineScope(UnconfinedTestDispatcher(TestCoroutineScheduler())), enqueue = { emptyList() }, jobs = flow,
            openOnComputer = { true },
        )
        val d = SceneDriver(ImageComposeScene(440, 800, Density(1f)) {
            ScrcpyViewerTheme(ThemeMode.Light) { Surface(Modifier.fillMaxSize()) { FilesTab(browser, motion = false) } }
        }).also { it.frames(30) }
        assertTrue("Nothing transferring. Pulls, pushes and installs show here." in d.texts())
        val running = job(1, TransferKind.Pull, name = "c.pdf", opensAfter = true, bytes = 1, total = 10)
        transfers.jobs = listOf(running)
        d.frames(30)
        assertTrue("Transfers · 1 running" in d.texts(), "${d.texts()}")
        val done = running.copy(state = JobState.Done, hostResult = Paths.get("/tmp/c.pdf"))
        transfers.jobs = listOf(done)
        flow.value = listOf(done)
        d.frames(30)
        val snack = checkNotNull(d.nodes().lastOrNull { it.text() == "Opened c.pdf on your computer" }).boundsInRoot
        val header = checkNotNull(d.nodes().lastOrNull { it.text() == "Transfers · done" }).boundsInRoot
        assertTrue(snack.bottom <= header.top, "snackbar $snack above the tray $header")
    }

    @Test
    fun `header says N failed when only failures remain`() = onEdt {
        jobs = listOf(job(1, state = JobState.Failed("x")))
        assertTrue("Transfers · 1 failed" in tray().texts())
        jobs = listOf(job(1, state = JobState.Failed("x")), job(2, state = JobState.Failed("y")), job(3, state = JobState.Done))
        val d = tray()
        assertTrue("Transfers · 2 failed" in d.texts(), "${d.texts()}")
        jobs = listOf(job(3, state = JobState.Done))
        d.frames(30)
        assertTrue("Transfers · done" in d.texts())
    }

    @Test
    fun `a collapsed tray still dismisses a done row after 2_5 s`() = onEdt {
        jobs = listOf(job(1, bytes = 5, total = 10))
        val d = tray()
        d.click(checkNotNull(d.described("Collapse transfers")))
        jobs = listOf(job(1, state = JobState.Done, bytes = 10, total = 10))
        d.frames(100)
        assertTrue(dismissed.isEmpty())
        d.frames(100)
        assertEquals(listOf(1L), dismissed)
    }

    @Test
    fun `done green reads at 4_5 to 1 on the tray surface in both themes`() {
        fun lum(c: androidx.compose.ui.graphics.Color) = c.luminance()
        fun ratio(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color) =
            (maxOf(lum(a), lum(b)) + 0.05f) / (minOf(lum(a), lum(b)) + 0.05f)
        val light = androidx.compose.ui.graphics.Color(0xFFEEEEEE)
        val dark = androidx.compose.ui.graphics.Color(0xFF211F26)
        assertTrue(ratio(doneGreen(false), light) >= 4.5f)
        assertTrue(ratio(doneGreen(true), dark) >= 4.5f)
    }

    @Test
    fun `opening encodes a space and non-ASCII names`() {
        var uri = ""
        val handler = object : androidx.compose.ui.platform.UriHandler {
            override fun openUri(uri2: String) { uri = uri2 }
        }
        // Under the OS temp folder, so the path is absolute on Windows too (a drive letter, backslashes).
        val file = Paths.get(System.getProperty("java.io.tmpdir")).toAbsolutePath().resolve("my 写真.jpg")
        assertTrue(handler.openFileSafely(file))
        assertTrue(uri.startsWith("file:"), uri)
        assertTrue(uri.all { it.code < 128 }, uri)
        assertTrue("my%20" in uri, uri)
        assertTrue("%E5%86%99%E7%9C%9F" in uri.uppercase(), uri)
    }

    @Test
    fun `a pull already done when the browser starts is not opened`() {
        val done = job(1, TransferKind.Pull, JobState.Done, name = "c.pdf", opensAfter = true, hostResult = Paths.get("/tmp/c.pdf"))
        val flow = MutableStateFlow(listOf(done))
        val opened = mutableListOf<Path>()
        val browser = browser(flow) { opened.add(it); true }
        flow.value = listOf(done, job(2))
        assertTrue(opened.isEmpty())
        assertEquals(null, browser.snack)
    }
}
