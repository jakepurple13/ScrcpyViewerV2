package com.programmersbox.scrcpyviewer.ui.settings

import com.programmersbox.scrcpyviewer.ui.theme.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.programmersbox.scrcpyviewer.actions.SkillTarget
import com.programmersbox.scrcpyviewer.ui.theme.CustomNeon
import com.programmersbox.scrcpyviewer.ui.theme.CustomPalette
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import com.programmersbox.scrcpyviewer.ui.theme.ThemePalette
import com.programmersbox.scrcpyviewer.ui.theme.ThemeStyle
import com.programmersbox.scrcpyviewer.ui.update.UpdateUiState
import com.programmersbox.scrcpyviewer.ui.update.UpdatesContent

enum class SettingsCategoryId { Appearance, Mirroring, Adb, Ai, Updates, Help }

/** [Inline] puts the control right of the text; [Block] puts it full-width below. */
enum class EntryLayout { Inline, Block }

/** Everything an entry's control can read or call. */
class SettingsBindings(
    val state: SettingsUiState,
    val updates: UpdateUiState,
    val actions: SettingsActions,
)

/** All Settings callbacks; every one defaults to a no-op so tests and previews pass only what they need. */
class SettingsActions(
    val onTheme: (ThemeMode) -> Unit = {},
    val onPalette: (ThemePalette) -> Unit = {},
    val onPureBlack: (Boolean) -> Unit = {},
    val onCustomPalette: (CustomPalette) -> Unit = {},
    val onNeon: (Boolean) -> Unit = {},
    val onCustomNeon: (CustomNeon) -> Unit = {},
    val onStyle: (ThemeStyle) -> Unit = {},
    val onRetroPalette: (Boolean) -> Unit = {},
    val onDesktopBlur: (Boolean) -> Unit = {},
    val onGlassOpacity: (Float) -> Unit = {},
    val onDefaultPreset: (String?) -> Unit = {},
    val onAnimateMirrorWindows: (Boolean) -> Unit = {},
    val onLaunchQuitAnimation: (com.programmersbox.scrcpyviewer.state.LaunchQuitAnimation) -> Unit = {},
    val onUseBundledAdb: () -> Unit = {},
    val onUseCustomAdb: (String) -> Unit = {},
    val onBrowseAdb: () -> Unit = {},
    val onBrowseCaptureDir: () -> Unit = {},
    val onShowOnboarding: () -> Unit = {},
    val onCheckUpdates: () -> Unit = {},
    val onUpdate: () -> Unit = {},
    val onCancelUpdate: () -> Unit = {},
    val onOpenAiSkill: () -> Unit = {},
    val onInstallAiSkill: (List<SkillTarget>) -> Unit = {},
    val onChooseAiSkillFolder: () -> Unit = {},
    val onDismissAiInstall: () -> Unit = {},
    val onSelectCategory: (SettingsCategoryId) -> Unit = {},
    val onQuery: (String) -> Unit = {},
)

/**
 * One setting. [keywords] are searched but never shown; [notes] are conditional hints shown under the description;
 * [rowModifier] makes the whole row act as the control (e.g. a switch row toggled by clicking its label).
 */
class SettingEntry(
    val id: String,
    val title: String,
    val description: String? = null,
    val keywords: List<String> = emptyList(),
    val layout: EntryLayout = EntryLayout.Inline,
    val notes: @Composable (SettingsBindings) -> Unit = {},
    val rowModifier: @Composable (SettingsBindings) -> Modifier = { Modifier },
    val content: @Composable (SettingsBindings) -> Unit = {},
)

class SettingsCategory(val id: SettingsCategoryId, val title: String, val entries: List<SettingEntry>)

/** Every Settings category and setting. The list pane, the detail pane and search all read this. */
val settingsCategories: List<SettingsCategory> = listOf(
    SettingsCategory(
        SettingsCategoryId.Appearance, "Appearance",
        listOf(
            SettingEntry(
                "theme", "Theme", "Light, dark, or follow the system.",
                keywords = listOf("dark mode", "light", "color", "colour"),
                content = { ThemeControl(it) },
            ),
            SettingEntry(
                "style", "Style", "How panes, buttons and switches are drawn. Works with any palette.",
                keywords = listOf("style", "glass", "glassmorphism", "frosted", "blur", "soft", "neumorphism", "retro", "windows 95", "win95", "classic"),
                layout = EntryLayout.Block,
                content = { StyleControl(it) },
            ),
            SettingEntry(
                "pure-black", "Pure black", "Black backgrounds with the dark theme. Easier on OLED screens.",
                keywords = listOf("amoled", "oled", "black"),
                notes = { PureBlackNotes(it) },
                rowModifier = { pureBlackToggle(it) },
                content = { PureBlackSwitch(it) },
            ),
            SettingEntry(
                "neon", "Neon", "Vivid colors with a glow on buttons, switches and the mirror frame. Works with any palette.",
                keywords = listOf("neon", "glow", "vivid", "synthwave", "cyberpunk"),
                rowModifier = { neonToggle(it) },
                content = { NeonSwitch(it) },
            ),
            SettingEntry(
                "palette", "Color palette", "Colors used across the app. Custom lets you pick your own.",
                keywords = listOf("color", "colour", "accent", "palette", "custom", "hue"),
                layout = EntryLayout.Block,
                content = { PaletteControl(it) },
            ),
            SettingEntry(
                "launch-quit-animation", "Launch & quit animation", "Plays when Scrcpy Vwr opens and quits.",
                keywords = listOf("animation", "motion", "launch", "startup", "quit", "shutdown", "splash", "open", "close"),
                content = { LaunchQuitAnimationControl(it) },
            ),
        ),
    ),
    SettingsCategory(
        SettingsCategoryId.Mirroring, "Mirroring",
        listOf(
            SettingEntry(
                "default-preset", "Default preset", "Applied to a device the first time it is selected.",
                keywords = listOf("preset", "profile", "defaults"),
                content = { DefaultPresetControl(it) },
            ),
            SettingEntry(
                "animate-mirror", "Animate mirror windows",
                "The window grows out of the Mirror button and the screen switches on with the first frame. Plays in reverse on close.",
                keywords = listOf("animation", "motion", "zoom", "transition", "open", "close", "window"),
                rowModifier = { animateMirrorToggle(it) },
                content = { AnimateMirrorSwitch(it) },
            ),
            SettingEntry(
                "capture-dir", "Screenshots and recordings folder", "Where screenshots and screen recordings are saved.",
                keywords = listOf("screenshot", "recording", "capture", "save", "directory", "path"),
                layout = EntryLayout.Block,
                content = { CaptureDirControl(it) },
            ),
        ),
    ),
    SettingsCategory(
        SettingsCategoryId.Adb, "adb",
        listOf(
            SettingEntry(
                "adb-path", "adb executable", "Use the bundled adb or point to your own.",
                keywords = listOf("android debug bridge", "platform-tools", "binary", "path", "custom"),
                layout = EntryLayout.Block,
                content = { AdbPicker(it) },
            ),
        ),
    ),
    SettingsCategory(
        SettingsCategoryId.Ai, "AI",
        listOf(
            SettingEntry(
                "ai-skill", "AI skill for custom actions",
                "Teach an AI tool on this computer to write quick actions. They show up here and in the mirror window as soon as the AI makes them.",
                keywords = listOf("claude", "gemini", "codex", "chatgpt", "grok", "skill", "agent", "llm"),
                layout = EntryLayout.Block,
                content = { AiSkillControl(it) },
            ),
        ),
    ),
    SettingsCategory(
        SettingsCategoryId.Updates, "Updates",
        listOf(
            SettingEntry(
                "updates", "App updates", "Check for new versions and download them.",
                keywords = listOf("version", "upgrade", "release", "download", "github"),
                layout = EntryLayout.Block,
                content = { UpdatesContent(it.updates, it.actions.onCheckUpdates, it.actions.onUpdate, it.actions.onCancelUpdate) },
            ),
        ),
    ),
    SettingsCategory(
        SettingsCategoryId.Help, "Help",
        listOf(
            SettingEntry(
                "welcome-guide", "Welcome guide", "Setup steps for USB debugging, Wi-Fi and mirroring.",
                keywords = listOf("onboarding", "tutorial", "setup", "usb", "wifi"),
                content = { OutlinedButton(onClick = it.actions.onShowOnboarding) { Text("Show welcome guide") } },
            ),
        ),
    ),
)
