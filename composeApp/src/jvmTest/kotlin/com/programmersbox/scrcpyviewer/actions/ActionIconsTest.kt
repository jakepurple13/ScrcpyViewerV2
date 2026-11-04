package com.programmersbox.scrcpyviewer.actions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ActionIconsTest {
    @Test
    fun `has six groups of eight with spec titles`() {
        val groups = ActionIcons.groups
        assertEquals(
            listOf("Connectivity", "Device", "Display and input", "Apps", "Debug", "General"),
            groups.map { it.title },
        )
        assertTrue(groups.all { it.icons.size == 8 })
    }

    @Test
    fun `all 48 names are distinct`() {
        assertEquals(48, ActionIcons.groups.flatMap { it.icons }.map { it.name }.toSet().size)
    }

    @Test
    fun `unknown name falls back to default`() {
        assertSame(ActionIcons.forName("play_arrow", "play_arrow"), ActionIcons.forName("nope", "play_arrow"))
        assertSame(ActionIcons.forName("play_arrow", "play_arrow"), ActionIcons.forName(null, "play_arrow"))
    }

    @Test
    fun `search matches keywords`() {
        assertTrue(ActionIcons.search("logs").flatMap { it.icons }.any { it.name == "terminal" })
    }

    @Test
    fun `search matches names and drops empty groups`() {
        val result = ActionIcons.search("bat")
        assertEquals(listOf("battery_full", "battery_saver"), result.flatMap { it.icons }.map { it.name })
        assertEquals(1, result.size)
    }

    @Test
    fun `blank search returns all groups`() {
        assertEquals(ActionIcons.groups, ActionIcons.search("  "))
    }
}
