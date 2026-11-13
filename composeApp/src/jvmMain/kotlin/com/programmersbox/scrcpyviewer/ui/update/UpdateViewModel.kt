package com.programmersbox.scrcpyviewer.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.programmersbox.scrcpyviewer.ui.common.defaultViewModelScope
import com.programmersbox.scrcpyviewer.update.Release
import com.programmersbox.scrcpyviewer.update.UpdateManager
import com.programmersbox.scrcpyviewer.update.UpdateState
import com.programmersbox.scrcpyviewer.update.updateRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import org.koin.core.annotation.KoinViewModel
import java.time.Instant

/** What the update popup, the Settings badge and the Settings section show. */
data class UpdateUiState(
    val state: UpdateState = UpdateState.Idle,
    /** The newest known newer release; kept through a failed check or update. */
    val available: Release? = null,
    val currentVersion: String = "",
    val lastChecked: Instant? = null,
    /** Set when the last update did not install; shown once in Settings and the popup. */
    val previousFailure: String? = null,
    /** The version this copy was updated from, on the first launch after an update; shown in Settings. */
    val updatedFrom: String? = null,
    /** Where "Download from GitHub" and "View release notes" go. */
    val releasePageUrl: String = releasePageUrl(null),
    val showPopup: Boolean = false,
    val canSelfUpdate: Boolean = false,
    val macNotInApplications: Boolean = false,
    /** Whether this copy can update itself at all; "Download updates automatically" is off limits otherwise. */
    val canInstall: Boolean = false,
)

/** Shortest gap between two download progress updates shown; Nucleus reports every 8 KiB read. */
private const val PROGRESS_INTERVAL_MILLIS = 100L

@KoinViewModel
class UpdateViewModel(
    private val manager: UpdateManager,
    scope: CoroutineScope = defaultViewModelScope(),
) : ViewModel(scope) {
    val state: StateFlow<UpdateUiState> = combine(
        manager.state.transform {
            emit(it)
            if (it is UpdateState.Downloading) delay(PROGRESS_INTERVAL_MILLIS)
        },
        manager.available,
        manager.lastChecked,
        manager.popupDismissed,
    ) { state, available, lastChecked, dismissed ->
        UpdateUiState(
            state = state,
            available = available,
            currentVersion = manager.currentVersion,
            lastChecked = lastChecked,
            previousFailure = manager.previousFailure,
            updatedFrom = manager.updatedFrom,
            releasePageUrl = releasePageUrl(available),
            // A background download stays out of the way until it is Ready.
            showPopup = available != null && !dismissed && state !is UpdateState.UpToDate &&
                !(state is UpdateState.Downloading && state.background),
            canSelfUpdate = available?.let(manager::canSelfUpdate) ?: false,
            macNotInApplications = manager.macNotInApplications,
            canInstall = manager.canInstall,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        UpdateUiState(
            currentVersion = manager.currentVersion,
            previousFailure = manager.previousFailure,
            updatedFrom = manager.updatedFrom,
            macNotInApplications = manager.macNotInApplications,
            canInstall = manager.canInstall,
        ),
    )

    fun check() = manager.check(userInitiated = true)

    fun update() = manager.startUpdate()

    fun cancel() = manager.cancel()

    fun later() = manager.dismissPopup()

    fun restart() = manager.restart()

}

/** The newer release's page, or the repo's latest-release page when none is known. */
internal fun releasePageUrl(available: Release?): String =
    available?.htmlUrl ?: "https://github.com/${updateRepo()}/releases/latest"
