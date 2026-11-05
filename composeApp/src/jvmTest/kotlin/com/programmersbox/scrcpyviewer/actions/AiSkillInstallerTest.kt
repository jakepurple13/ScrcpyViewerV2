package com.programmersbox.scrcpyviewer.actions

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiSkillInstallerTest {
    private val home = Files.createTempDirectory("home")
    private val inbox = home.resolve("data/actions-inbox")
    private val actions = home.resolve("data/actions.json")
    private val installer = AiSkillInstaller(home, inbox, actions)

    @Test
    fun `found only when marker dir exists`() {
        Files.createDirectories(home.resolve(".gemini"))
        assertTrue(installer.isFound(AiTool.Gemini))
        assertFalse(installer.isFound(AiTool.Codex))
    }

    @Test
    fun `codex installs to agents skills`() {
        assertTrue(installer.install(SkillTarget.Tool(AiTool.Codex, home)).isSuccess)
        assertTrue(Files.exists(home.resolve(".agents/skills/scrcpy-vwr-actions/SKILL.md")))
    }

    @Test
    fun `targets list tools in order then custom folders`() {
        val t = installer.targets(listOf(home.resolve("mine").toString()))
        assertEquals(listOf("Claude Code", "Gemini CLI", "Codex", "Grok CLI", "mine"), t.map { it.label })
    }

    @Test
    fun `status not installed then up to date`() {
        val target = SkillTarget.Tool(AiTool.Claude, home)
        assertEquals(SkillStatus.NotInstalled, installer.status(target))
        installer.install(target).getOrThrow()
        assertEquals(SkillStatus.UpToDate, installer.status(target))
    }

    @Test
    fun `older version is update available`() {
        val target = SkillTarget.Tool(AiTool.Claude, home)
        installer.install(target).getOrThrow()
        val file = target.skillDir.resolve("SKILL.md")
        Files.writeString(file, Files.readString(file).replace(Regex("scrcpy-vwr-skill-version: \\d+"), "scrcpy-vwr-skill-version: 0"))
        assertEquals(SkillStatus.UpdateAvailable, installer.status(target))
    }

    @Test
    fun `different inbox path is update available`() {
        val target = SkillTarget.Tool(AiTool.Claude, home)
        AiSkillInstaller(home, home.resolve("elsewhere"), actions).install(target).getOrThrow()
        assertEquals(SkillStatus.UpdateAvailable, installer.status(target))
    }

    @Test
    fun `install replaces existing SKILL md`() {
        val target = SkillTarget.Tool(AiTool.Grok, home)
        Files.createDirectories(target.skillDir)
        Files.writeString(target.skillDir.resolve("SKILL.md"), "old")
        installer.install(target).getOrThrow()
        assertEquals(SkillStatus.UpToDate, installer.status(target))
        assertFalse(Files.readString(target.skillDir.resolve("SKILL.md")) == "old")
    }

    @Test
    fun `folder target installs into subfolder`() {
        val dir = home.resolve("picked")
        val target = SkillTarget.Folder(dir)
        installer.install(target).getOrThrow()
        assertTrue(Files.exists(dir.resolve("scrcpy-vwr-actions/SKILL.md")))
        assertEquals("picked", target.label)
    }

    @Test
    fun `install into unwritable folder returns failure`() {
        val dir = Files.createDirectories(home.resolve("ro"))
        dir.toFile().setWritable(false)
        try {
            if (dir.toFile().canWrite()) return
            assertTrue(installer.install(SkillTarget.Folder(dir)).isFailure)
        } finally {
            dir.toFile().setWritable(true)
        }
    }
}
