package com.programmersbox.scrcpyviewer.actions

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiSkillTest {
    private val inbox = Path.of("/tmp/data/actions-inbox")
    private val actionsFile = Path.of("/tmp/data/actions.json")
    private val rendered = AiSkill.render(inbox, actionsFile)

    @Test
    fun `render leaves no placeholders`() {
        // Literal `{{` stays: the skill documents it as the escape for a brace in commands.
        val placeholder = Regex("""\{\{[A-Z_]+}}""")
        assertFalse(placeholder.containsMatchIn(rendered), "unfilled placeholder: ${placeholder.find(rendered)?.value}")
    }

    @Test
    fun `render lists every icon in group order`() {
        val names = ActionIcons.groups.flatMap { it.icons }.map { it.name }
        assertTrue(names.joinToString(", ") in rendered)
    }

    @Test
    fun `frontmatter has name description and version`() {
        assertTrue(rendered.startsWith("---\nname: scrcpy-vwr-actions\n"))
        val frontmatter = rendered.substringAfter("---\n").substringBefore("\n---\n")
        assertTrue(frontmatter.lines().any { it.startsWith("description: ") })
        assertTrue("scrcpy-vwr-skill-version: 1" in frontmatter)
    }

    @Test
    fun `render uses LF line endings`() {
        assertFalse('\r' in rendered)
    }

    @Test
    fun `installedVersion and installedInbox read back what render wrote`() {
        assertEquals(AiSkill.VERSION, AiSkill.installedVersion(rendered))
        assertEquals(inbox, AiSkill.installedInbox(rendered))
    }

    @Test
    fun `inbox marker line appears exactly once`() {
        assertEquals(1, rendered.lines().count { it.startsWith("Inbox folder: ") })
    }

    @Test
    fun `installedVersion and installedInbox are null for unrelated text`() {
        assertNull(AiSkill.installedVersion("---\nname: other\n---\n"))
        assertNull(AiSkill.installedInbox("---\nname: other\n---\n"))
    }

    @Test
    fun `paths with spaces are quoted in commands`() {
        val spaced = Path.of("/tmp/Jo Smith/actions-inbox")
        val text = AiSkill.render(spaced, Path.of("/tmp/Jo Smith/actions.json"))
        // Path.toString uses the OS separator (backslashes on Windows).
        assertTrue("\"$spaced\"" in text)
        assertEquals(spaced, AiSkill.installedInbox(text))
    }

    @Test
    fun `actions file path appears in the skill`() {
        assertTrue(actionsFile.toString() in rendered)
    }

    @Test
    fun `template loads from classpath`() {
        assertNotNull(AiSkill::class.java.getResource("/ai-skill/SKILL.md"))
    }
}
