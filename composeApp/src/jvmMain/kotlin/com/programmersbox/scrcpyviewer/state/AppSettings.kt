package com.programmersbox.scrcpyviewer.state

import com.programmersbox.scrcpyviewer.Os
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

@Serializable
data class Settings(
    val themeMode: ThemeMode = ThemeMode.System,
    val adbPathOverride: String? = null,
    /** Resolved when absent from the file: `~/Movies/ScrcpyViewer` on macOS, `~/Videos/ScrcpyViewer` elsewhere. */
    val captureDir: String = defaultCaptureDir(),
    /** Whether new windows use the OS title bar; null = OS default. */
    val useSystemTitleBar: Boolean? = null,
    /** False until the first-run guide is finished or skipped. True when absent, so upgrades from older files skip it. */
    val onboardingSeen: Boolean = true,
    /** False until the Actions intro is finished or closed. False when absent, so existing users see it once. */
    val actionsIntroSeen: Boolean = false,
    /** The mirror window's quick actions sheet width (dp) on the Actions tab; new mirror windows open at it. */
    val quickActionsWidth: Int = 280,
    /** The sheet's width (dp) on the Shell tab. */
    val shellWidth: Int = 400,
    /** Folders the user picked to install the AI skill into (absolute paths, no duplicates). */
    val aiSkillFolders: List<String> = emptyList(),
    /** Whether the Devices screen shows the full options pane instead of the simple one (preset, Mirror, Wi-Fi). */
    val advancedDeviceOptions: Boolean = false,
)

internal fun defaultCaptureDir(): String {
    val home = Paths.get(System.getProperty("user.home"))
    return home.resolve(if (Os.isMac) "Movies" else "Videos").resolve("ScrcpyViewer").toString()
}

/** App settings persisted to [file] (`settings.json`); defaults when the file is missing or corrupt. */
class AppSettings(file: Path) {
    private val store = JsonFile(file, Settings.serializer()) { Settings() }
    private val lock = Any()
    // No file yet means a first launch: show the guide. Not persisted until the guide closes.
    private val _settings = MutableStateFlow(
        if (Files.exists(file)) store.load() else store.load().copy(onboardingSeen = false),
    )

    val settings: StateFlow<Settings> get() = _settings

    /** Non-null when the file on disk could not be read or backed up; saving is then disabled. */
    val persistenceError: StateFlow<String?> get() = store.error

    fun update(transform: (Settings) -> Settings) = synchronized(lock) {
        _settings.value = transform(_settings.value)
        store.save(_settings.value)
    }

    fun addAiSkillFolder(path: String) = update { s ->
        if (path in s.aiSkillFolders) s else s.copy(aiSkillFolders = s.aiSkillFolders + path)
    }
}
