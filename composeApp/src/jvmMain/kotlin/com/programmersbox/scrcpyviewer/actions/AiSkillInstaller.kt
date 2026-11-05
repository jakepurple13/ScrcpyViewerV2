package com.programmersbox.scrcpyviewer.actions

import java.nio.file.Files
import java.nio.file.Path

/** An AI tool that reads skills from a folder under the user's home. */
enum class AiTool(val label: String, val skillsDir: String, val markerDir: String) {
    Claude("Claude Code", ".claude/skills", ".claude"),
    Gemini("Gemini CLI", ".gemini/skills", ".gemini"),
    Codex("Codex", ".agents/skills", ".codex"),
    Grok("Grok CLI", ".grok/skills", ".grok"),
}

/** Where the skill gets installed: a known tool's skills folder, or a folder the user picked. */
sealed interface SkillTarget {
    val label: String

    /** `<skills dir>/scrcpy-vwr-actions`; holds `SKILL.md`. */
    val skillDir: Path

    data class Tool(val tool: AiTool, val home: Path) : SkillTarget {
        override val label: String get() = tool.label
        override val skillDir: Path get() = home.resolve(tool.skillsDir).resolve(AiSkill.FOLDER)
    }

    data class Folder(val dir: Path) : SkillTarget {
        override val label: String get() = dir.fileName?.toString() ?: dir.toString()
        override val skillDir: Path get() = dir.resolve(AiSkill.FOLDER)
    }
}

enum class SkillStatus { NotInstalled, UpToDate, UpdateAvailable }

/** Writes the rendered skill into AI tools' skills folders and reports whether each install is current. */
class AiSkillInstaller(private val home: Path, private val inbox: Path, private val actionsFile: Path) {
    fun targets(customFolders: List<String>): List<SkillTarget> =
        AiTool.entries.map { SkillTarget.Tool(it, home) } + customFolders.map { SkillTarget.Folder(Path.of(it)) }

    fun isFound(tool: AiTool): Boolean = Files.isDirectory(home.resolve(tool.markerDir))

    fun status(target: SkillTarget): SkillStatus {
        val file = target.skillDir.resolve(SKILL_FILE)
        val text = runCatching { Files.readString(file) }.getOrNull() ?: return SkillStatus.NotInstalled
        val version = AiSkill.installedVersion(text)
        val installedInbox = AiSkill.installedInbox(text)
        val current = version != null && version >= AiSkill.VERSION &&
            installedInbox?.toAbsolutePath()?.normalize() == inbox.toAbsolutePath().normalize()
        return if (current) SkillStatus.UpToDate else SkillStatus.UpdateAvailable
    }

    fun install(target: SkillTarget): Result<Unit> = runCatching {
        Files.createDirectories(target.skillDir)
        Files.writeString(target.skillDir.resolve(SKILL_FILE), AiSkill.render(inbox, actionsFile))
        Unit
    }

    private companion object {
        const val SKILL_FILE = "SKILL.md"
    }
}
