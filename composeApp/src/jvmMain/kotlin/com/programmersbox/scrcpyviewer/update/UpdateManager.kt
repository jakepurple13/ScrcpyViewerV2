package com.programmersbox.scrcpyviewer.update

import com.programmersbox.scrcpyviewer.APP_VERSION
import com.programmersbox.scrcpyviewer.Os
import dev.nucleusframework.updater.UpdateEvent
import dev.nucleusframework.updater.UpdateInfo
import dev.nucleusframework.updater.UpdateResult
import dev.nucleusframework.updater.Version
import dev.nucleusframework.updater.exception.NoMatchingFileException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/** How often the running app checks for a new release in the background. */
val UPDATE_CHECK_INTERVAL: Duration = 6.hours

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    /** [background]: started by "Download updates automatically", so no dialog shows it; it ends in [Ready]. */
    data class Downloading(
        val release: Release,
        val read: Long,
        val total: Long,
        val background: Boolean = false,
    ) : UpdateState
    /**
     * Downloaded in the background and verified. Waits for [UpdateManager.restart]; quitting the app instead installs
     * it without reopening.
     */
    data class Ready(val release: Release) : UpdateState
    /** Downloaded and verified; the app should quit, then `main()` calls [UpdateManager.installPending]. */
    data class Installing(val release: Release) : UpdateState
    data class Failed(val message: String, val release: Release?) : UpdateState
}

/**
 * Checks for, downloads and installs app updates through Nucleus' updater ([UpdateBackend]). It lives as long as
 * the app: the popup, the badge and Settings share it, `main()` quits once it reaches [UpdateState.Installing] and
 * then calls [installPending].
 */
class UpdateManager(
    private val backend: UpdateBackend,
    private val scope: CoroutineScope,
    val currentVersion: String = APP_VERSION,
    private val bundledAdbInUse: () -> Path?,
    /** The app runs from a mounted disk image or App Translocation, where its `.app` cannot be replaced. */
    val macNotInApplications: Boolean = macRunningOutsideApplications(),
    private val repo: String = updateRepo(),
    private val now: () -> Instant = Instant::now,
    /** How long the check waits for release notes before showing the release without them. */
    private val notesTimeoutMs: Long = 10_000,
    /** The "Download updates automatically" setting. Turning it on while a release is offered downloads it. */
    private val autoDownload: StateFlow<Boolean> = MutableStateFlow(false),
) {
    // Guards every state change, so a check or update started while another is running is ignored.
    private val lock = Any()
    private var downloadJob: Job? = null
    private var pendingInfo: UpdateInfo? = null
    private var pendingFile: File? = null

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _available = MutableStateFlow<Release?>(null)

    /** The newest known release newer than [currentVersion]; it survives a failed check or update. */
    val available: StateFlow<Release?> = _available.asStateFlow()

    private val _lastChecked = MutableStateFlow<Instant?>(null)
    val lastChecked: StateFlow<Instant?> = _lastChecked.asStateFlow()

    private val _popupDismissed = MutableStateFlow(false)

    /** True once "Later" is chosen; for this launch, until a newer release is found. */
    val popupDismissed: StateFlow<Boolean> = _popupDismissed.asStateFlow()

    /** Whether this copy can install updates at all; see [canSelfUpdate] for a given release. */
    val canInstall: Boolean = backend.canInstall && !macNotInApplications

    init {
        scope.launch { autoDownload.collect { if (it) synchronized(lock) { startDownload(background = true) } } }
    }

    /**
     * Whether "Update now" is offered for [release]; otherwise the UI offers "Download from GitHub". False when the
     * release has no installer for this OS or architecture.
     */
    fun canSelfUpdate(release: Release): Boolean =
        canInstall && synchronized(lock) { pendingInfo?.version == release.version }

    // A dev run (`./gradlew run`) reads the same marker as the installed app, so it leaves it for that app.
    private val lastUpdate: UpdateEvent? = if (!backend.canInstall) {
        null
    } else {
        runCatching { backend.consumeUpdateEvent() }
            .onFailure { log.log(System.Logger.Level.WARNING, "Could not read the update marker", it) }
            .getOrNull()
    }

    /**
     * Nucleus writes `old -> new` just before it installs. Launching on any other version than `new` means the
     * install did not happen (Nucleus keeps its own log in a temp dir, so there is no reason to show).
     */
    val previousFailure: String? =
        lastUpdate?.takeIf { it.newVersion != currentVersion }?.let { "Updating to ${it.newVersion} did not complete." }

    /** The version this copy was updated from, on the first launch after a successful update. */
    val updatedFrom: String? = lastUpdate?.takeIf { it.newVersion == currentVersion }?.previousVersion

    /**
     * Checks at once, then again every [interval] until cancelled. Failures stay silent, as on launch; a check
     * that would overlap a running check or update is skipped.
     */
    suspend fun checkPeriodically(interval: Duration = UPDATE_CHECK_INTERVAL) {
        while (true) {
            check(userInitiated = false)
            delay(interval)
        }
    }

    /** Asks for the latest release. A failure is shown only when [userInitiated]. */
    fun check(userInitiated: Boolean) {
        val previous = synchronized(lock) {
            val current = _state.value
            if (current is UpdateState.Checking || current is UpdateState.Downloading ||
                current is UpdateState.Ready || current is UpdateState.Installing
            ) {
                return
            }
            _state.value = UpdateState.Checking
            current
        }
        scope.launch {
            try {
                val (info, found) = when (val result = backend.check()) {
                    is UpdateResult.Available -> result.info to result.info.toRelease(repo)
                    is UpdateResult.NotAvailable -> null to null
                    is UpdateResult.Error -> {
                        // A newer release with no file for this OS or arch: offer it, only for download from GitHub.
                        if (result.exception !is NoMatchingFileException) throw result.exception
                        null to backend.latestVersion()?.takeIf(::isNewer)?.let { releaseFor(it, repo) }
                    }
                }
                // The update metadata has no notes. Fetching them is best effort and never delays the popup long.
                val notes = found?.let { releaseNotes(it.version) }
                synchronized(lock) {
                    val release = found?.copy(body = notes)
                    // "Later" covers the release it was chosen for; a newer one found later shows the popup again.
                    if (release != null && release.version != _available.value?.version) _popupDismissed.value = false
                    pendingInfo = info
                    _available.value = release
                    _lastChecked.value = now()
                    _state.value = release?.let(UpdateState::Available) ?: UpdateState.UpToDate
                    if (autoDownload.value) startDownload(background = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                synchronized(lock) {
                    _state.value = if (userInitiated) {
                        UpdateState.Failed("Couldn't check for updates: ${e.message}", _available.value)
                    } else {
                        log.log(System.Logger.Level.WARNING, "Update check failed", e)
                        previous
                    }
                }
            }
        }
    }

    /** Downloads the release shown in [UpdateState.Available] (or a failed one); Installing once verified. */
    fun startUpdate() {
        synchronized(lock) { startDownload(background = false) }
    }

    /** Call holding [lock]. Does nothing unless a release this copy can install is offered. */
    private fun startDownload(background: Boolean) {
        val release = when (val current = _state.value) {
            is UpdateState.Available -> current.release
            // A background download that failed waits for the user, so the error stays in the popup.
            is UpdateState.Failed -> current.release.takeIf { !background }
            else -> null
        } ?: return
        if (!canInstall) return
        val info = pendingInfo?.takeIf { it.version == release.version } ?: return
        _state.value = UpdateState.Downloading(release, 0, info.currentFile.size, background)
        downloadJob = scope.launch { download(release, info, background) }
    }

    /** Installs the update downloaded in the background: [UpdateState.Ready] becomes Installing, so the app quits. */
    fun restart() {
        synchronized(lock) {
            val current = _state.value as? UpdateState.Ready ?: return
            _state.value = UpdateState.Installing(current.release)
        }
    }

    private suspend fun download(release: Release, info: UpdateInfo, background: Boolean) {
        val job = currentCoroutineContext().job
        try {
            var file: File? = null
            backend.download(info).collect { progress ->
                synchronized(lock) {
                    if (job.isActive) {
                        _state.value =
                            UpdateState.Downloading(release, progress.bytesDownloaded, progress.totalBytes, background)
                    }
                }
                progress.file?.let { file = it }
            }
            val downloaded = file ?: error("Download finished without a file")
            synchronized(lock) {
                job.ensureActive()
                pendingFile = downloaded
                _state.value = if (background) UpdateState.Ready(release) else UpdateState.Installing(release)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            synchronized(lock) {
                if (job.isActive) _state.value = UpdateState.Failed(e.message ?: "Update failed", release)
            }
        }
    }

    /**
     * Stops the download and goes back to [UpdateState.Available]. Cancelling a background download (from Settings)
     * also closes the popup, which would otherwise offer the release right away.
     */
    fun cancel() {
        synchronized(lock) {
            val current = _state.value as? UpdateState.Downloading ?: return
            downloadJob?.cancel()
            downloadJob = null
            if (current.background) _popupDismissed.value = true
            _state.value = UpdateState.Available(current.release)
        }
    }

    /**
     * Called by `main()` once the windows have closed and the sessions have ended; returns at once when nothing was
     * downloaded. Stops the app's own adb server (a running adb inside the install folder would block the Windows
     * installer), then hands off to Nucleus, which starts its install script and exits the JVM. A quit while the
     * update waits in [UpdateState.Ready] installs it without reopening the app.
     */
    fun installPending() {
        val (file, reopen) = synchronized(lock) { pendingFile to (_state.value !is UpdateState.Ready) }
        if (file == null) return
        bundledAdbInUse()?.let { adb ->
            runCatching {
                ProcessBuilder(adb.toString(), "kill-server")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                    .waitFor(5, TimeUnit.SECONDS)
            }.onFailure { log.log(System.Logger.Level.WARNING, "adb kill-server failed; installing anyway", it) }
        }
        runCatching { if (reopen) backend.installAndRestart(file) else backend.installAndQuit(file) }
            .onFailure { log.log(System.Logger.Level.ERROR, "Could not start the update installer", it) }
    }

    fun dismissPopup() {
        _popupDismissed.value = true
    }

    /** Null when the notes cannot be had within [notesTimeoutMs]; never fails the check. */
    private suspend fun releaseNotes(version: String): String? = try {
        withTimeoutOrNull(notesTimeoutMs) { backend.releaseNotes(version) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.log(System.Logger.Level.INFO, "No release notes for $version", e)
        null
    }

    private fun isNewer(version: String): Boolean =
        runCatching { Version.fromString(version) > Version.fromString(currentVersion) }.getOrDefault(false)

    private companion object {
        val log: System.Logger = System.getLogger("scrcpyviewer.Update")
    }
}

/**
 * True on macOS when the app runs straight from the mounted DMG or from App Translocation (a quarantined app opened
 * outside Applications): Nucleus would try to replace a read-only or temporary copy there.
 */
internal fun macRunningOutsideApplications(
    appPath: String? = System.getProperty("jpackage.app-path"),
    isMac: Boolean = Os.isMac,
): Boolean {
    if (!isMac || appPath.isNullOrBlank()) return false
    return appPath.startsWith("/Volumes/") || "/AppTranslocation/" in appPath
}
