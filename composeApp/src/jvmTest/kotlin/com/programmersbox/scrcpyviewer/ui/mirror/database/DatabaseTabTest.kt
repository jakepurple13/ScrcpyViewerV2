package com.programmersbox.scrcpyviewer.ui.mirror.database

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import com.programmersbox.scrcpyviewer.apps.DeviceApp
import com.programmersbox.scrcpyviewer.database.RootState
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.common.LocalReduceMotion
import com.programmersbox.scrcpyviewer.ui.onEdt
import com.programmersbox.scrcpyviewer.ui.settings.click
import com.programmersbox.scrcpyviewer.ui.settings.helpScene
import com.programmersbox.scrcpyviewer.ui.settings.key
import com.programmersbox.scrcpyviewer.ui.settings.node
import com.programmersbox.scrcpyviewer.ui.settings.nodeContaining
import com.programmersbox.scrcpyviewer.ui.settings.type
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal val DEBUG_APP = DeviceApp("com.example.notes", "Notes", false, "1", 1, debuggable = true)
internal val RELEASE_APP = DeviceApp("com.android.chrome", "Chrome", true, "1", 1)

/** A controller over a real local copy of a small notes database (plus a 1 200-row `trace` table); runs inline. */
internal fun notesController(running: Boolean = false, root: RootState = RootState.None): Pair<DatabaseController, FakeDevice> {
    val temp = Files.createTempDirectory("dbtab")
    val device = FakeDevice(
        Files.createTempDirectory(temp, "fx"),
        "CREATE TABLE notes (id INTEGER PRIMARY KEY, title TEXT, pinned INTEGER)",
        "INSERT INTO notes VALUES (1,'Grocery run',1),(2,'Release',0),(3,'Gift ideas',1)",
        "CREATE TABLE trace (id INTEGER PRIMARY KEY, msg TEXT)",
        "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 1200) INSERT INTO trace SELECT i, 'line ' || i FROM n",
    ).apply { this.running = running }
    val controller = DatabaseController(
        devices = device, probe = FakeProbe(root), apps = { listOf(DEBUG_APP, RELEASE_APP) }, tempRoot = temp.resolve("work"),
        scope = CoroutineScope(Dispatchers.Unconfined), notify = {}, launch = {}, io = Dispatchers.Unconfined,
    )
    return controller to device
}

internal fun dataScene(
    controller: DatabaseController,
    width: Int = 900,
    height: Int = 700,
    motion: Boolean = false,
    onShowDebuggable: () -> Unit = {},
): SceneDriver = helpScene(width, height) {
    val info = object : androidx.compose.ui.platform.WindowInfo {
        override val isWindowFocused = true
        override val containerSize get() = androidx.compose.ui.unit.IntSize(width, height)
    }
    CompositionLocalProvider(LocalReduceMotion provides !motion, androidx.compose.ui.platform.LocalWindowInfo provides info) {
        DatabaseTab(controller, motion = motion, onShowDebuggableApps = onShowDebuggable)
    }
}.also { it.frames(40) }

internal fun SceneDriver.doubleClick(text: String) {
    val at = assertNotNull(node(text), "no $text").boundsInRoot.center
    repeat(2) {
        scene.sendPointerEvent(PointerEventType.Move, at, type = PointerType.Mouse)
        scene.sendPointerEvent(PointerEventType.Press, at, type = PointerType.Mouse, button = PointerButton.Primary)
        scene.sendPointerEvent(PointerEventType.Release, at, type = PointerType.Mouse, button = PointerButton.Primary)
        frames(4) // taps closer than 40 ms aren't a double-click
    }
    frames(3)
}

class DatabaseTabTest {
    @Test
    fun `browse shows rows and the total`() = onEdt {
        val (c, _) = notesController()
        c.pick(DEBUG_APP)
        val d = dataScene(c)
        assertNotNull(d.node("Grocery run"))
        assertNotNull(d.node("3 rows"))
        d.click("trace")
        d.frames(10)
        assertNotNull(d.nodeContaining("500 of 1,200"))
        d.scene.close()
    }

    @Test
    fun `double-click edits a cell, stages it and shows the bar`() = onEdt {
        val (c, _) = notesController()
        c.pick(DEBUG_APP)
        val d = dataScene(c)
        d.doubleClick("Release")
        d.type("d")
        d.key(Key.Enter)
        d.frames(10)
        assertEquals(1, c.staged)
        assertNotNull(d.node("Released"))
        assertNotNull(d.node("1 change staged in notes.db"))
        d.scene.close()
    }

    @Test
    fun `Esc cancels an edit`() = onEdt {
        val (c, _) = notesController()
        c.pick(DEBUG_APP)
        val d = dataScene(c)
        d.doubleClick("Release")
        d.type("xyz")
        d.key(Key.Escape)
        d.frames(5)
        assertEquals(0, c.staged)
        assertNull(d.node("1 change staged in notes.db"))
        d.scene.close()
    }

    @Test
    fun `a query runs and shows its rows`() = onEdt {
        val (c, _) = notesController()
        c.pick(DEBUG_APP)
        val d = dataScene(c)
        d.click("Query")
        d.frames(10)
        c.runQuery("SELECT title FROM notes WHERE pinned = 1")
        d.frames(5)
        assertNotNull(d.nodeContaining("2 rows"))
        assertNotNull(d.node("Gift ideas"))
        d.scene.close()
    }

    @Test
    fun `structure shows the create statement`() = onEdt {
        val (c, _) = notesController()
        c.pick(DEBUG_APP)
        val d = dataScene(c)
        d.click("Structure")
        d.frames(10)
        assertNotNull(d.nodeContaining("CREATE TABLE notes"))
        d.scene.close()
    }

    @Test
    fun `a locked app offers the debuggable apps`() = onEdt {
        val (c, _) = notesController()
        c.pick(RELEASE_APP)
        var shown = 0
        val d = dataScene(c) { shown++ }
        assertNotNull(d.node("Chrome's databases are private"))
        d.click("Show debuggable apps")
        assertEquals(1, shown)
        d.scene.close()
    }

    @Test
    fun `the write dialog says whether the app is running`() = onEdt {
        for (running in listOf(true, false)) {
            val (c, _) = notesController(running = running)
            c.pick(DEBUG_APP)
            c.setCell(1, 1, com.programmersbox.scrcpyviewer.database.SqlValue.Text("x"))
            val d = dataScene(c)
            d.click("Write to device")
            d.frames(5)
            if (running) {
                assertNotNull(d.node("Notes is running"))
                assertNotNull(d.node("Stop app and write"))
            } else {
                assertNotNull(d.node("Notes isn't running"))
                assertNotNull(d.node("Write"))
            }
            d.scene.close()
        }
    }

    @Test
    fun `the staged bar slides in`() = onEdt {
        val (c, _) = notesController()
        c.pick(DEBUG_APP)
        val d = dataScene(c, motion = true)
        c.setCell(1, 1, com.programmersbox.scrcpyviewer.database.SqlValue.Text("x"))
        val tops = List(40) {
            d.frame()
            d.node("1 change staged in notes.db")?.boundsInRoot?.top
        }.filterNotNull().filter { it > 0f }
        val end = tops.last()
        assertTrue(tops.any { it > end + 4f }, "the bar rises into place: $tops")
        assertEquals(end, tops.takeLast(5).first(), "then settles")
        d.scene.close()
    }

    @Test
    fun `at 360 dp a table opens over the list and Esc goes back`() = onEdt {
        val (c, _) = notesController()
        c.pick(DEBUG_APP)
        val d = dataScene(c, width = 360)
        assertNull(d.node("Grocery run"), "list first")
        d.click("notes")
        d.frames(30)
        assertNotNull(d.node("Grocery run"))
        d.key(Key.Escape)
        d.frames(30)
        assertNull(d.node("Grocery run"))
        d.scene.close()
    }
}
