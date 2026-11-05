package com.programmersbox.scrcpyviewer.actions

import java.nio.file.Path

/** The bundled AI skill (`ai-skill/SKILL.md`) that teaches AI coding CLIs to write actions into the inbox. */
object AiSkill {
    const val FOLDER = "scrcpy-vwr-actions"
    const val VERSION = 1

    private const val INBOX_MARKER = "Inbox folder: "
    private val versionLine = Regex("""^\s*scrcpy-vwr-skill-version:\s*(\d+)\s*$""", RegexOption.MULTILINE)

    private val template: String by lazy {
        val url = checkNotNull(AiSkill::class.java.getResource("/ai-skill/SKILL.md")) { "ai-skill/SKILL.md missing" }
        url.readText()
    }

    /** The skill text with this app's [inbox], [actionsFile], skill version and icon names filled in. */
    // A Windows checkout (core.autocrlf) bundles the template with CRLF; the installed skill is always LF.
    fun render(inbox: Path, actionsFile: Path): String = template
        .replace("\r\n", "\n")
        .replace("{{SKILL_VERSION}}", VERSION.toString())
        .replace("{{INBOX_DIR}}", inbox.toString())
        .replace("{{ACTIONS_FILE}}", actionsFile.toString())
        .replace("{{ICONS}}", ActionIcons.groups.flatMap { it.icons }.joinToString(", ") { it.name })

    /** The skill version in an installed SKILL.md's frontmatter, or null when absent. */
    fun installedVersion(text: String): Int? =
        versionLine.find(frontmatter(text))?.groupValues?.get(1)?.toIntOrNull()

    /** The inbox path an installed SKILL.md was rendered with, or null when absent. */
    fun installedInbox(text: String): Path? = text.lineSequence()
        .firstOrNull { it.startsWith(INBOX_MARKER) }
        ?.removePrefix(INBOX_MARKER)
        ?.trimEnd()
        ?.takeIf { it.isNotEmpty() }
        ?.let { runCatching { Path.of(it) }.getOrNull() }

    private fun frontmatter(text: String): String {
        val normalized = text.replace("\r\n", "\n")
        if (!normalized.startsWith("---\n")) return ""
        return normalized.removePrefix("---\n").substringBefore("\n---", missingDelimiterValue = "")
    }
}
