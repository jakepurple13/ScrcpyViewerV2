package com.programmersbox.scrcpyviewer.actions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActionGroupsTest {
    private fun run(id: String, source: ActionSource) = RunOnceAction(id, id, command = "echo $id").withSource(source)

    @Test
    fun `ai actions show under Made by AI and stay out of Your actions`() {
        val groups = ActionGroups.of(listOf(run("a", ActionSource.Ai), run("u", ActionSource.User), run("b", ActionSource.Ai)))
        assertEquals(listOf("u"), groups.yours.map { it.id })
        assertEquals(listOf("a", "b"), groups.madeByAi.map { it.id })
        assertTrue(groups.showAiSection)
        assertTrue(groups.showYoursHeader)
    }

    @Test
    fun `no AI section without AI actions`() {
        val groups = ActionGroups.of(listOf(run("u1", ActionSource.User), run("u2", ActionSource.User)))
        assertEquals(listOf("u1", "u2"), groups.yours.map { it.id })
        assertFalse(groups.showAiSection)
        assertFalse(groups.showYoursHeader)
        assertFalse(ActionGroups.of(emptyList()).showAiSection)
    }

    @Test
    fun `only AI actions shows the AI header and no Your actions header`() {
        val groups = ActionGroups.of(listOf(run("a", ActionSource.Ai)))
        assertTrue(groups.showAiSection)
        assertFalse(groups.showYoursHeader)
        assertTrue(groups.yours.isEmpty())
    }
}
