package com.programmersbox.scrcpyviewer.ui.mirror.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** The macOS file-promise items, without AppKit: what stays for Finder's writes and what goes at the drag's end. */
class PromiseRegistryTest {
    private val registry = PromiseRegistry<String>()

    @Test
    fun `each item gets its own key, in order, and each drag its own id`() {
        val (a, keys) = registry.register(listOf("a.jpg", "Camera"))
        val (b, more) = registry.register(listOf("a.jpg"))
        assertNotEquals(a, b)
        assertEquals(3, (keys + more).toSet().size)
        assertEquals(listOf("a.jpg", "Camera"), keys.map { registry[it] })
    }

    @Test
    fun `a drag dropped nowhere or cancelled lets go of its items at once`() {
        val (drag, keys) = registry.register(listOf("a.jpg", "b.jpg"))
        registry.ended(drag, dropped = false)
        assertEquals(0, registry.size())
        assertNull(registry[keys[0]])
    }

    @Test
    fun `a dropped drag keeps its items for writes that come after the end, each going once written`() {
        val (drag, keys) = registry.register(listOf("a.jpg", "b.jpg"))
        registry.ended(drag, dropped = true)
        assertEquals("a.jpg", registry[keys[0]], "Finder asks after the drag ended")
        registry.written(keys[0])
        assertNull(registry[keys[0]])
        assertEquals("b.jpg", registry[keys[1]], "a later write still finds its item")
        registry.written(keys[1])
        assertEquals(0, registry.size())
    }

    @Test
    fun `ending one drag leaves another drag's items alone`() {
        val (first, firstKeys) = registry.register(listOf("a.jpg"))
        registry.ended(first, dropped = true)
        val (second, _) = registry.register(listOf("b.jpg", "c.jpg"))
        registry.ended(second, dropped = false)
        assertEquals(1, registry.size())
        assertEquals("a.jpg", registry[firstKeys[0]], "the dropped drag's write is still to come")
    }
}
