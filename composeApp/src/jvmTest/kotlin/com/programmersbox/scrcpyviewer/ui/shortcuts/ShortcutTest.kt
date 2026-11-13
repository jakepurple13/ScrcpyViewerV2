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
        assertEquals(ShortcutProblem.NeedsModifier, validate(Shortcut(g, meta = true), mac = false))
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
}
