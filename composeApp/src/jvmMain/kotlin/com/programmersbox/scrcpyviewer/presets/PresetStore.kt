package com.programmersbox.scrcpyviewer.presets

import com.programmersbox.scrcpyviewer.state.JsonFile
import com.programmersbox.scrcpyviewer.telemetry.Telemetry
import com.programmersbox.scrcpyviewer.telemetry.TelemetryEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.nio.file.Path
import java.util.UUID

@Serializable
private data class StoreFile(val presets: List<Preset> = emptyList(), val defaultPresetId: String? = null)

/**
 * Presets and the default preset id, persisted to [file]. Writes are synchronous (small file) so a
 * new instance always sees the latest state; [scope] is reserved for the owner's lifecycle.
 * [telemetry] gets `preset_saved` (the number of presets, never a name) from [upsert].
 */
@Suppress("unused")
class PresetStore(file: Path, scope: CoroutineScope, private val telemetry: Telemetry = Telemetry.Noop) {
    private val file = JsonFile(file, StoreFile.serializer()) { StoreFile() }
    private val lock = Any()

    private val _presets: MutableStateFlow<List<Preset>>
    private val _default: MutableStateFlow<String?>

    val presets: StateFlow<List<Preset>> get() = _presets
    val defaultPresetId: StateFlow<String?> get() = _default

    /** Non-null when the file on disk could not be read or backed up; persisting is then disabled to protect it. */
    val persistenceError: StateFlow<String?> get() = this.file.error

    init {
        val loaded = this.file.load()
        _presets = MutableStateFlow(loaded.presets)
        _default = MutableStateFlow(loaded.defaultPresetId?.takeIf { id -> loaded.presets.any { it.id == id } })
    }

    fun upsert(p: Preset) {
        val total = synchronized(lock) {
            _presets.value = _presets.value.with(p)
            save()
            _presets.value.size
        }
        telemetry.track(TelemetryEvent.PresetSaved(total))
    }

    /** [upsert] for each of [presets], in one write and without `preset_saved` (an import sends its own event). */
    fun upsertAll(presets: List<Preset>) = mutate {
        _presets.value = presets.fold(_presets.value) { list, p -> list.with(p) }
    }

    private fun List<Preset>.with(p: Preset): List<Preset> =
        if (any { it.id == p.id }) map { if (it.id == p.id) p else it } else this + p

    fun delete(id: String) = mutate {
        _presets.value = _presets.value.filterNot { it.id == id }
        if (_default.value == id) _default.value = null
    }

    fun duplicate(id: String): Preset {
        synchronized(lock) {
            val src = _presets.value.first { it.id == id }
            val copy = src.copy(id = UUID.randomUUID().toString(), name = uniqueName(src.name))
            _presets.value += copy
            save()
            return copy
        }
    }

    fun setDefault(id: String?) = mutate {
        _default.value = id?.takeIf { want -> _presets.value.any { it.id == want } }
    }

    /** True when another preset (not [exceptId]) already has [name], ignoring case. */
    fun isNameTaken(name: String, exceptId: String? = null): Boolean =
        _presets.value.any { it.id != exceptId && it.name.equals(name, ignoreCase = true) }

    /** [base] if free, else "[base] (2)", "[base] (3)", ... */
    fun uniqueName(base: String): String {
        if (!isNameTaken(base)) return base
        var n = 2
        while (isNameTaken("$base ($n)")) n++
        return "$base ($n)"
    }

    private fun mutate(block: () -> Unit) = synchronized(lock) { block(); save() }

    private fun save() = file.save(StoreFile(_presets.value, _default.value))
}
