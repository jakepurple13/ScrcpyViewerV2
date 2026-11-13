package com.programmersbox.scrcpyviewer.di

import com.programmersbox.scrcpyviewer.AppDataDir
import com.programmersbox.scrcpyviewer.Os
import com.programmersbox.scrcpyviewer.actions.ActionInbox
import com.programmersbox.scrcpyviewer.actions.ActionStore
import com.programmersbox.scrcpyviewer.actions.AiSkillInstaller
import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.adb.DeviceTracker
import com.programmersbox.scrcpyviewer.adb.DnsSdDiscovery
import com.programmersbox.scrcpyviewer.adb.WirelessAdb
import com.programmersbox.scrcpyviewer.adb.optionsKey
import com.programmersbox.scrcpyviewer.info.DeviceInfoReader
import com.programmersbox.scrcpyviewer.media.AudioPlayer
import com.programmersbox.scrcpyviewer.media.DelayedVideoSink
import com.programmersbox.scrcpyviewer.media.VideoDecoder
import com.programmersbox.scrcpyviewer.presets.PresetStore
import com.programmersbox.scrcpyviewer.scrcpy.ScrcpySession
import com.programmersbox.scrcpyviewer.scrcpy.ServerLauncher
import com.programmersbox.scrcpyviewer.state.AppSettings
import com.programmersbox.scrcpyviewer.state.DeviceOptionsStore
import com.programmersbox.scrcpyviewer.tools.BundledTools
import com.programmersbox.scrcpyviewer.ui.devices.DeviceSelection
import com.programmersbox.scrcpyviewer.ui.devices.SelectedDevice
import com.programmersbox.scrcpyviewer.ui.devices.info.DeviceInfoReaderFactory
import com.programmersbox.scrcpyviewer.ui.actions.ActionsNavigation
import com.programmersbox.scrcpyviewer.ui.mirror.MirrorController
import com.programmersbox.scrcpyviewer.ui.mirror.follow.InputFollowing
import com.programmersbox.scrcpyviewer.ui.mirror.MirrorRequests
import com.programmersbox.scrcpyviewer.ui.mirror.OpenMirrors
import com.programmersbox.scrcpyviewer.ui.mirror.QuickActionsMemory
import com.programmersbox.scrcpyviewer.ui.mirror.ShellHistory
import com.programmersbox.scrcpyviewer.update.NucleusUpdateBackend
import com.programmersbox.scrcpyviewer.update.UpdateBackend
import com.programmersbox.scrcpyviewer.update.UpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.core.Koin
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.KoinApplication
import com.programmersbox.scrcpyviewer.ui.common.PcClipboard
import com.programmersbox.scrcpyviewer.ui.window.NativeScreenGeometry
import com.programmersbox.scrcpyviewer.ui.window.ScreenGeometry
import org.koin.core.annotation.Module
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Singleton
import org.koin.plugin.module.dsl.startKoin
import java.nio.file.Path
import java.nio.file.Paths

@KoinApplication
object ScrcpyKoinApp

/** Where settings, presets and device options are stored. Given to Koin at start (tests use a temp dir). */
@Provided
class DataDir(val path: Path)

/** Builds the adb client for the adb path. Given to Koin at start (tests give one that never runs adb). */
@Provided
fun interface AdbClientFactory {
    fun create(adbPath: () -> Path): AdbClient
}

/** The scope stores, the device tracker and mirror sessions run on; it lives as long as the app. */
class AppScope : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Default)

/** Discovery root: the modules below, plus the `@KoinViewModel`s and `@Singleton`s under `ui`. */
@Module(includes = [StorageModule::class, AdbModule::class, MirrorModule::class, UpdateModule::class])
@Configuration
@ComponentScan("com.programmersbox.scrcpyviewer.ui")
class AppModule

/** Files under [DataDir] and the bundled tools. */
@Module
class StorageModule {
    @Singleton
    fun appScope(): AppScope = AppScope()

    @Singleton
    fun appSettings(dataDir: DataDir): AppSettings = AppSettings(dataDir.path.resolve("settings.json"))

    @Singleton
    fun presetStore(dataDir: DataDir, scope: AppScope): PresetStore = PresetStore(dataDir.path.resolve("presets.json"), scope)

    @Singleton
    fun actionStore(dataDir: DataDir): ActionStore = ActionStore(dataDir.path.resolve("actions.json"))

    /** Watches `actions-inbox` for action files AI tools drop in; `main()` starts it. */
    @Singleton
    fun actionInbox(dataDir: DataDir, store: ActionStore): ActionInbox =
        ActionInbox(dataDir.path.resolve("actions-inbox"), store)

    @Singleton
    fun aiSkillInstaller(dataDir: DataDir): AiSkillInstaller = AiSkillInstaller(
        Path.of(System.getProperty("user.home")),
        dataDir.path.resolve("actions-inbox"),
        dataDir.path.resolve("actions.json"),
    )

    @Singleton
    fun deviceOptions(dataDir: DataDir, presets: PresetStore, scope: AppScope): DeviceOptionsStore =
        DeviceOptionsStore(dataDir.path.resolve("devices.json"), presets, scope)

    @Singleton
    fun bundledTools(dataDir: DataDir): BundledTools = BundledTools(dataDir = dataDir.path)
}

/** The adb client, the device list and Wi-Fi debugging. */
@Module
class AdbModule {
    @Singleton
    fun adbClient(factory: AdbClientFactory, settings: AppSettings, tools: BundledTools): AdbClient = factory.create {
        settings.settings.value.adbPathOverride?.let { Paths.get(it) } ?: tools.adbExecutable()
    }

    @Singleton
    fun deviceTracker(client: AdbClient, scope: AppScope): DeviceTracker = DeviceTracker(client, scope)

    /** The device selected on the Devices screen; Presets applies to it. */
    @Singleton(binds = [SelectedDevice::class])
    fun deviceSelection(tracker: DeviceTracker, scope: AppScope): DeviceSelection =
        DeviceSelection(tracker.devices, tracker.connected, scope)

    /** Readers for the Devices screen's Info tab, one per selected device. */
    @Singleton
    fun deviceInfoReaderFactory(client: AdbClient): DeviceInfoReaderFactory =
        DeviceInfoReaderFactory { DeviceInfoReader(client, it) }

    @Singleton
    fun wirelessAdb(client: AdbClient, mirrors: Lazy<MirrorController>): WirelessAdb = WirelessAdb(
        client,
        extraDiscovery = if (Os.isMac) DnsSdDiscovery()::services else null,
        // Restarting adb ends every mirror session, so only do it unasked while none is open.
        canRestartServer = { mirrors.value.open.value.isEmpty() },
    )
}

/** Mirror sessions and the requests that open them. */
@Module
class MirrorModule {
    /** Where the screens are, from the OS (NSScreen, EnumDisplayMonitors); no AWT. */
    @Singleton
    fun screenGeometry(): ScreenGeometry = NativeScreenGeometry

    /** The computer's clipboard; the main window binds it (see Main.kt). */
    @Singleton
    fun pcClipboard(): PcClipboard = PcClipboard()

    @Singleton
    fun mirrorRequests(): MirrorRequests = MirrorRequests()

    /** Mirror windows ask the main window to open the Actions screen through this. */
    @Singleton
    fun actionsNavigation(): ActionsNavigation = ActionsNavigation()

    /** Typed shell commands, shared by every mirror window's console. */
    @Singleton
    fun shellHistory(): ShellHistory = ShellHistory()

    /** Quick actions state kept for the life of the app across mirror windows (toggles, last values, last tab). */
    @Singleton
    fun quickActionsMemory(): QuickActionsMemory = QuickActionsMemory()

    @Singleton
    fun openMirrors(mirrors: Lazy<MirrorController>): OpenMirrors = OpenMirrors { mirrors.value.open.value.keys }

    @Singleton
    fun serverLauncher(client: AdbClient, tools: BundledTools): ServerLauncher = ServerLauncher(client, tools)

    /** Open mirror sessions; they run on [AppScope], so they live until their window or the app is closed. */
    @Singleton
    fun mirrorController(
        scope: AppScope,
        tracker: DeviceTracker,
        deviceOptions: DeviceOptionsStore,
        serverLauncher: ServerLauncher,
        settings: AppSettings,
        clipboard: PcClipboard,
    ): MirrorController = MirrorController(
        scope = scope,
        devices = tracker.devices,
        optionsFor = { serial ->
            val key = tracker.devices.value.firstOrNull { it.serial == serial }?.optionsKey() ?: serial
            deviceOptions.workingSet(key).value.values
        },
        newSession = { serial, values ->
            ScrcpySession(
                serial, values, serverLauncher,
                decoderFactory = { meta, session ->
                    // video-buffer delays what is shown, not what is recorded (the recorder is another sink).
                    DelayedVideoSink.forOptions(VideoDecoder(meta.codecId, session::publishFrame, session::publishFps), values)
                },
                audioFactory = { codecId, _ -> AudioPlayer.forOptions(codecId, values) },
                scope = scope,
                captureDir = { Paths.get(settings.settings.value.captureDir) },
            )
        },
        setClipboard = clipboard::setText,
    )

    /** The app-wide follow link between mirror windows; pruned as mirrors close. */
    @Singleton
    fun inputFollowing(mirrors: MirrorController, scope: AppScope): InputFollowing =
        InputFollowing(mirrors.open, scope)
}

/** App updates, through Nucleus' updater ([UpdateBackend]). */
@Module
class UpdateModule {
    /** Lives as long as the app: the popup, the badge and Settings share it, and `main()` quits on Installing. */
    @Singleton
    fun updateManager(
        backend: UpdateBackend,
        dataDir: DataDir,
        scope: AppScope,
        settings: AppSettings,
        tools: BundledTools,
    ): UpdateManager {
        // 1.5.0's own updater downloaded installers and helper scripts here; Nucleus caches in "update-cache".
        runCatching { dataDir.path.resolve(LEGACY_UPDATES_DIR).toFile().deleteRecursively() }
        return UpdateManager(
            backend = backend,
            scope = scope,
            // Stop the adb server before installing only when it is the app's own adb.
            bundledAdbInUse = { if (settings.settings.value.adbPathOverride == null) tools.adbExecutable() else null },
            autoDownload = settings.settings.map { it.autoDownloadUpdates }
                .stateIn(scope, SharingStarted.Eagerly, settings.settings.value.autoDownloadUpdates),
        )
    }

    private companion object {
        const val LEGACY_UPDATES_DIR = "updates"
    }
}

/** Where Nucleus keeps the last downloaded installer, the base for the next differential download. */
private const val UPDATE_CACHE_DIR = "update-cache"

/**
 * Starts the app's Koin graph and opens a mirror window for each [MirrorRequests] serial. Tests pass their own
 * [dataDir], an [adbClientFactory] and an [updateBackend] so no adb server, device or network is touched; they call
 * `stopKoin()` when done.
 */
fun startAppKoin(
    dataDir: Path = AppDataDir.root(),
    adbClientFactory: AdbClientFactory = AdbClientFactory { AdbClient(adbPath = it) },
    updateBackend: UpdateBackend = NucleusUpdateBackend(cacheDir = dataDir.resolve(UPDATE_CACHE_DIR)),
): Koin {
    val koin = startKoin<ScrcpyKoinApp> {
        koin.declare(DataDir(dataDir))
        koin.declare(adbClientFactory)
        koin.declare(updateBackend)
    }.koin
    val mirrors = koin.get<MirrorController>()
    koin.get<AppScope>().launch { koin.get<MirrorRequests>().collect { mirrors.open(it) } }
    return koin
}
