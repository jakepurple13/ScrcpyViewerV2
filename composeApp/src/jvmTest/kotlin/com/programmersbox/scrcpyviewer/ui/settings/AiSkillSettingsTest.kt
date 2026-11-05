package com.programmersbox.scrcpyviewer.ui.settings

import com.programmersbox.scrcpyviewer.actions.AiSkillInstaller
import com.programmersbox.scrcpyviewer.actions.AiTool
import com.programmersbox.scrcpyviewer.actions.SkillStatus
import com.programmersbox.scrcpyviewer.actions.SkillTarget
import com.programmersbox.scrcpyviewer.presets.PresetStore
import com.programmersbox.scrcpyviewer.state.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiSkillSettingsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val dir = Files.createTempDirectory("ai-skill-settings")
    private val home = Files.createDirectory(dir.resolve("home"))
    private val inbox = dir.resolve("inbox")
    private val installer = AiSkillInstaller(home, inbox, dir.resolve("actions.json"))
    private val settings = AppSettings(dir.resolve("settings.json"))

    private fun vm() = SettingsViewModel(
        settings, PresetStore(dir.resolve("presets.json"), scope),
        object : SettingsAdb {
            override fun bundledAdb(): Path = dir.resolve("adb")
            override suspend fun version(path: Path): String? = null
            override fun restartTracker() = Unit
        },
        installer, scope,
    )

    private fun SettingsViewModel.awaitResult(): List<Pair<String, String?>> = runBlocking {
        withTimeout(10_000) { state.first { it.aiInstallResult != null }.aiInstallResult!! }
    }

    /** Rows loaded on IO; waits until non-empty and [ready]. */
    private fun SettingsViewModel.awaitRows(ready: (List<AiSkillRow>) -> Boolean = { true }): List<AiSkillRow> = runBlocking {
        withTimeout(10_000) { state.first { it.aiSkill.isNotEmpty() && ready(it.aiSkill) }.aiSkill }
    }

    private fun tool(t: AiTool) = SkillTarget.Tool(t, home)

    private fun List<AiSkillRow>.of(t: AiTool) = first { it.target == tool(t) }

    @Test
    fun `found tools are pre-checked`() {
        Files.createDirectories(home.resolve(".claude"))
        Files.createDirectories(home.resolve(".codex"))
        val rows = vm().awaitRows()
        assertEquals(setOf(tool(AiTool.Claude), tool(AiTool.Codex)), preChecked(rows))
        assertFalse(rows.of(AiTool.Gemini).found)
    }

    @Test
    fun `add button counts checked tools`() {
        Files.createDirectories(home.resolve(".claude"))
        Files.createDirectories(home.resolve(".gemini"))
        val rows = vm().awaitRows()
        assertEquals("Add to 2 tools", addButtonLabel(preChecked(rows).size))
        assertEquals("Add to 1 tool", addButtonLabel(1))
        assertEquals("Add to 0 tools", addButtonLabel(0))
    }

    @Test
    fun `install shows Done dialog with results`() {
        val vm = vm()
        vm.awaitRows()
        val blocker = Files.writeString(dir.resolve("file"), "x")
        vm.installAiSkill(listOf(tool(AiTool.Claude), SkillTarget.Folder(blocker)))
        val result = vm.awaitResult()
        assertEquals(listOf("Claude Code", "file"), result.map { it.first })
        assertNull(result[0].second)
        assertNotNull(result[1].second)
        assertEquals("Some installs failed", installResultTitle(result))
        assertEquals("Skill added", installResultTitle(listOf("Claude Code" to null)))
        assertEquals("✓ Claude Code", installResultLine("Claude Code", null))
        assertTrue(Files.exists(tool(AiTool.Claude).skillDir.resolve("SKILL.md")))
        assertEquals(SkillStatus.UpToDate, vm.state.value.aiSkill.of(AiTool.Claude).status)
        vm.dismissAiInstallResult()
        assertNull(vm.state.value.aiInstallResult)
    }

    @Test
    fun `update available reinstalls`() {
        val skill = tool(AiTool.Gemini).skillDir.resolve("SKILL.md")
        Files.createDirectories(skill.parent)
        Files.writeString(skill, "---\nname: scrcpy-vwr-actions\nmetadata:\n  scrcpy-vwr-skill-version: 0\n---\nold")
        val vm = vm()
        val before = vm.awaitRows().of(AiTool.Gemini)
        assertEquals(SkillStatus.UpdateAvailable, before.status)
        vm.installAiSkill(listOf(before.target))
        vm.awaitResult()
        assertEquals(SkillStatus.UpToDate, vm.state.value.aiSkill.of(AiTool.Gemini).status)
    }

    @Test
    fun `added folder becomes a pre-checked row`() {
        val vm = vm()
        vm.awaitRows()
        val folder = Files.createDirectory(dir.resolve("custom"))
        vm.addAiSkillFolder(folder)
        val rows = vm.awaitRows { r -> r.any { it.target is SkillTarget.Folder } }
        assertTrue(SkillTarget.Folder(folder) in preChecked(rows))
    }

    @Test
    fun `home prefix is shown as tilde`() {
        assertEquals("~/.claude/skills/x", abbreviateHome("/Users/a/.claude/skills/x", "/Users/a"))
        assertEquals("/opt/x", abbreviateHome("/opt/x", "/Users/a"))
        assertEquals("/Users/ab/x", abbreviateHome("/Users/ab/x", "/Users/a"))
    }
}
