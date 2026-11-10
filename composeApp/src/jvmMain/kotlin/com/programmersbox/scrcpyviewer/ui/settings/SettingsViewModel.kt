package com.programmersbox.scrcpyviewer.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.programmersbox.scrcpyviewer.actions.AiSkillInstaller
import com.programmersbox.scrcpyviewer.actions.SkillStatus
import com.programmersbox.scrcpyviewer.actions.SkillTarget
import com.programmersbox.scrcpyviewer.adb.DeviceTracker
import com.programmersbox.scrcpyviewer.di.AdbClientFactory
import com.programmersbox.scrcpyviewer.presets.Preset
import com.programmersbox.scrcpyviewer.presets.PresetStore
import com.programmersbox.scrcpyviewer.state.AppSettings
import com.programmersbox.scrcpyviewer.state.Settings
import com.programmersbox.scrcpyviewer.tools.BundledTools
import com.programmersbox.scrcpyviewer.ui.common.defaultViewModelScope
import com.programmersbox.scrcpyviewer.ui.theme.CustomNeon
import com.programmersbox.scrcpyviewer.ui.theme.CustomPalette
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import com.programmersbox.scrcpyviewer.ui.theme.ThemePalette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Singleton

const val UNKNOWN_VERSION = "Unknown"

/** One place the AI skill can go, whether the tool looks installed, and how current its copy of the skill is. */
data class AiSkillRow(val target: SkillTarget, val found: Boolean, val status: SkillStatus)

data class SettingsUiState(
    val settings: Settings = Settings(),
    val presets: List<Preset> = emptyList(),
    val defaultPresetId: String? = null,
    /** Version of the bundled adb; null while it is being read. */
    val bundledAdbVersion: String? = null,
    /** Version of the custom adb ([Settings.adbPathOverride]); null while being read or when there is none. */
    val customAdbVersion: String? = null,
    /** Why the last chosen custom adb path was rejected; the path is not saved. */
    val adbPathError: String? = null,
    val persistenceError: String? = null,
    val aiSkill: List<AiSkillRow> = emptyList(),
    /** Label to error (null = installed) per target of the last install; non-null shows the Done dialog. */
    val aiInstallResult: List<Pair<String, String?>>? = null,
)

/** Error text when [path] cannot be used as the adb executable, else null. */
fun validateAdbPath(path: Path): String? = when {
    !Files.exists(path) -> "File not found: $path"
    !Files.isRegularFile(path) -> "Not a file: $path"
    !Files.isExecutable(path) -> "Not executable: $path"
    else -> null
}

/** "1.0.41 (35.0.2)" from `adb version` output; null when it has no recognizable version line. */
fun parseAdbVersion(output: String): String? {
    val lines = output.lines()
    val protocol = lines.firstNotNullOfOrNull { Regex("""Android Debug Bridge version (\S+)""").find(it)?.groupValues?.get(1) } ?: return null
    val build = lines.firstNotNullOfOrNull { Regex("""^Version (\d+(?:\.\d+)*)""").find(it.trim())?.groupValues?.get(1) }
    return if (build != null) "$protocol ($build)" else protocol
}

/** The adb side of the Settings screen. */
interface SettingsAdb {
    /** The bundled adb executable; may throw. */
    fun bundledAdb(): Path

    /** The parsed version of the adb at [path]; null on failure. */
    suspend fun version(path: Path): String?

    /** Called when the effective adb path changes. */
    fun restartTracker()
}

@Singleton
class AppSettingsAdb(
    private val tools: BundledTools,
    private val adbClientFactory: AdbClientFactory,
    private val tracker: DeviceTracker,
) : SettingsAdb {
    override fun bundledAdb(): Path = tools.adbExecutable()

    override suspend fun version(path: Path): String? {
        val result = adbClientFactory.create { path }.exec("version")
        return if (result.exitCode == 0) parseAdbVersion(result.stdout) else null
    }

    override fun restartTracker() = tracker.restart()
}

@KoinViewModel
class SettingsViewModel(
    private val appSettings: AppSettings,
    private val presets: PresetStore,
    private val adb: SettingsAdb,
    private val aiSkill: AiSkillInstaller,
    scope: CoroutineScope = defaultViewModelScope(),
) : ViewModel(scope) {
    private val bundledVersion = MutableStateFlow<String?>(null)
    private val customVersion = MutableStateFlow<String?>(null)
    private val adbError = MutableStateFlow<String?>(null)
    private val aiRows = MutableStateFlow<List<AiSkillRow>>(emptyList())
    private val aiResult = MutableStateFlow<List<Pair<String, String?>>?>(null)

    val state: StateFlow<SettingsUiState> = combine(
        appSettings.settings, presets.presets, presets.defaultPresetId, bundledVersion, customVersion,
    ) { settings, list, defaultId, bundled, custom ->
        SettingsUiState(settings, list, defaultId, bundled, if (settings.adbPathOverride != null) custom else null)
    }.combine(adbError) { s, e -> s.copy(adbPathError = e) }
        .combine(appSettings.persistenceError) { s, e -> s.copy(persistenceError = e) }
        .combine(aiRows) { s, rows -> s.copy(aiSkill = rows) }
        .combine(aiResult) { s, r -> s.copy(aiInstallResult = r) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState())

    init {
        viewModelScope.launch { bundledVersion.value = versionOf { adb.bundledAdb() } }
        appSettings.settings.value.adbPathOverride?.let(::loadCustomVersion)
        viewModelScope.launch { refreshAiSkill() }
    }

    private suspend fun refreshAiSkill() {
        aiRows.value = withContext(Dispatchers.IO) {
            aiSkill.targets(appSettings.settings.value.aiSkillFolders).map { t ->
                val found = (t as? SkillTarget.Tool)?.let { aiSkill.isFound(it.tool) } ?: true
                AiSkillRow(t, found, aiSkill.status(t))
            }
        }
    }

    /** Installs the skill into each of [targets], refreshes the rows, and shows the per-target results. */
    fun installAiSkill(targets: List<SkillTarget>) {
        viewModelScope.launch {
            val results = withContext(Dispatchers.IO) {
                targets.map { t -> t.label to aiSkill.install(t).exceptionOrNull()?.let { it.message ?: it::class.simpleName ?: "Failed" } }
            }
            refreshAiSkill()
            aiResult.value = results
        }
    }

    /** Remembers a folder the user picked as an install target. */
    fun addAiSkillFolder(path: Path) {
        appSettings.addAiSkillFolder(path.toString())
        viewModelScope.launch { refreshAiSkill() }
    }

    fun dismissAiInstallResult() {
        aiResult.value = null
    }

    private val navState = MutableStateFlow(SettingsNav())

    /** Held here, not in the screen, so the selected category survives rail tab switches. Not persisted. */
    val nav: StateFlow<SettingsNav> = navState

    fun selectCategory(id: SettingsCategoryId) = navState.update { it.select(id) }

    fun setQuery(query: String) = navState.update { it.withQuery(query) }

    fun setTheme(mode: ThemeMode) = appSettings.update { it.copy(themeMode = mode) }

    /** A neon palette also turns the Neon switch on, which the user can turn off again. */
    fun setPalette(palette: ThemePalette) =
        appSettings.update { it.copy(themePalette = palette.name, neon = it.neon || palette.neon) }

    fun setPureBlack(value: Boolean) = appSettings.update { it.copy(pureBlack = value) }

    /** Called when a Custom slider is released, not on every drag step (each call writes the settings file). */
    fun setCustomPalette(custom: CustomPalette) = appSettings.update { it.copy(customPalette = custom) }

    fun setNeon(value: Boolean) = appSettings.update { it.copy(neon = value) }

    /** Like [setCustomPalette], called on slider release. */
    fun setCustomNeon(custom: CustomNeon) = appSettings.update { it.copy(customNeon = custom) }

    /** Read when a window opens, so it applies to windows opened after this. */
    fun setUseSystemTitleBar(value: Boolean) = appSettings.update { it.copy(useSystemTitleBar = value) }

    /** Reopens the first-run guide; it marks itself seen again when closed. */
    fun showOnboarding() = appSettings.update { it.copy(onboardingSeen = false) }

    /** The first-run guide was closed. */
    fun finishOnboarding() = appSettings.update { it.copy(onboardingSeen = true) }

    /** The Actions intro was finished or closed. */
    fun finishActionsIntro() = appSettings.update { it.copy(actionsIntroSeen = true) }

    /** `null` clears the default preset. */
    fun setDefaultPreset(id: String?) = presets.setDefault(id)

    fun setCaptureDir(path: String) = appSettings.update { it.copy(captureDir = path) }

    /** Switches back to the bundled adb. */
    fun useBundledAdb() {
        adbError.value = null
        if (appSettings.settings.value.adbPathOverride == null) return
        appSettings.update { it.copy(adbPathOverride = null) }
        customVersion.value = null
        adb.restartTracker()
    }

    /** Uses [path] as the adb executable if it is a runnable file; otherwise shows an error and keeps the current setting. */
    fun useCustomAdb(path: String) {
        val trimmed = path.trim()
        val error = if (trimmed.isEmpty()) "Enter the path to an adb executable" else validateAdbPath(Paths.get(trimmed))
        adbError.value = error
        if (error != null) return
        val changed = appSettings.settings.value.adbPathOverride != trimmed
        appSettings.update { it.copy(adbPathOverride = trimmed) }
        loadCustomVersion(trimmed)
        if (changed) adb.restartTracker()
    }

    private fun loadCustomVersion(path: String) {
        customVersion.value = null
        viewModelScope.launch {
            val version = versionOf { Paths.get(path) }
            if (appSettings.settings.value.adbPathOverride == path) customVersion.value = version
        }
    }

    private suspend fun versionOf(path: () -> Path): String =
        runCatching { adb.version(path()) }.getOrNull() ?: UNKNOWN_VERSION
}
