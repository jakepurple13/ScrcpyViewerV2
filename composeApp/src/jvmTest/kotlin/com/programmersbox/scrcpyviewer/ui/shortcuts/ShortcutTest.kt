package com.programmersbox.scrcpyviewer.ui.shortcuts

import java.awt.event.KeyEvent as K
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShortcutTest {
    private val g = K.VK_G

    @Test
    fun `encode and decode round trip`() {
        val s = Shortcut(g, ctrl = true, alt = true, shift = true, meta = true)
        assertEquals(s, Shortcut.decode(s.encode()))
        assertEquals(Shortcut(K.VK_F4, alt = true), Shortcut.decode(Shortcut(K.VK_F4, alt = true).encode()))
    }

    @Test
    fun `decode rejects garbage`() {
        listOf("", "G", "X:71", "MA:71", "AA:71", "A:", "A:abc", "A:0", "A:71:2").forEach { assertNull(Shortcut.decode(it), it) }
    }

    @Test
    fun `labels per os`() {
        assertEquals("⌥⌘G", Shortcut(g, alt = true, meta = true).label(mac = true))
        assertEquals("⌃⌥⇧⌘G", Shortcut(g, true, true, true, true).label(mac = true))
        assertEquals("Ctrl+Alt+G", Shortcut(g, ctrl = true, alt = true).label(mac = false))
        assertEquals("Ctrl+Alt+Shift+G", Shortcut(g, true, true, true).label(mac = false))
    }

    @Test
    fun `needs a modifier`() {
        assertEquals(ShortcutProblem.NeedsModifier, validate(Shortcut(g), mac = true))
        assertEquals(ShortcutProblem.NeedsModifier, validate(Shortcut(g, shift = true), mac = true))
        assertEquals(ShortcutProblem.NeedsModifier, validate(Shortcut(g, shift = true), mac = false))
        assertEquals(ShortcutProblem.WinKey, validate(Shortcut(g, meta = true), mac = false))
        assertEquals(ShortcutProblem.NeedsModifier, validate(Shortcut(K.VK_CONTROL, ctrl = true), mac = false))
        assertNull(validate(Shortcut(g, alt = true, meta = true), mac = true))
        assertNull(validate(Shortcut(g, ctrl = true, alt = true), mac = false))
    }

    @Test
    fun `system chords are reserved`() {
        assertEquals(ShortcutProblem.Reserved, validate(Shortcut(K.VK_Q, meta = true), mac = true))
        assertEquals(ShortcutProblem.Reserved, validate(Shortcut(K.VK_V, meta = true), mac = true))
        assertEquals(ShortcutProblem.Reserved, validate(Shortcut(K.VK_V, ctrl = true), mac = false))
        assertEquals(ShortcutProblem.Reserved, validate(Shortcut(K.VK_F4, alt = true), mac = false))
        assertEquals(ShortcutProblem.Reserved, validate(Shortcut(K.VK_DELETE, ctrl = true, alt = true), mac = false))
    }

    @Test
    fun `zoom keys and extras are reserved`() {
        assertEquals(ShortcutProblem.Reserved, validate(Shortcut(K.VK_0, meta = true), mac = true))
        assertEquals(ShortcutProblem.Reserved, validate(Shortcut(K.VK_MINUS, ctrl = true), mac = false))
        val extra = Shortcut(g, alt = true)
        assertEquals(ShortcutProblem.Reserved, validate(extra, mac = false, extraReserved = setOf(extra)))
    }

    @Test
    fun `conflict names the owner`() {
        val s = Shortcut(g, alt = true, meta = true)
        val map = mapOf(ShortcutAction.ArrangeGrid.id to s.encode())
        assertEquals(ShortcutAction.ArrangeGrid, conflict(map, ShortcutAction.Follow, s))
        assertNull(conflict(map, ShortcutAction.ArrangeGrid, s))
        assertNull(conflict(emptyMap(), ShortcutAction.Follow, s))
    }

    @Test
    fun `key names read naturally`() {
        fun l(k: Int, mac: Boolean) = Shortcut(k, ctrl = true).label(mac)
        assertEquals("⌃↑", l(K.VK_UP, true)); assertEquals("Ctrl+Up", l(K.VK_UP, false))
        assertEquals("⌃Space", l(K.VK_SPACE, true)); assertEquals("Ctrl+Space", l(K.VK_SPACE, false))
        assertEquals("⌃↩", l(K.VK_ENTER, true)); assertEquals("Ctrl+Enter", l(K.VK_ENTER, false))
        assertEquals("⌃⎋", l(K.VK_ESCAPE, true)); assertEquals("Ctrl+Esc", l(K.VK_ESCAPE, false))
        assertEquals("⌃⌫", l(K.VK_BACK_SPACE, true)); assertEquals("Ctrl+Backspace", l(K.VK_BACK_SPACE, false))
        assertEquals("⌃⌦", l(K.VK_DELETE, true)); assertEquals("Ctrl+Delete", l(K.VK_DELETE, false))
        assertEquals("⌃⇥", l(K.VK_TAB, true)); assertEquals("Ctrl+Tab", l(K.VK_TAB, false))
        assertEquals("Ctrl+,", l(K.VK_COMMA, false)); assertEquals("⌃[", l(K.VK_OPEN_BRACKET, true))
        assertEquals("Ctrl+`", l(K.VK_BACK_QUOTE, false)); assertEquals("Ctrl+=", l(K.VK_EQUALS, false))
        assertEquals("Ctrl+Num 1", l(K.VK_NUMPAD1, false)); assertEquals("⌃Num 0", l(K.VK_NUMPAD0, true))
        assertEquals("Ctrl+F1", l(K.VK_F1, false)); assertEquals("⌃F12", l(K.VK_F12, true))
        assertEquals("⌃F13", l(K.VK_F13, true)); assertEquals("Ctrl+F24", l(K.VK_F24, false))
        assertEquals("Win+G", Shortcut(g, meta = true).label(mac = false))
    }

    @Test
    fun `shift command q logs out and is reserved on mac`() {
        assertEquals(ShortcutProblem.Reserved, validate(Shortcut(K.VK_Q, shift = true, meta = true), mac = true))
    }

    @Test
    fun `altgr is refused off mac`() {
        val s = Shortcut(g, ctrl = true, alt = true)
        assertEquals(ShortcutProblem.AltGr, validate(s, mac = false, altGr = true))
        assertNull(validate(s, mac = false, altGr = false))
    }

    @Test
    fun `windows key is refused off mac`() {
        assertEquals(ShortcutProblem.WinKey, validate(Shortcut(g, ctrl = true, meta = true), mac = false))
    }

    @Test
    fun `a loaded value must still be validated`() {
        val loaded = Shortcut.decode(":71")
        assertEquals(Shortcut(71), loaded)
        assertEquals(ShortcutProblem.NeedsModifier, validate(loaded!!, mac = true))
    }

    @Test
    fun `shift command g is valid on mac`() {
        assertNull(validate(Shortcut(g, shift = true, meta = true), mac = true))
    }
}
