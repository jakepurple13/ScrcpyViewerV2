package com.programmersbox.scrcpyviewer.ui.devices.info

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.programmersbox.scrcpyviewer.adb.Device
import com.programmersbox.scrcpyviewer.adb.DeviceState
import com.programmersbox.scrcpyviewer.info.DeviceInfoReader
import com.programmersbox.scrcpyviewer.info.LiveInfo
import com.programmersbox.scrcpyviewer.info.StaticInfo
import com.programmersbox.scrcpyviewer.state.AppSettings
import com.programmersbox.scrcpyviewer.ui.common.defaultViewModelScope
import com.programmersbox.scrcpyviewer.ui.devices.DeviceSelection
import com.programmersbox.scrcpyviewer.ui.devices.DeviceSelectionState
import com.programmersbox.scrcpyviewer.ui.devices.deviceTitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel

/** The Devices screen's detail tabs; the choice is saved in `Settings.deviceDetailTab`. */
enum class DetailTab {
    Options, Info;

    companion object {
        fun fromName(name: String?): DetailTab = entries.firstOrNull { it.name == name } ?: Options
    }
}

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

data class DeviceInfoUiState(
    val serial: String? = null,
    val title: String? = null,
    /** Why the selected device can't be read (the device row's message), or null when it is ready. */
    val notReady: String? = null,
    val static: Load<StaticInfo> = Load.Loading,
    val live: Load<LiveInfo> = Load.Loading,
    /** When [live] last loaded, from the view model's clock. */
    val liveUpdatedAt: Long? = null,
    /** The last live poll failed; [live] still holds the values before it. */
    val liveStale: Boolean = false,
)

/** Builds the reader for one device's serial. */
fun interface DeviceInfoReaderFactory {
    fun create(serial: String): DeviceInfoReader
}

/**
 * The Info tab: static info once per selected device (cached per serial for the session, refreshed quietly on return)
 * and live info every [LIVE_POLL_MS] while the tab is on screen ([setActive]) and the device is ready.
 *
 * A stuck adb read cannot be interrupted (blocking socket read), so a new device, [setActive] or [retry] cancels the
 * old work without waiting for it and starts the new work at once; results from old work are dropped by [generation].
 */
@KoinViewModel
class DeviceInfoViewModel(
    selection: DeviceSelection,
    private val settings: AppSettings,
    private val readerFor: DeviceInfoReaderFactory,
    scope: CoroutineScope = defaultViewModelScope(),
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel(scope) {
    private val active = MutableStateFlow(false)
    private val retries = MutableStateFlow(0)
    private val _state = MutableStateFlow(DeviceInfoUiState())
    val state: StateFlow<DeviceInfoUiState> = _state.asStateFlow()

    val tab: StateFlow<DetailTab> = settings.settings.map { DetailTab.fromName(it.deviceDetailTab) }
        .stateIn(scope, SharingStarted.Eagerly, DetailTab.fromName(settings.settings.value.deviceDetailTab))

    // Touched only from viewModelScope's dispatcher.
    private val staticCache = HashMap<String, StaticInfo>()
    private var target: Target? = null
    private var work: Job? = null
    private var generation = 0L

    private data class Target(val serial: String, val retry: Int)

    init {
        viewModelScope.launch {
            combine(selection.state, active, retries, ::Triple).collect { (sel, isActive, retry) ->
                onInput(sel, isActive, retry)
            }
        }
    }

    fun setTab(tab: DetailTab) = settings.update { it.copy(deviceDetailTab = tab.name) }

    /** The Info tab calls this while it is on screen; nothing is read while inactive. */
    fun setActive(active: Boolean) {
        this.active.value = active
    }

    /** Reloads static and live info. */
    fun retry() {
        retries.update { it + 1 }
    }

    private fun onInput(sel: DeviceSelectionState, isActive: Boolean, retry: Int) {
        val device = sel.devices.firstOrNull { it.serial == sel.serial }
        val serial = device?.serial
        val notReady = device?.let(::notReadyMessage)
        _state.update { old ->
            if (old.serial != serial) {
                DeviceInfoUiState(
                    serial = serial,
                    title = device?.let(::deviceTitle),
                    notReady = notReady,
                    static = serial?.let(staticCache::get)?.let { Load.Ready(it) } ?: Load.Loading,
                )
            } else {
                old.copy(title = device?.let(::deviceTitle), notReady = notReady)
            }
        }
        val next = if (serial != null && notReady == null && isActive) Target(serial, retry) else null
        if (next == target) return
        // cancel(), not cancelAndJoin(): a stuck read must not hold up the next device.
        work?.cancel()
        work = null
        target = next
        val gen = ++generation
        if (next == null) return
        _state.update { it.copy(static = it.static.failedToLoading(), live = it.live.failedToLoading()) }
        work = viewModelScope.launch { load(next.serial, gen) }
    }

    private suspend fun load(serial: String, gen: Long) = coroutineScope {
        val reader = readerFor.create(serial)
        launch {
            val result = readOrMessage { reader.readStatic() }
            if (gen != generation) return@launch
            when (result) {
                is Load.Ready -> {
                    staticCache[serial] = result.value
                    _state.update { it.copy(static = result) }
                }
                // A failed quiet refresh keeps the cached values.
                is Load.Failed -> if (serial !in staticCache) _state.update { it.copy(static = result) }
                Load.Loading -> Unit
            }
        }
        while (true) {
            val result = readOrMessage { reader.readLive() }
            if (gen != generation) return@coroutineScope
            _state.update {
                when (result) {
                    is Load.Ready -> it.copy(live = result, liveUpdatedAt = clock(), liveStale = false)
                    is Load.Failed -> if (it.live is Load.Ready) it.copy(liveStale = true) else it.copy(live = result)
                    Load.Loading -> it
                }
            }
            delay(LIVE_POLL_MS)
        }
    }

    private inline fun <T> readOrMessage(read: () -> T): Load<T> = try {
        Load.Ready(read())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Load.Failed(e.message ?: "Could not read device info")
    }

    companion object {
        const val LIVE_POLL_MS = 5_000L
    }
}

private fun <T> Load<T>.failedToLoading(): Load<T> = if (this is Load.Failed) Load.Loading else this

/** The device row's message for a device that can't be read, or null when it is ready. */
private fun notReadyMessage(device: Device): String? = when (device.state) {
    DeviceState.Device -> null
    DeviceState.Unauthorized -> "Accept the USB debugging prompt on the device"
    DeviceState.Offline -> "Device is offline"
    DeviceState.Other -> "Device isn't ready"
}
