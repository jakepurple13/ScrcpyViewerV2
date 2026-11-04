package com.programmersbox.scrcpyviewer.ui.presets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.programmersbox.scrcpyviewer.options.OptionValues
import com.programmersbox.scrcpyviewer.presets.ConflictChoice
import com.programmersbox.scrcpyviewer.presets.ImportItem
import com.programmersbox.scrcpyviewer.presets.ImportPlan
import com.programmersbox.scrcpyviewer.presets.Preset
import com.programmersbox.scrcpyviewer.presets.PresetCodec
import com.programmersbox.scrcpyviewer.presets.PresetStore
import com.programmersbox.scrcpyviewer.state.DeviceOptionsStore
import com.programmersbox.scrcpyviewer.ui.common.defaultViewModelScope
import com.programmersbox.scrcpyviewer.ui.devices.SelectedDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.koin.core.annotation.KoinViewModel
import java.util.UUID

data class PresetsUiState(
    val presets: List<Preset> = emptyList(),
    val defaultId: String? = null,
    val selected: Preset? = null,
    /** The file text for [selected] (what Export writes and Copy JSON copies). */
    val selectedJson: String = "",
    /** Non-null when presets cannot be saved to disk. */
    val persistenceError: String? = null,
    /** Apply needs a selected device on the Devices page. */
    val canApply: Boolean = false,
)

/**
 * The import being reviewed. When [error] is set the file was rejected and [items] is empty.
 * [unknownOptions] maps a preset name from the file to the option flags that are not known and will be ignored.
 */
data class ImportUiState(
    val items: List<ImportItem> = emptyList(),
    val unknownOptions: Map<String, List<String>> = emptyMap(),
    val error: String? = null,
) {
    /** Presets that confirming would add or change: included, and not a conflict that is set to Skip. */
    val importCount: Int
        get() = items.count { it.include && !(it.conflictsWith != null && it.choice == ConflictChoice.Skip) }
}

@KoinViewModel
class PresetsViewModel(
    private val store: PresetStore,
    private val options: DeviceOptionsStore,
    /** The device selected on the Devices screen, which Apply targets. */
    private val device: SelectedDevice,
    scope: CoroutineScope = defaultViewModelScope(),
) : ViewModel(scope) {
    private val selectedId = MutableStateFlow<String?>(null)

    val state: StateFlow<PresetsUiState> = combine(
        store.presets, store.defaultPresetId, selectedId, store.persistenceError, device.optionsKey,
    ) { presets, defaultId, id, error, serial ->
        val selected = presets.firstOrNull { it.id == id } ?: presets.firstOrNull()
        PresetsUiState(
            presets = presets,
            defaultId = defaultId,
            selected = selected,
            selectedJson = selected?.let { PresetCodec.encode(listOf(it)) }.orEmpty(),
            persistenceError = error,
            canApply = serial != null,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PresetsUiState())

    val selected: StateFlow<Preset?> = state.map { it.selected }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _importState = MutableStateFlow<ImportUiState?>(null)
    val importState: StateFlow<ImportUiState?> get() = _importState

    /** One-shot messages for the snackbar. */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> get() = _messages

    fun select(id: String) {
        selectedId.value = id
    }

    /** Adds an empty preset with a unique name and selects it. */
    fun new(): Preset {
        val names = store.presets.value.map { it.name.lowercase() }.toSet()
        var name = "New preset"
        var n = 2
        while (name.lowercase() in names) name = "New preset (${n++})"
        val preset = Preset(UUID.randomUUID().toString(), name, options = OptionValues(emptyMap()))
        store.upsert(preset)
        selectedId.value = preset.id
        return preset
    }

    fun edit(id: String, name: String, description: String, values: OptionValues) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || store.presets.value.none { it.id == id } || store.isNameTaken(trimmed, exceptId = id)) return
        store.upsert(Preset(id, trimmed, description, values))
    }

    fun duplicate(id: String) {
        if (store.presets.value.none { it.id == id }) return
        selectedId.value = store.duplicate(id).id
    }

    fun delete(id: String) {
        store.delete(id)
    }

    /** `null` clears the default. */
    fun setDefault(id: String?) {
        store.setDefault(id)
    }

    /** Applies the preset to the selected device's working set. Returns false (and does nothing) with no selected device. */
    fun applyToSelectedDevice(id: String): Boolean {
        val serial = device.optionsKey.value ?: return false
        val preset = store.presets.value.firstOrNull { it.id == id } ?: return false
        options.applyPreset(serial, id)
        _messages.tryEmit("Applied ${preset.name} to ${device.label(serial)}")
        return true
    }

    fun exportJson(ids: List<String>): String =
        PresetCodec.encode(store.presets.value.filter { it.id in ids })

    fun beginImport(text: String) {
        _importState.value = PresetCodec.decode(text).fold(
            onSuccess = { ImportUiState(ImportPlan.plan(store.presets.value, it.presets), it.unknownOptions) },
            onFailure = { ImportUiState(error = it.message ?: "This file isn't a Scrcpy Vwr preset file.") },
        )
    }

    fun setChoice(index: Int, choice: ConflictChoice) = updateItem(index) { it.copy(choice = choice) }

    fun setInclude(index: Int, include: Boolean) = updateItem(index) { it.copy(include = include) }

    fun confirmImport() {
        val import = _importState.value ?: return
        _importState.value = null
        if (import.error != null) return
        val before = store.presets.value
        ImportPlan.apply(before, import.items)
            .filter { after -> before.firstOrNull { it.id == after.id } != after }
            .forEach(store::upsert)
    }

    fun cancelImport() {
        _importState.value = null
    }

    private fun updateItem(index: Int, transform: (ImportItem) -> ImportItem) {
        val current = _importState.value ?: return
        if (index !in current.items.indices) return
        _importState.value = current.copy(items = current.items.mapIndexed { i, item -> if (i == index) transform(item) else item })
    }
}
