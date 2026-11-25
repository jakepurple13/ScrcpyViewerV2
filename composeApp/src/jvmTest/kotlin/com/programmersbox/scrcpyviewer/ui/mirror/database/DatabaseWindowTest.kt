package com.programmersbox.scrcpyviewer.ui.mirror.database

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.unit.IntSize
import com.programmersbox.scrcpyviewer.ui.common.LocalReduceMotion
import com.programmersbox.scrcpyviewer.ui.onEdt
import com.programmersbox.scrcpyviewer.ui.settings.click
import com.programmersbox.scrcpyviewer.ui.settings.helpScene
import com.programmersbox.scrcpyviewer.ui.settings.node
import com.programmersbox.scrcpyviewer.ui.settings.nodes
import androidx.compose.ui.semantics.getOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DatabaseWindowTest {
    private fun window(width: Int) = object : WindowInfo {
        override val isWindowFocused = true
        override val containerSize get() = IntSize(width, 640)
    }

    @Test
    fun `the sheet shows a card while Databases is popped out`() = onEdt {
        val (c, _) = notesController()
        c.pick(DEBUG_APP)
        var docked = 0
        c.onDock = { docked++ }
        val d = helpScene(620, 640) {
            CompositionLocalProvider(LocalReduceMotion provides true) { DatabaseSheetTab(c, sheetWidth = androidx.compose.ui.unit.Dp(620f), motion = false) {} }
        }
        d.frames(10)
        assertNull(d.node("Databases is open in its own window"))
        c.popOut()
        d.frames(10)
        assertNotNull(d.node("Databases is open in its own window"))
        d.click("Show window")
        assertEquals(1, c.raiseRequests)
        d.click("Move back here")
        d.frames(10)
        assertFalse(c.popped)
        assertEquals(1, docked)
        assertNull(d.node("Databases is open in its own window"))
        d.scene.close()
    }

    @Test
    fun `the window shows tables beside rows, without a back arrow or pop-out button`() = onEdt {
        val (c, _) = notesController()
        c.pick(DEBUG_APP)
        c.popOut()
        val d = helpScene(900, 640) {
            CompositionLocalProvider(LocalReduceMotion provides true, LocalWindowInfo provides window(900)) {
                DatabaseWindowContent(c, motion = false, shown = true, onRaise = {})
            }
        }
        d.frames(30)
        assertNotNull(d.node("TABLES"))
        assertNotNull(d.node("Grocery run"))
        assertNull(d.node("Back to list"))
        assertNull(d.nodes().firstOrNull { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)?.firstOrNull() == "Open in its own window" })
        d.scene.close()
    }

    @Test
    fun `raise requests reach the window`() = onEdt {
        val (c, _) = notesController()
        c.popOut()
        var raised = 0
        val d = helpScene(500, 400) { DatabaseWindowContent(c, motion = false, shown = true, onRaise = { raised++ }) }
        d.frames(5)
        c.raise()
        d.frames(5)
        assertEquals(1, raised)
        d.scene.close()
    }

    @Test
    fun `at 360 dp the window still shows its tables`() = onEdt {
        val (c, _) = notesController()
        c.pick(DEBUG_APP)
        val d = helpScene(360, 400) {
            CompositionLocalProvider(LocalReduceMotion provides true, LocalWindowInfo provides window(360)) {
                DatabaseWindowContent(c, motion = false, shown = true, onRaise = {})
            }
        }
        d.frames(40)
        assertNotNull(d.node("notes"))
        d.scene.close()
    }
}

