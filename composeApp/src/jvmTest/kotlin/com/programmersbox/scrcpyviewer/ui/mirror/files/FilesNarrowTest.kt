package com.programmersbox.scrcpyviewer.ui.mirror.files

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
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
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.adb.ShellRunner
import com.programmersbox.scrcpyviewer.files.DeviceEntry
import com.programmersbox.scrcpyviewer.files.DeviceFiles
import com.programmersbox.scrcpyviewer.files.DeviceSpace
import com.programmersbox.scrcpyviewer.files.JobState
import com.programmersbox.scrcpyviewer.files.Listing
import com.programmersbox.scrcpyviewer.files.SearchHit
import com.programmersbox.scrcpyviewer.files.TransferJob
import com.programmersbox.scrcpyviewer.files.TransferKind
import com.programmersbox.scrcpyviewer.files.TransferRequest
import com.programmersbox.scrcpyviewer.state.AppSettings
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.common.LocalReduceMotion
import com.programmersbox.scrcpyviewer.ui.mirror.FileTransfers
import com.programmersbox.scrcpyviewer.ui.mirror.QuickActionsMemory
import com.programmersbox.scrcpyviewer.ui.onEdt
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import java.nio.file.Files
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Nov 8, 2:32 PM UTC. */
private val PHOTO_TIME = LocalDateTime.of(2026, 11, 8, 14, 32).toInstant(ZoneOffset.UTC).toEpochMilli()
private const val PHOTO = "IMG_20261108_143212.jpg"

/** Every listing answers at once (as FilesTabTest's scripted device), so the browser's work finishes inside the call. */
private class NarrowFiles : DeviceFiles(ShellRunner(object : AdbClient(adbPath = { error("no adb") }) {}, "serial"), {}) {
    val dirs = mutableMapOf<String, Listing>()
    var lists = 0

    override suspend fun list(dir: String): Listing {
        lists++
        return dirs[dir] ?: Listing.Failed("ls: $dir: No such file or directory")
    }
    var counted = true
    var space: DeviceSpace? = DeviceSpace(44_236_955_648, 122_880_000_000)

    override suspend fun counts(dir: String, folders: List<String>): Map<String, Int> =
        if (counted) folders.associateWith { 3 } else emptyMap()
    override suspend fun freeSpace() = space

    override fun search(query: String, includeHidden: Boolean): Flow<List<SearchHit>> = flowOf(
        dirs.flatMap { (d, l) ->
            (l as? Listing.Entries)?.entries.orEmpty()
                .filter { it.name.contains(query, ignoreCase = true) && (includeHidden || !it.hidden) }
                .map { SearchHit(d, it) }
        },
    )
}

/** The Files tab in a narrow sheet: what folds, what moves to a second line, and that 440 dp stays as it was. */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class FilesNarrowTest {
    private val files = NarrowFiles().apply {
        dirs["/sdcard"] = Listing.Entries(listOf(DeviceEntry("Download", true, 4096, PHOTO_TIME)))
        dirs["/sdcard/Download"] = Listing.Entries(
            listOf(
                DeviceEntry("Telegram", true, 4096, PHOTO_TIME),
                DeviceEntry(PHOTO, false, 4_300_000, PHOTO_TIME),
                DeviceEntry("notes.txt", false, 5_400, PHOTO_TIME),
            ),
        )
        dirs["/sdcard/Download/Telegram"] = Listing.Entries(listOf(DeviceEntry("voice.ogg", false, 91_000, PHOTO_TIME)))
    }

    private fun browser(transfers: FileTransfers = FileTransfers(queue = null, pushTarget = "/sdcard/Download/") {}) = FilesBrowser(
        "SER", files, transfers, QuickActionsMemory(),
        AppSettings(Files.createTempDirectory("files-narrow").resolve("settings.json")), "/sdcard/Download",
        CoroutineScope(Dispatchers.Unconfined), {}, enqueue = { emptyList() }, jobs = emptyFlow(),
    )

    /** The tab at [width] dp (a state, so a test can resize it) in a 440 × 640 scene. */
    private fun scene(browser: FilesBrowser, width: MutableState<Int>, motion: Boolean = false): SceneDriver =
        SceneDriver(ImageComposeScene(440, 640, Density(1f)) {
            CompositionLocalProvider(LocalReduceMotion provides !motion) {
                ScrcpyViewerTheme(ThemeMode.Light) {
                    Box(Modifier.width(width.value.dp).fillMaxHeight()) {
                        Surface {
                            FilesTab(browser, zone = ZoneOffset.UTC, locale = Locale.US, motion = motion, isMac = true)
                        }
                    }
                }
            }
        }).also { it.frames(30) }

    private fun scene(browser: FilesBrowser, width: Int, motion: Boolean = false) = scene(browser, mutableStateOf(width), motion)

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
        checkNotNull(nodes().lastOrNull { it.text() == value }) { "no \"$value\" in ${texts()}" }

    private fun SceneDriver.describedOrNull(description: String): SemanticsNode? =
        nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true }

    private fun SceneDriver.described(description: String): SemanticsNode =
        checkNotNull(describedOrNull(description)) { "nothing described \"$description\"" }

    private fun SemanticsNode.clickable(): SemanticsNode = generateSequence(this) { it.parent }.first { SemanticsActions.OnClick in it.config }

    /** The date cell of the row named [name]: the first date at or below its name. */
    private fun SceneDriver.dateOf(name: String): Rect {
        val top = text(name).boundsInRoot.top
        return nodes().filter { it.text() == "Nov 8, 2:32 PM" }.map { it.boundsInRoot }.filter { it.top >= top - 10f }.minBy { it.top }
    }

    /** Inside [width] dp, with a size. */
    private fun Rect.shownWithin(width: Int) = this.width > 0f && height > 0f && left >= -0.5f && right <= width + 0.5f

    private fun SceneDriver.click(node: SemanticsNode, button: PointerButton = PointerButton.Primary) {
        val at = node.boundsInRoot.center
        scene.sendPointerEvent(PointerEventType.Move, at, type = PointerType.Mouse)
        scene.sendPointerEvent(PointerEventType.Press, at, type = PointerType.Mouse, button = button)
        scene.sendPointerEvent(PointerEventType.Release, at, type = PointerType.Mouse, button = button)
        frames(40)
    }

    private fun SceneDriver.key(key: Key, cmd: Boolean = false, shift: Boolean = false) {
        for (type in listOf(KeyEventType.KeyDown, KeyEventType.KeyUp)) {
            scene.sendKeyEvent(KeyEvent(key, type, isMetaPressed = cmd, isShiftPressed = shift))
            frame()
        }
        frames(30)
    }

    @Test
    fun `each flag flips exactly at its threshold`() {
        assertEquals(FilesLayout(false, false, false, false, false, false, false, false), filesLayout(440.dp))
        assertTrue(filesLayout(439.dp).foldRefreshAndNew)
        assertFalse(filesLayout(440.dp).foldRefreshAndNew)
        assertTrue(filesLayout(399.dp).modifiedBelow && filesLayout(399.dp).trayStacked)
        assertFalse(filesLayout(400.dp).modifiedBelow || filesLayout(400.dp).trayStacked)
        assertTrue(filesLayout(379.dp).spaceBelowFilter && filesLayout(379.dp).foldRenameDelete)
        assertFalse(filesLayout(380.dp).spaceBelowFilter || filesLayout(380.dp).foldRenameDelete)
        assertTrue(filesLayout(339.dp).foldPush && filesLayout(339.dp).dialogButtonsStacked)
        assertFalse(filesLayout(340.dp).foldPush || filesLayout(340.dp).dialogButtonsStacked)
        assertTrue(filesLayout(329.dp).sizeBelow)
        assertFalse(filesLayout(330.dp).sizeBelow)
    }

    @Test
    fun `at 280 the crumbs keep 120 dp and the folded buttons lead the view menu`() = onEdt {
        val d = scene(browser(), 280)
        val bar = checkNotNull(d.nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == CRUMBS_TAG }).boundsInRoot
        assertTrue(bar.width >= 120f, "crumbs $bar")
        val crumb = d.text("Download").boundsInRoot
        assertTrue(crumb.left >= bar.left - 1f && crumb.right <= bar.right + 1f && crumb.shownWithin(280), "Download $crumb in $bar")
        assertNull(d.describedOrNull("Refresh"), "Refresh folds")
        assertNull(d.describedOrNull("New folder"), "New folder folds")
        assertNull(d.describedOrNull("Push files here"), "Push folds")
        assertTrue(d.described("View options").boundsInRoot.shownWithin(280))

        d.click(d.described("View options"))
        val top = { label: String -> d.text(label).boundsInRoot.top }
        assertTrue(top("Refresh") < top("New folder") && top("New folder") < top("Push files here…"), "folded in bar order: ${d.texts()}")
        assertTrue(top("Push files here…") < top("Show hidden files"), "folded items first")
        d.click(d.text("Refresh"))
        assertNull(d.texts().firstOrNull { it == "Show hidden files" }, "the menu closes")
    }

    @Test
    fun `folded buttons keep their shortcuts`() = onEdt {
        val b = browser()
        val d = scene(b, 280)
        d.click(d.text("notes.txt"))
        val before = files.lists
        d.key(Key.R, cmd = true)
        assertTrue(files.lists > before, "⌘R refreshes")
        d.key(Key.F2)
        assertEquals("notes.txt", b.renaming, "F2 renames")
        d.key(Key.Escape)
        d.click(d.text("notes.txt"))
        d.key(Key.Backspace)
        assertTrue(b.dialog is FilesDialog.Delete, "⌫ asks to delete")
        d.key(Key.Escape)
        d.key(Key.N, cmd = true, shift = true)
        assertNotNull(b.pendingNewFolder, "⇧⌘N adds a folder")
    }

    @Test
    fun `at 280 a row puts its date on a second line under a full-width name`() = onEdt {
        val d = scene(browser(), 280)
        val name = d.text(PHOTO).boundsInRoot
        val date = d.dateOf(PHOTO)
        val size = d.text("4.1 MB").boundsInRoot
        assertTrue(name.width >= 160f, "name $name")
        assertTrue(date.top >= name.bottom, "date $date under name $name")
        assertTrue(size.right <= date.left && size.top >= name.bottom, "size $size before date $date")
        assertTrue(date.shownWithin(280) && size.shownWithin(280))
        // The sort chip stands in for the column header.
        assertNull(d.texts().firstOrNull { it == "Modified" }, "no Modified column: ${d.texts()}")
        d.click(d.described("Sort by"))
        assertTrue("Size" in d.texts() && "Modified" in d.texts(), "the sort items: ${d.texts()}")
    }

    @Test
    fun `at 280 free space takes its own line under the filter`() = onEdt {
        val d = scene(browser(), 280)
        val filter = d.described("Filter this folder").boundsInRoot
        val free = d.text("41.2 GB free").boundsInRoot
        val meter = d.described("64% used").boundsInRoot
        assertTrue(free.top >= filter.bottom, "free $free under filter $filter")
        assertTrue(free.right <= 280f && free.right >= 280f - 20f, "free at the end: $free")
        assertTrue(meter.width > 100f && meter.right <= free.left, "meter fills the line: $meter")
    }

    @Test
    fun `at 280 Rename and Delete fold into the selection menu`() = onEdt {
        val b = browser()
        val d = scene(b, 280)
        b.click("notes.txt")
        b.toggle(PHOTO)
        d.frames(40)
        assertTrue(d.text("Pull").boundsInRoot.shownWithin(280))
        assertNull(d.describedOrNull("Rename"), "Rename folds")
        assertNull(d.describedOrNull("Delete"), "Delete folds")
        assertNull(d.texts().firstOrNull { it == "Rename" })
        d.click(d.described("More for selection"))
        val top = { label: String -> d.text(label).boundsInRoot.top }
        assertTrue(top("Rename") < top("Delete") && top("Delete") < top("Pull to Downloads"), "folded first: ${d.texts()}")
        assertEquals(1, d.texts().count { it == "Rename" }, "listed once")
    }

    @Test
    fun `at 280 and 360 a transfer stacks inside the sheet, its status on one line`() = onEdt {
        for (width in listOf(280, 360)) {
            val transfers = FileTransfers(queue = null, pushTarget = "/sdcard/Download/") {}
            transfers.jobs = listOf(
                TransferJob(1, TransferRequest(Paths.get("/Users/me/trip.mp4"), TransferKind.Push), JobState.Running, 120_000_000, 380_000_000, 18_000_000),
            )
            val d = scene(browser(transfers), width)
            val name = d.text("trip.mp4").boundsInRoot
            val progress = checkNotNull(d.nodes().lastOrNull { it.text()?.contains(" of ") == true }) { "${d.texts()}" }.boundsInRoot
            val destination = d.text("To Download").boundsInRoot
            val cancel = d.described("Cancel trip.mp4").boundsInRoot
            assertTrue(name.shownWithin(width) && progress.shownWithin(width) && cancel.shownWithin(width), "$width: $name $progress $cancel")
            assertTrue(destination.top > name.bottom && destination.shownWithin(width), "$width: destination $destination under the name")
            // One line when the status fits beside a shortened destination (360), else the whole status under it (280).
            if (width == 360) {
                assertTrue(destination.right <= progress.left && progress.top < destination.bottom, "$width: $destination leads $progress")
            } else {
                assertTrue(progress.top >= destination.bottom, "$width: $progress under $destination")
            }
            assertTrue(progress.height < 1.6f * destination.height, "$width: the status is one line: $progress")
        }
    }

    @Test
    fun `at 280 the delete dialog stacks its buttons, Delete on top`() = onEdt {
        val b = browser()
        val d = scene(b, 280)
        b.click("notes.txt")
        b.askDelete()
        d.frames(40)
        val delete = d.text("Delete").clickable().boundsInRoot
        val cancel = d.text("Cancel").clickable().boundsInRoot
        assertTrue(delete.bottom <= cancel.top, "Delete $delete above Cancel $cancel")
        assertTrue(delete.width >= 200f && cancel.width >= 200f, "$delete $cancel")
        assertTrue(delete.shownWithin(280) && cancel.shownWithin(280))
    }

    @Test
    fun `at 440 nothing folds`() = onEdt {
        val b = browser()
        val d = scene(b, 440)
        assertTrue("Size" in d.texts() && "Modified" in d.texts())
        assertNotNull(d.describedOrNull("Refresh"))
        assertNotNull(d.describedOrNull("Push files here"))
        val name = d.text(PHOTO).boundsInRoot
        val date = d.dateOf(PHOTO)
        assertTrue(date.top < name.bottom, "one line")
        b.click("notes.txt")
        d.frames(40)
        assertNotNull(d.describedOrNull("Rename"))
    }

    @Test
    fun `at 280 a selected search result's Show in folder is an icon right of the name`() = onEdt {
        val b = browser()
        val d = scene(b, 280)
        d.click(d.described("Search device"))
        d.described("Search this device").config[SemanticsActions.SetText].action?.invoke(AnnotatedString("voice"))
        b.search.rerun()
        d.frames(40)
        d.click(d.text("voice.ogg"))
        val reveal = d.described("Show in folder")
        assertTrue(reveal.boundsInRoot.shownWithin(280), "${reveal.boundsInRoot}")
        val all = mutableListOf<SemanticsNode>()
        fun walk(n: SemanticsNode) { all += n; n.children.forEach(::walk) }
        walk(reveal.clickable())
        assertNull(all.firstOrNull { it.text() == "Show in folder" }, "icon only")
        assertTrue(reveal.boundsInRoot.left > d.text("voice.ogg").boundsInRoot.right)
    }

    @Test
    fun `at 280 a long selected result's name stops short of Show in folder`() = onEdt {
        val long = "a-very-long-holiday-photo-album-name-2026.jpg"
        files.dirs["/sdcard/Download/Telegram"] = Listing.Entries(listOf(DeviceEntry(long, false, 91_000, PHOTO_TIME)))
        val b = browser()
        val d = scene(b, 280, motion = true)
        d.click(d.described("Search device"))
        d.described("Search this device").config[SemanticsActions.SetText].action?.invoke(AnnotatedString("holiday"))
        b.search.rerun()
        d.frames(40)
        val before = d.text(long).boundsInRoot
        d.click(d.text(long))
        d.frames(60)
        val name = d.text(long).boundsInRoot
        val reveal = d.described("Show in folder").boundsInRoot
        assertTrue(name.right <= reveal.left, "name $name, icon $reveal")
        assertTrue(name.right < before.right, "the name gave up room: $before -> $name")
    }

    @Test
    fun `below 330 a folder without a count yet puts its date at the start of the second line`() = onEdt {
        files.counted = false
        val d = scene(browser(), 280)
        val name = d.text("Telegram").boundsInRoot
        val date = d.dateOf("Telegram")
        assertTrue(date.top >= name.bottom && date.left == name.left, "date $date under name $name")
    }

    @Test
    fun `at 280 the free space line opens and closes with the spring`() = onEdt {
        files.space = null
        val b = browser()
        val d = scene(b, 280, motion = true)
        val chip = { d.described("Sort by").boundsInRoot.top }
        val closed = chip()
        files.space = DeviceSpace(44_236_955_648, 122_880_000_000)
        b.refresh()
        d.frames(3)
        val opening = chip()
        d.frames(90)
        val open = chip()
        assertTrue(open > closed && opening > closed && opening < open, "opening: $closed $opening $open")
        files.space = null
        b.refresh()
        d.frames(3)
        val closing = chip()
        d.frames(90)
        assertTrue(closing < open && closing > closed && chip() == closed, "closing: $open $closing ${chip()}")
    }

    @Test
    fun `with reduced motion a threshold crossing snaps`() = onEdt {
        val width = mutableStateOf(440)
        val d = scene(browser(), width, motion = false)
        // Each part's own layout, independent of what moves above it: the date under its name, free space under the pill.
        fun dateOffset() = d.dateOf(PHOTO).top - d.text(PHOTO).boundsInRoot.top
        fun spaceOffset() = d.text("41.2 GB free").boundsInRoot.top - d.described("Filter this folder").boundsInRoot.top
        val start = dateOffset() to spaceOffset()
        width.value = 280
        val seen = List(10) {
            d.frame()
            dateOffset() to spaceOffset()
        }
        d.frames(60)
        val end = dateOffset() to spaceOffset()
        assertTrue(end.first > start.first && end.second > start.second, "moved: $start -> $end")
        assertEquals(end, seen.last(), "settled within a few frames: $seen")
        assertTrue(
            seen.all { (it.first == start.first || it.first == end.first) && (it.second == start.second || it.second == end.second) },
            "no frames in between: $start $seen $end",
        )
    }

    @Test
    fun `stacked dialog buttons tab in stack order`() {
        assertEquals(listOf(1, 0, 2), stackOrder(listOf(false, true, false)))
        assertEquals(0, nextInOrder(listOf(1, 0, 2), from = 1, step = 1), "filled on top, then the first under it")
        assertEquals(1, nextInOrder(listOf(1, 0, 2), from = 2, step = 1), "wraps to the top")
        assertEquals(2, nextInOrder(listOf(1, 0, 2), from = 1, step = -1))
    }

    @Test
    fun `narrowing with motion moves the date down over several frames`() = onEdt {
        val width = mutableStateOf(440)
        val d = scene(browser(), width, motion = true)
        d.frames(60)
        val start = d.dateOf(PHOTO).top
        width.value = 280
        d.frames(4)
        val mid = d.dateOf(PHOTO).top
        d.frames(90)
        val end = d.dateOf(PHOTO).top
        assertTrue(end > start, "moves down: $start -> $end")
        assertTrue(mid > start && mid < end, "mid-frame between: $start $mid $end")
    }
}
