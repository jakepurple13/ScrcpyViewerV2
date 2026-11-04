package com.programmersbox.scrcpyviewer.actions

import com.programmersbox.scrcpyviewer.presets.ConflictChoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActionImportPlanTest {
    private fun t(id: String, name: String, on: String = "on") = ToggleAction(id, name, on = on, off = "off")

    private val existing = listOf(t("e1", "Fast"))

    private fun ids(vararg v: String): () -> String {
        val it = v.iterator()
        return { it.next() }
    }

    @Test
    fun `plan flags clashes and defaults to Replace`() {
        val items = ActionImportPlan.plan(existing, listOf(t("n1", "fast"), t("n2", "Other")))
        assertEquals(existing[0], items[0].conflictsWith)
        assertEquals(ConflictChoice.Replace, items[0].choice)
        assertNull(items[1].conflictsWith)
    }

    @Test
    fun `non conflicting get a new id`() {
        val out = ActionImportPlan.apply(existing, ActionImportPlan.plan(existing, listOf(t("n2", "Other"))), ids("new1"))
        assertEquals(listOf("e1", "new1"), out.map { it.id })
    }

    @Test
    fun `Replace keeps existing id`() {
        val items = ActionImportPlan.plan(existing, listOf(t("n1", "Fast", on = "z")))
        val out = ActionImportPlan.apply(existing, items, ids())
        assertEquals(listOf(t("e1", "Fast", on = "z")), out)
    }

    @Test
    fun `KeepBoth renames and gets new id`() {
        val items = ActionImportPlan.plan(existing, listOf(t("n1", "Fast"))).map { it.copy(choice = ConflictChoice.KeepBoth) }
        val out = ActionImportPlan.apply(existing, items, ids("new1"))
        assertEquals(listOf("Fast", "Fast (2)"), out.map { it.name })
        assertEquals("new1", out[1].id)
    }

    @Test
    fun `Skip and include false drop`() {
        val skip = ActionImportPlan.plan(existing, listOf(t("n1", "Fast"))).map { it.copy(choice = ConflictChoice.Skip) }
        assertEquals(existing, ActionImportPlan.apply(existing, skip, ids()))
        val off = ActionImportPlan.plan(existing, listOf(t("n2", "Other"))).map { it.copy(include = false) }
        assertEquals(existing, ActionImportPlan.apply(existing, off, ids()))
    }

    @Test
    fun `same name twice in one file adds both and renames second`() {
        val items = ActionImportPlan.plan(emptyList(), listOf(t("n1", "Dup"), t("n2", "dup", on = "2")))
        val out = ActionImportPlan.apply(emptyList(), items, ids("x1", "x2"))
        assertEquals(listOf("Dup", "dup (2)"), out.map { it.name })
        assertEquals(listOf("x1", "x2"), out.map { it.id })
    }

    @Test
    fun `a later incoming item clashing with an earlier one is flagged and defaults to Keep both`() {
        val first = t("n1", "Fast", on = "1")
        val second = t("n2", "fast", on = "2")
        val items = ActionImportPlan.plan(existing, listOf(first, second))
        assertEquals(existing[0], items[0].conflictsWith)
        assertEquals(ConflictChoice.Replace, items[0].choice)
        assertEquals(first, items[1].conflictsWith, "the earlier incoming item")
        assertTrue(items[1].inFile)
        assertEquals(ConflictChoice.KeepBoth, items[1].choice)

        // Defaults: the first replaces the existing one, the second is kept beside it.
        val out = ActionImportPlan.apply(existing, items, ids("x2"))
        assertEquals(listOf("Fast" to "1", "fast (2)" to "2"), out.map { it.name to (it as ToggleAction).on })
        assertEquals(listOf("e1", "x2"), out.map { it.id })
    }

    @Test
    fun `Replace on an in-file clash replaces the earlier incoming item`() {
        val items = ActionImportPlan.plan(existing, listOf(t("n1", "Fast", on = "1"), t("n2", "fast", on = "2")))
            .mapIndexed { i, it -> if (i == 1) it.copy(choice = ConflictChoice.Replace) else it }
        val out = ActionImportPlan.apply(existing, items, ids())
        assertEquals(listOf(t("e1", "fast", on = "2")), out)
    }

    @Test
    fun `Replace on an in-file clash whose earlier item was skipped adds it`() {
        val items = ActionImportPlan.plan(emptyList(), listOf(t("n1", "Dup"), t("n2", "Dup", on = "2")))
            .mapIndexed { i, it -> if (i == 0) it.copy(include = false) else it.copy(choice = ConflictChoice.Replace) }
        val out = ActionImportPlan.apply(emptyList(), items, ids("x1"))
        assertEquals(listOf(t("x1", "Dup", on = "2")), out)
    }
}
