package com.programmersbox.scrcpyviewer.ui.mirror

import com.programmersbox.scrcpyviewer.actions.RunOnceAction
import com.programmersbox.scrcpyviewer.actions.ToggleAction
import com.programmersbox.scrcpyviewer.actions.Variable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TileStateLineTest {
    @Test
    fun `run once with a variable asks for its label`() {
        val action = RunOnceAction(
            id = "clear", name = "Clear data", command = "pm clear {pkg}", confirm = true,
            variables = mapOf("pkg" to Variable(label = "App to clear")),
        )
        assertEquals("Asks for App to clear", tileStateLine(action, null))
    }

    @Test
    fun `first variable in the command wins and a missing label falls back to the name`() {
        val action = RunOnceAction(
            id = "x", name = "X", command = "echo {b} {a}", variables = mapOf("a" to Variable(label = "A label")),
        )
        assertEquals("Asks for b", tileStateLine(action, null))
    }

    @Test
    fun `confirm without variables asks first`() {
        assertEquals("Asks first", tileStateLine(RunOnceAction(id = "r", name = "R", command = "ls", confirm = true), null))
    }

    @Test
    fun `plain run once has no state line`() {
        assertNull(tileStateLine(RunOnceAction(id = "r", name = "R", command = "ls"), null))
    }

    @Test
    fun `toggle shows on off or nothing`() {
        val toggle = ToggleAction(id = "t", name = "T", on = "a", off = "b")
        assertEquals("On", tileStateLine(toggle, true))
        assertEquals("Off", tileStateLine(toggle, false))
        assertNull(tileStateLine(toggle, null))
    }
}
