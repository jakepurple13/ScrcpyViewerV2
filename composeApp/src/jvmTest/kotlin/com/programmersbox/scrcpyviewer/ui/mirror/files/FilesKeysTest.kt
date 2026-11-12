package com.programmersbox.scrcpyviewer.ui.mirror.files

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The spec's Keyboard table, on macOS (⌘) and on Windows and Linux (Ctrl). */
@OptIn(InternalComposeUiApi::class)
class FilesKeysTest {
    private fun down(key: Key, cmd: Boolean = false, shift: Boolean = false, mac: Boolean, ctrlOnMac: Boolean = false) = KeyEvent(
        key = key,
        type = KeyEventType.KeyDown,
        isMetaPressed = cmd && mac,
        isCtrlPressed = (cmd && !mac) || ctrlOnMac,
        isShiftPressed = shift,
    )

    private fun check(mac: Boolean) {
        fun action(key: Key, cmd: Boolean = false, shift: Boolean = false) = filesKeyAction(down(key, cmd, shift, mac), mac)
        assertEquals(FilesKey.Up, action(Key.DirectionUp))
        assertEquals(FilesKey.Down, action(Key.DirectionDown))
        assertEquals(FilesKey.ExtendUp, action(Key.DirectionUp, shift = true))
        assertEquals(FilesKey.ExtendDown, action(Key.DirectionDown, shift = true))
        assertEquals(FilesKey.Enter, action(Key.Enter))
        assertEquals(FilesKey.Enter, action(Key.NumPadEnter))
        assertEquals(FilesKey.Open, action(Key.DirectionDown, cmd = true))
        assertEquals(FilesKey.Parent, action(Key.DirectionUp, cmd = true))
        assertEquals(FilesKey.Rename, action(Key.F2))
        assertEquals(FilesKey.Delete, action(Key.Backspace))
        assertEquals(FilesKey.Delete, action(Key.Delete))
        assertEquals(FilesKey.SelectAll, action(Key.A, cmd = true))
        assertEquals(FilesKey.NewFolder, action(Key.N, cmd = true, shift = true))
        assertEquals(FilesKey.Refresh, action(Key.R, cmd = true))
        assertEquals(FilesKey.Escape, action(Key.Escape))
        // Plain letters, and N without Shift, are not shortcuts.
        assertNull(action(Key.A))
        assertNull(action(Key.N, cmd = true))
        assertNull(action(Key.R))
    }

    @Test
    fun `macOS uses Command`() = check(mac = true)

    @Test
    fun `Windows and Linux use Ctrl`() = check(mac = false)

    @Test
    fun `the other platform's modifier is not the shortcut`() {
        // Ctrl+A on macOS, ⌘A (Meta) off it.
        assertNull(filesKeyAction(KeyEvent(Key.A, KeyEventType.KeyDown, isCtrlPressed = true), isMac = true))
        assertNull(filesKeyAction(KeyEvent(Key.A, KeyEventType.KeyDown, isMetaPressed = true), isMac = false))
        assertNull(filesKeyAction(down(Key.R, cmd = true, mac = true, ctrlOnMac = true), isMac = true))
    }

    @Test
    fun `key up is ignored`() {
        assertNull(filesKeyAction(KeyEvent(Key.F2, KeyEventType.KeyUp), isMac = true))
    }
}
