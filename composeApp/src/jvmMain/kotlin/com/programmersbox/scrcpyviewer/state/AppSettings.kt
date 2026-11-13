package com.programmersbox.scrcpyviewer.state

import com.programmersbox.scrcpyviewer.Os
import com.programmersbox.scrcpyviewer.ui.devices.info.DetailTab
import com.programmersbox.scrcpyviewer.ui.theme.AppTheme
import com.programmersbox.scrcpyviewer.ui.theme.CustomNeon
import com.programmersbox.scrcpyviewer.ui.theme.CustomPalette
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import com.programmersbox.scrcpyviewer.ui.theme.ThemePalette
import com.programmersbox.scrcpyviewer.ui.theme.ThemeStyle
import com.programmersbox.scrcpyviewer.ui.theme.DEFAULT_GLASS_OPACITY
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
    /** Retired ("Use system title bar"): still parsed so older files load, but nothing reads it. */
    val useSystemTitleBar: Boolean? = null,
    /** False until the first-run guide is finished or skipped. True when absent, so upgrades from older files skip it. */
    val onboardingSeen: Boolean = true,
    /** False until the Actions intro is finished or closed. False when absent, so existing users see it once. */
    val actionsIntroSeen: Boolean = false,
    /** The mirror window's quick actions sheet width (dp) on the Actions tab; new mirror windows open at it. */
    val quickActionsWidth: Int = 280,
    /** The sheet's width (dp) on the Shell tab. */
    val shellWidth: Int = 400,
    /** The sheet's width (dp) on the Apps tab. */
    val appsWidth: Int = 480,
    /** The sheet's width (dp) on the Files tab. */
    val filesWidth: Int = 440,
    /** The sheet's width (dp) on the Logcat tab. */
    val logcatWidth: Int = 560,
    /** How the Files tab sorts a folder: "name", "size" or "mtime". A string so an unknown value never corrupts the file. */
    val filesSort: String = "name",
    /** Whether the Files tab sorts descending. */
    val filesSortDescending: Boolean = false,
    /** Whether the Files tab lists hidden (dot) files. */
    val filesShowHidden: Boolean = false,
    /** Folders the user picked to install the AI skill into (absolute paths, no duplicates). */
    val aiSkillFolders: List<String> = emptyList(),
    /** Whether the Devices screen shows the full options pane instead of the simple one (preset, Mirror, Wi-Fi). */
    val advancedDeviceOptions: Boolean = false,
    /** The Devices screen's detail tab, a [DetailTab] name; unknown or missing names fall back to Options. */
    val deviceDetailTab: String = DetailTab.Options.name,
    /** A [ThemePalette] name; unknown or missing names fall back to Teal. A string so a removed palette never corrupts the file. */
    val themePalette: String = ThemePalette.Teal.name,
    /** Black backgrounds in dark mode. */
    val pureBlack: Boolean = false,
    /** The Custom palette's sliders, kept when another palette is chosen. */
    val customPalette: CustomPalette = CustomPalette(),
    /** The Neon switch: neon colors and glow for any palette. */
    val neon: Boolean = false,
    /** The Custom neon palette's sliders, kept when another palette is chosen. */
    val customNeon: CustomNeon = CustomNeon(),
    /** A [ThemeStyle] name; unknown or missing names fall back to Material. */
    val themeStyle: String = ThemeStyle.Material.name,
    /** Retro 95 follows the palette instead of the classic gray and navy. */
    val retroPalette: Boolean = false,
    /** Glass shows the blurred desktop behind windows (macOS). */
    val desktopBlur: Boolean = true,
    /** Glass panes' opacity, 0..1. */
    val glassOpacity: Float = DEFAULT_GLASS_OPACITY,
    /** Mirror windows grow out of the Mirror button and the device screen switches on with the first frame. */
    val animateMirrorWindows: Boolean = true,
    /** A [LaunchQuitAnimation] name; unknown or missing names fall back to IconUnfold. */
    val launchQuitAnimation: String = LaunchQuitAnimation.IconUnfold.name,
    /** A newer release downloads in the background as soon as it is found, then waits for a restart. */
    val autoDownloadUpdates: Boolean = false,
)

/** Everything the theme reads from [Settings]. */
val Settings.appTheme: AppTheme
    get() = AppTheme(
        themeMode, ThemePalette.fromName(themePalette), pureBlack, customPalette, neon, customNeon,
        ThemeStyle.fromName(themeStyle), retroPalette, desktopBlur, glassOpacity,
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
