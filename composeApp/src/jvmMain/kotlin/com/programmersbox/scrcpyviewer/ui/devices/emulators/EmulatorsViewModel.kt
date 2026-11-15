package com.programmersbox.scrcpyviewer.ui.devices.emulators

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.programmersbox.scrcpyviewer.emulator.Avd
import com.programmersbox.scrcpyviewer.emulator.AvdState
import com.programmersbox.scrcpyviewer.emulator.EmulatorLauncher
import com.programmersbox.scrcpyviewer.emulator.SdkLookup
import com.programmersbox.scrcpyviewer.emulator.avdHome
import com.programmersbox.scrcpyviewer.emulator.EmulatorEnvironment
import com.programmersbox.scrcpyviewer.emulator.findSdk
import com.programmersbox.scrcpyviewer.emulator.readAvds
import com.programmersbox.scrcpyviewer.state.AppSettings
import com.programmersbox.scrcpyviewer.state.EmulatorPrefs
import com.programmersbox.scrcpyviewer.ui.common.OpenPlatform
import com.programmersbox.scrcpyviewer.ui.common.defaultViewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.annotation.KoinViewModel
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext

/** One AVD in the Emulators section. [size] is null until its folder has been measured (or when that failed). */
internal data class EmulatorRow(
    val avd: Avd,
    val state: AvdState,
    val size: Long?,
    val prefs: EmulatorPrefs,
)

internal data class EmulatorsUiState(
    /** Null until the first read finishes. */
    val sdk: SdkLookup? = null,
    /** Catalog AVDs, sorted by name; empty unless [sdk] is [SdkLookup.Found]. */
    val rows: List<EmulatorRow> = emptyList(),
    val expanded: Boolean = true,
    /** Emulators still booting: the connected rows leave them out until they are ready. */
    val hiddenSerials: Set<String> = emptySet(),
    /** The user's home folder, shown as `~` in the SDK line. */
    val home: Path? = null,
)

internal sealed interface EmulatorEvent {
    data class Start(val avdId: String) : EmulatorEvent
    data class ColdBoot(val avdId: String) : EmulatorEvent
    data class Stop(val avdId: String) : EmulatorEvent
    data class Retry(val avdId: String) : EmulatorEvent
    data class ToggleMirror(val avdId: String) : EmulatorEvent
    data class ToggleWindow(val avdId: String) : EmulatorEvent
    data class SetExpanded(val expanded: Boolean) : EmulatorEvent
    data class SdkChosen(val path: String) : EmulatorEvent
    data object Reload : EmulatorEvent
}

private data class Catalog(val sdk: SdkLookup?, val avds: List<Avd>)

/**
 * The Devices screen's Emulators section: the SDK and AVD catalog (read off the main thread), each AVD's launcher state,
 * and its saved preferences. The catalog is read when the screen opens (it sends [EmulatorEvent.Reload]), after a new
 * SDK folder is chosen, and after a start or stop. AVD folder sizes (`du`, up to 30 s each) are measured in the background.
 */
@KoinViewModel
internal class EmulatorsViewModel(
    private val launcher: EmulatorLauncher,
    private val settings: AppSettings,
    /** Env, home and folder sizes: the machine's in the app, fakes in tests (no real SDK read, no `du`). */
    environment: EmulatorEnvironment,
    scope: CoroutineScope = defaultViewModelScope(),
    private val os: OpenPlatform = OpenPlatform.current(),
    private val io: CoroutineContext = Dispatchers.IO,
) : ViewModel(scope) {
    private val env = environment.env
    private val home = environment.home
    private val sizeOf = environment.sizeOf
    private val catalog = MutableStateFlow(Catalog(null, emptyList()))
    private val sizes = MutableStateFlow<Map<String, Long>>(emptyMap())
    private var loadJob: Job? = null
    private var sizeJob: Job? = null

    val state: StateFlow<EmulatorsUiState> = combine(
        catalog, sizes, launcher.states, launcher.bootingSerials, settings.settings,
    ) { cat, sizes, states, booting, s ->
        EmulatorsUiState(
            sdk = cat.sdk,
            // Only catalog AVDs: the launcher's map may hold others (an emulator of a Gradle managed device).
            rows = cat.avds.map { avd ->
                EmulatorRow(avd, states[avd.id] ?: AvdState.Stopped, sizes[avd.id], s.emulatorPrefs[avd.id] ?: EmulatorPrefs())
            },
            expanded = s.emulatorsExpanded,
            hiddenSerials = booting,
            home = home,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, EmulatorsUiState(expanded = settings.settings.value.emulatorsExpanded, home = home))

    init {
        // The first read comes from the Devices screen's Reload as it opens.
        // A stop (here or elsewhere) changes the AVD's size on disk: read the catalog again once it is Stopped.
        viewModelScope.launch {
            var before = launcher.states.value.keys
            launcher.states.collect { now ->
                if ((before - now.keys).isNotEmpty()) reload()
                before = now.keys
            }
        }
    }

    fun onEvent(event: EmulatorEvent) {
        when (event) {
            is EmulatorEvent.Start -> start(event.avdId, cold = false)
            is EmulatorEvent.ColdBoot -> start(event.avdId, cold = true)
            is EmulatorEvent.Retry -> start(event.avdId, cold = (row(event.avdId)?.state as? AvdState.Failed)?.cold == true)
            is EmulatorEvent.Stop -> launcher.stop(event.avdId, row(event.avdId)?.avd?.name ?: event.avdId)
            is EmulatorEvent.ToggleMirror -> updatePrefs(event.avdId) { it.copy(mirrorWhenReady = !it.mirrorWhenReady) }
            is EmulatorEvent.ToggleWindow -> updatePrefs(event.avdId) { it.copy(showWindow = !it.showWindow) }
            is EmulatorEvent.SetExpanded -> settings.update { it.copy(emulatorsExpanded = event.expanded) }
            is EmulatorEvent.SdkChosen -> {
                // Saved even when it is not an SDK: the section then says what it found there.
                settings.update { it.copy(androidSdkPath = event.path) }
                reload()
            }
            EmulatorEvent.Reload -> reload()
        }
    }

    private fun row(avdId: String): EmulatorRow? = state.value.rows.firstOrNull { it.avd.id == avdId }

    private fun start(avdId: String, cold: Boolean) {
        val sdk = (catalog.value.sdk as? SdkLookup.Found)?.sdk ?: return
        val avd = catalog.value.avds.firstOrNull { it.id == avdId } ?: return
        launcher.start(avd, sdk, cold)
        reload()
    }

    private fun updatePrefs(avdId: String, change: (EmulatorPrefs) -> EmulatorPrefs) = settings.update { s ->
        s.copy(emulatorPrefs = s.emulatorPrefs + (avdId to change(s.emulatorPrefs[avdId] ?: EmulatorPrefs())))
    }

    /** Reads the SDK and the AVD list off the main thread, then measures each AVD folder; a newer read replaces it. */
    private fun reload() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val next = withContext(io) {
                val sdk = findSdk(settings.settings.value.androidSdkPath, env, home, os)
                Catalog(sdk, if (sdk is SdkLookup.Found) readAvds(avdHome(env, home)) else emptyList())
            }
            catalog.value = next
            sizeJob?.cancel()
            sizeJob = viewModelScope.launch {
                for (avd in next.avds) {
                    val size = withContext(io) { sizeOf(avd.dir) } ?: continue
                    sizes.update { it + (avd.id to size) }
                }
            }
        }
    }
}
