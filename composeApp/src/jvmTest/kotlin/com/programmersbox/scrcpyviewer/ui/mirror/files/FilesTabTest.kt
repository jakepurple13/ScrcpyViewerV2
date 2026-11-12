package com.programmersbox.scrcpyviewer.ui.mirror.files

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.adb.ShellRunner
import com.programmersbox.scrcpyviewer.files.DeviceEntry
import com.programmersbox.scrcpyviewer.files.DeviceFiles
import com.programmersbox.scrcpyviewer.files.DeviceSpace
import com.programmersbox.scrcpyviewer.files.Listing
import com.programmersbox.scrcpyviewer.state.AppSettings
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.mirror.FileTransfers
import com.programmersbox.scrcpyviewer.ui.mirror.QuickActionsMemory
import com.programmersbox.scrcpyviewer.ui.onEdt
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow
import java.nio.file.Files
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun file(name: String, size: Long = 10) = DeviceEntry(name, false, size, 1_731_000_000_000)
private fun dir(name: String) = DeviceEntry(name, true, 4096, 1_731_000_000_000)

/** Every listing answers at once, so the browser's work finishes inside the call that started it. */
private class ScriptedFiles : DeviceFiles(ShellRunner(object : AdbClient(adbPath = { error("no adb") }) {}, "serial"), {}) {
    val dirs = mutableMapOf<String, Listing>()

    override suspend fun list(dir: String): Listing = dirs[dir] ?: Listing.Failed("ls: $dir: No such file or directory")
    override suspend fun counts(dir: String, folders: List<String>): Map<String, Int> = folders.associateWith { 3 }
    override suspend fun freeSpace() = DeviceSpace(44_236_955_648, 122_880_000_000)
}

/** The Files tab over a scripted device: layout, states, navigation and sorting. */
@OptIn(ExperimentalComposeUiApi::class)
class FilesTabTest {
    private val files = ScriptedFiles().apply {
        dirs["/sdcard"] = Listing.Entries(listOf(dir("Download"), dir("Documents"), dir("Android")))
        dirs["/sdcard/Download"] = Listing.Entries(
            listOf(file("notes.txt", 50), dir("Telegram"), file("app.apk", 900), file(".nomedia", 0), file("a.jpg", 20)),
        )
        dirs["/sdcard/Download/Telegram"] = Listing.Entries(listOf(file("voice.ogg")))
        dirs["/sdcard/Documents"] = Listing.Entries(emptyList())
        dirs["/sdcard/Android"] = Listing.Entries(listOf(dir("data")))
        dirs["/sdcard/Android/data"] = Listing.Blocked
        dirs["/sdcard/Big"] = Listing.Entries((0 until 5_000).map { file("f%04d.txt".format(it)) })
    }

    private fun browser(start: String = "/sdcard/Download") = FilesBrowser(
        "SER", files, FileTransfers(queue = null, pushTarget = "/sdcard/Download/") {}, QuickActionsMemory(),
        AppSettings(Files.createTempDirectory("files-tab").resolve("settings.json")), start,
        CoroutineScope(Dispatchers.Unconfined), {}, enqueue = { emptyList() }, jobs = emptyFlow(),
    )

    private fun scene(browser: FilesBrowser, motion: Boolean = false): SceneDriver =
        SceneDriver(ImageComposeScene(440, 800, Density(1f)) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                Surface(Modifier.fillMaxSize()) {
                    FilesTab(browser, zone = ZoneOffset.UTC, locale = Locale.US, motion = motion)
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

    private fun SceneDriver.text(value: String): SemanticsNode =
        checkNotNull(nodes().firstOrNull { it.text() == value }) { "no \"$value\" in ${texts()}" }

    private fun SceneDriver.described(description: String): SemanticsNode = checkNotNull(
        nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true },
    ) { "nothing described \"$description\"" }

    private fun SceneDriver.enabled(description: String): Boolean {
        // The clickable that carries the description, or its parent.
        val node = described(description)
        val clickable = generateSequence(node) { it.parent }.first { SemanticsActions.OnClick in it.config }
        return SemanticsProperties.Disabled !in clickable.config
    }

    /** The visible names, top to bottom, among [candidates]. */
    private fun SceneDriver.rows(candidates: Collection<String>): List<String> =
        nodes().filter { it.text() in candidates }.sortedBy { it.boundsInRoot.top }.mapNotNull { it.text() }

    private fun SceneDriver.crumbs(): List<String> {
        val bar = checkNotNull(nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == CRUMBS_TAG })
        val all = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) {
            all += node
            node.children.forEach(::walk)
        }
        walk(bar)
        return all.mapNotNull { it.text() }
    }

    private fun SceneDriver.press(node: SemanticsNode) {
        val at = node.boundsInRoot.center
        scene.sendPointerEvent(PointerEventType.Move, at, type = PointerType.Mouse)
        scene.sendPointerEvent(PointerEventType.Press, at, type = PointerType.Mouse, button = PointerButton.Primary)
        scene.sendPointerEvent(PointerEventType.Release, at, type = PointerType.Mouse, button = PointerButton.Primary)
    }

    private fun SceneDriver.click(node: SemanticsNode) {
        press(node)
        frames(40)
    }

    private fun SceneDriver.doubleClick(node: SemanticsNode) {
        press(node)
        frame()
        press(node)
        frames(40)
    }

    @Test
    fun `opens on the push target with folders first, then names`() = onEdt {
        val driver = scene(browser())
        assertEquals(listOf("Internal storage", "Download"), driver.crumbs())
        assertEquals(listOf("Telegram", "a.jpg", "app.apk", "notes.txt"), driver.rows(listOf("Telegram", "a.jpg", "app.apk", "notes.txt", ".nomedia")))
        assertTrue("Filter in Download" in driver.texts())
        assertTrue("41.2 GB free" in driver.texts())
        driver.described("64% used")
        assertTrue("3 items" in driver.texts(), "the folder's item count")
        assertTrue("Nov 7, 5:20 PM" in driver.texts(), "modified date and time: ${driver.texts()}")
        assertTrue(listOf("Name", "Size", "Modified").all { it in driver.texts() })
    }

    @Test
    fun `double-clicking a folder opens it and Back goes back`() = onEdt {
        val driver = scene(browser())
        assertTrue(!driver.enabled("Back"), "no history yet")
        driver.doubleClick(driver.text("Telegram"))
        assertEquals(listOf("Internal storage", "Download", "Telegram"), driver.crumbs())
        assertTrue("voice.ogg" in driver.texts())
        assertTrue(driver.enabled("Back"))
        driver.click(driver.described("Back"))
        assertEquals(listOf("Internal storage", "Download"), driver.crumbs())
        assertTrue("notes.txt" in driver.texts())
    }

    @Test
    fun `a crumb opens its folder`() = onEdt {
        val b = browser()
        val driver = scene(b)
        driver.click(driver.text("Internal storage"))
        assertEquals("/sdcard", b.path)
        assertEquals(listOf("Internal storage"), driver.crumbs())
        assertEquals(listOf("Android", "Documents", "Download"), driver.rows(listOf("Android", "Documents", "Download")))
    }

    @Test
    fun `an empty folder offers Push files`() = onEdt {
        val driver = scene(browser("/sdcard/Documents"))
        assertTrue("Documents is empty" in driver.texts())
        assertTrue("Drag files here from your computer, or push them from a file picker." in driver.texts())
        assertTrue("Push files" in driver.texts())
    }

    @Test
    fun `a blocked folder explains and goes up`() = onEdt {
        val b = browser("/sdcard/Android/data")
        val driver = scene(b)
        assertTrue("Android blocks this folder" in driver.texts())
        assertTrue("Apps keep private files in /sdcard/Android/data. adb can’t list it without root." in driver.texts())
        driver.click(driver.text("Go up"))
        assertEquals("/sdcard/Android", b.path)
        assertTrue("data" in driver.texts())
    }

    @Test
    fun `a failed listing shows the reason and Retry`() = onEdt {
        val b = browser("/sdcard/Gone")
        val driver = scene(b)
        assertTrue("ls: /sdcard/Gone: No such file or directory" in driver.texts())
        files.dirs["/sdcard/Gone"] = Listing.Entries(listOf(file("back.txt")))
        driver.click(driver.text("Retry"))
        assertTrue("back.txt" in driver.texts())
    }

    @Test
    fun `typing a filter that matches nothing says so`() = onEdt {
        val driver = scene(browser())
        val field = checkNotNull(driver.nodes().firstOrNull { SemanticsActions.SetText in it.config })
        field.config[SemanticsActions.SetText].action?.invoke(AnnotatedString("zzz"))
        driver.frames(40)
        assertTrue("Nothing matches “zzz”" in driver.texts())
        assertTrue("The filter only searches this folder." in driver.texts())
        assertNull(driver.texts().firstOrNull { it == "notes.txt" })
    }

    @Test
    fun `clicking Size twice reverses the order`() = onEdt {
        val driver = scene(browser())
        val names = listOf("Telegram", "a.jpg", "app.apk", "notes.txt")
        driver.click(driver.text("Size"))
        val first = driver.rows(names)
        driver.click(driver.text("Size"))
        val second = driver.rows(names)
        assertEquals("Telegram", first.first(), "folders stay first")
        assertEquals("Telegram", second.first(), "folders stay first")
        assertEquals(first.drop(1).reversed(), second.drop(1))
        assertEquals(listOf("app.apk", "notes.txt", "a.jpg"), first.drop(1), "Size starts largest first")
    }

    @Test
    fun `five thousand entries compose lazily and scroll to the last`() = onEdt {
        val driver = scene(browser("/sdcard/Big"))
        val shown = driver.texts().count { it.startsWith("f") && it.endsWith(".txt") }
        assertTrue(shown in 1..100, "only the rows on screen compose: $shown")
        val list = checkNotNull(driver.nodes().firstOrNull { SemanticsActions.ScrollToIndex in it.config })
        list.config[SemanticsActions.ScrollToIndex].action?.invoke(4_999)
        driver.frames(10)
        assertTrue("f4999.txt" in driver.texts())
    }

    @Test
    fun `with motion the old folder keeps its rows while it slides out`() = onEdt {
        val driver = scene(browser(), motion = true)
        driver.frames(60)
        val telegram = driver.text("Telegram")
        driver.press(telegram)
        driver.frame()
        driver.press(telegram)
        driver.frames(3)
        val mid = driver.texts()
        assertTrue("notes.txt" in mid && "voice.ogg" in mid, "both folders mid-transition: $mid")
        driver.frames(60)
        assertNull(driver.texts().firstOrNull { it == "notes.txt" })
        assertTrue("voice.ogg" in driver.texts())
    }

    @Test
    fun `a file that appears on refresh grows in`() = onEdt {
        val b = browser()
        val driver = scene(b, motion = true)
        driver.frames(60)
        files.dirs["/sdcard/Download"] = Listing.Entries(
            (files.dirs["/sdcard/Download"] as Listing.Entries).entries + file("new.txt"),
        )
        driver.click(driver.described("Refresh"))
        assertEquals(setOf("new.txt"), b.arriving)
        assertTrue("new.txt" in driver.texts())
    }

    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    @Test
    fun `Esc drops a pending new folder`() = onEdt {
        val b = browser()
        val driver = scene(b)
        driver.click(driver.described("New folder"))
        driver.click(driver.described("Filter this folder"))
        assertEquals("New folder", b.pendingNewFolder)
        assertTrue("New folder" in driver.texts())
        for (type in listOf(KeyEventType.KeyDown, KeyEventType.KeyUp)) {
            driver.scene.sendKeyEvent(KeyEvent(key = Key.Escape, type = type))
            driver.frame()
        }
        driver.frames(40)
        assertNull(b.pendingNewFolder)
        assertNull(driver.texts().firstOrNull { it == "New folder" })
    }

    @Test
    fun `a dropped session dims the list under a banner`() = onEdt {
        val b = browser()
        val driver = scene(b)
        b.onConnectionChanged(false)
        driver.frames(40)
        assertTrue("Device disconnected" in driver.texts())
        assertTrue("notes.txt" in driver.texts(), "the list stays")
        assertTrue(!driver.enabled("Refresh"))
        b.onConnectionChanged(true)
        driver.frames(40)
        assertNull(driver.texts().firstOrNull { it == "Device disconnected" })
        assertNotNull(driver.texts().firstOrNull { it == "notes.txt" })
    }
}
