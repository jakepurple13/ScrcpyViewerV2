package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.apps.AppFilter
import com.programmersbox.scrcpyviewer.apps.DeviceApp
import com.programmersbox.scrcpyviewer.database.RootState
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.common.LocalReduceMotion
import com.programmersbox.scrcpyviewer.ui.onEdt
import com.programmersbox.scrcpyviewer.ui.settings.click
import com.programmersbox.scrcpyviewer.ui.settings.helpScene
import com.programmersbox.scrcpyviewer.ui.settings.node
import com.programmersbox.scrcpyviewer.ui.settings.nodeContaining
import com.programmersbox.scrcpyviewer.ui.settings.nodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppsDatabaseTest {
    private val notes = DeviceApp("com.example.notes", "Notes", false, "2.4.0", 412, debuggable = true)
    private val weather = DeviceApp("io.example.weather", "Weather Lab", false, "0.9", 9, debuggable = true)
    private val chrome = DeviceApp("com.android.chrome", "Chrome", true, "131", 677)
    private val apps = listOf(notes, weather, chrome)

    private class Scene(val driver: SceneDriver, val events: MutableList<AppsEvent>, val state: () -> AppsUiState, val setState: (AppsUiState) -> Unit)

    private fun scene(root: RootState = RootState.None, width: Int = 480, filter: AppFilter = AppFilter.All, motion: Boolean = false): Scene {
        val events = mutableListOf<AppsEvent>()
        var state by mutableStateOf(AppsUiState(apps = apps, root = root, filter = filter))
        val driver = helpScene(width + 20, 760) {
            CompositionLocalProvider(LocalReduceMotion provides !motion) {
                Box(Modifier.width(width.dp).height(760.dp)) {
                    AppsTabContent(state, enabled = true) { e ->
                        events += e
                        if (e is AppsEvent.Filter) state = state.copy(filter = e.filter)
                    }
                }
            }
        }
        driver.frames(20)
        return Scene(driver, events, { state }, { state = it })
    }

    @Test
    fun `the Debuggable chip filters to debug builds`() = onEdt {
        val s = scene()
        s.driver.click("Debuggable")
        s.driver.frames(20)
        assertEquals(AppFilter.Debuggable, s.state().filter)
        assertNotNull(s.driver.node("Notes"))
        assertNull(s.driver.node("Chrome"))
        s.driver.scene.close()
    }

    @Test
    fun `debug builds carry a DEBUG badge`() = onEdt {
        val s = scene()
        assertEquals(2, s.driver.nodes().count { n -> n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)?.firstOrNull() == "Debug build" })
        s.driver.scene.close()
    }

    @Test
    fun `Data access says how the app's data is reached`() = onEdt {
        fun access(app: String, root: RootState): String? {
            val s = scene(root)
            s.driver.click(app)
            s.driver.frames(30)
            // The sheet merges its texts into one node.
            val text = listOf("Debuggable · run-as", "Release build · locked", "Through root (su)", "Through adb root", "Release build · ask for root")
                .firstOrNull { s.driver.nodeContaining(it) != null }
            s.driver.scene.close()
            return text
        }
        assertEquals("Debuggable · run-as", access("Notes", RootState.None))
        assertEquals("Release build · locked", access("Chrome", RootState.None))
        assertEquals("Through root (su)", access("Chrome", RootState.Rooted))
        assertEquals("Through adb root", access("Chrome", RootState.AdbRoot))
        assertEquals("Release build · ask for root", access("Chrome", RootState.Available))
    }

    @Test
    fun `a long Data access value scrolls as a marquee`() = onEdt {
        val d = helpScene(200, 120) {
            CompositionLocalProvider(LocalReduceMotion provides false) {
                Box(Modifier.width(150.dp)) { DataAccessBox(chrome, RootState.Available, motion = true) }
            }
        }
        d.frames(5)
        assertNotNull(d.node("Release build · ask for root"), "the full value is kept, not cut")
        val before = d.snapshot()
        d.frames(150) // the marquee starts after its delay
        val after = d.snapshot()
        var changed = 0
        for (x in 0 until 150) for (y in 0 until 60) if (before[x, y] != after[x, y]) changed++
        assertTrue(changed > 20, "the value moves: $changed px changed")
        d.scene.close()
    }

    @Test
    fun `Databases is disabled for a locked app, with the reason`() = onEdt {
        val s = scene(RootState.None)
        s.driver.click("Chrome")
        s.driver.frames(30)
        assertNotNull(s.driver.nodeContaining("Android only shares app data for debug builds or on a rooted phone"))
        s.driver.click("Databases")
        assertTrue(s.events.none { it is AppsEvent.ShowDatabases })
        s.driver.scene.close()
    }

    @Test
    fun `Databases opens the app's databases`() = onEdt {
        val s = scene()
        s.driver.click("Notes")
        s.driver.frames(30)
        s.driver.click("Databases")
        assertEquals(AppsEvent.ShowDatabases(notes), s.events.filterIsInstance<AppsEvent.ShowDatabases>().single())
        s.driver.scene.close()
    }

    @Test
    fun `a rooted phone opens a release app through root`() = onEdt {
        val s = scene(RootState.Rooted)
        s.driver.click("Chrome")
        s.driver.frames(30)
        assertNotNull(s.driver.nodeContaining("Opens through root"))
        s.driver.click("Databases")
        assertEquals(AppsEvent.ShowDatabases(chrome), s.events.filterIsInstance<AppsEvent.ShowDatabases>().single())
        s.driver.scene.close()
    }
}
