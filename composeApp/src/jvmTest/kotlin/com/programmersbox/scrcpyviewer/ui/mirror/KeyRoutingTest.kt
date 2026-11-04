package com.programmersbox.scrcpyviewer.ui.mirror

import java.awt.event.KeyEvent as AwtKeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeyRoutingTest {
    private val editingKeys = listOf(
        AwtKeyEvent.VK_BACK_SPACE, AwtKeyEvent.VK_DELETE, AwtKeyEvent.VK_ENTER, AwtKeyEvent.VK_TAB,
        AwtKeyEvent.VK_LEFT, AwtKeyEvent.VK_RIGHT, AwtKeyEvent.VK_UP, AwtKeyEvent.VK_DOWN,
        AwtKeyEvent.VK_HOME, AwtKeyEvent.VK_END, AwtKeyEvent.VK_A,
    )

    @Test
    fun `editing keys typed in the focused sheet go to the sheet`() {
        editingKeys.forEach { assertEquals(KeyTarget.Sheet, keyTarget(sheetOpen = true, sheetFocused = true, keyCode = it), "key $it") }
    }

    @Test
    fun `paste and zoom shortcuts in the focused sheet go to the sheet`() {
        // The modifiers do not matter: Cmd/Ctrl+V pastes into the field, Cmd/Ctrl+= types or does nothing there.
        listOf(AwtKeyEvent.VK_V, AwtKeyEvent.VK_EQUALS, AwtKeyEvent.VK_MINUS, AwtKeyEvent.VK_0, AwtKeyEvent.VK_META, AwtKeyEvent.VK_CONTROL)
            .forEach { assertEquals(KeyTarget.Sheet, keyTarget(sheetOpen = true, sheetFocused = true, keyCode = it), "key $it") }
    }

    @Test
    fun `Esc in the focused sheet goes to the sheet, which clears a field or closes`() {
        assertEquals(KeyTarget.Sheet, keyTarget(sheetOpen = true, sheetFocused = true, keyCode = AwtKeyEvent.VK_ESCAPE))
    }

    @Test
    fun `Esc with the sheet open but focus on the stage closes the sheet`() {
        assertEquals(KeyTarget.Window, keyTarget(sheetOpen = true, sheetFocused = false, keyCode = AwtKeyEvent.VK_ESCAPE))
    }

    @Test
    fun `keys with focus outside the sheet keep going to the device`() {
        (editingKeys + AwtKeyEvent.VK_V + AwtKeyEvent.VK_EQUALS).forEach {
            assertEquals(KeyTarget.Device, keyTarget(sheetOpen = true, sheetFocused = false, keyCode = it), "open, key $it")
            assertEquals(KeyTarget.Device, keyTarget(sheetOpen = false, sheetFocused = false, keyCode = it), "closed, key $it")
        }
        assertEquals(KeyTarget.Device, keyTarget(sheetOpen = false, sheetFocused = false, keyCode = AwtKeyEvent.VK_ESCAPE))
    }

    @Test
    fun `a closed sheet never holds the keys, even with stale focus`() {
        assertEquals(KeyTarget.Device, keyTarget(sheetOpen = false, sheetFocused = true, keyCode = AwtKeyEvent.VK_BACK_SPACE))
    }

    @Test
    fun `sheet focus is held while any part has focus`() {
        val focus = SheetFocus()
        val panel = Any()
        val dialog = Any()
        assertFalse(focus.focused)
        focus.update(panel, true)
        assertTrue(focus.focused)
        // A prompt opening moves focus from the panel's field to the dialog's: either order keeps the sheet focused.
        focus.update(dialog, true)
        focus.update(panel, false)
        assertTrue(focus.focused)
        focus.update(dialog, false)
        assertFalse(focus.focused)
    }

    @Test
    fun `sheet focus reports gaining it once, for releasing keys the device holds`() {
        val focus = SheetFocus()
        val gained = mutableListOf<Unit>()
        focus.onGained = { gained += Unit }
        focus.update("a", true)
        focus.update("b", true)
        focus.update("a", false)
        focus.update("b", false)
        focus.update("a", true)
        assertEquals(2, gained.size)
    }
}
