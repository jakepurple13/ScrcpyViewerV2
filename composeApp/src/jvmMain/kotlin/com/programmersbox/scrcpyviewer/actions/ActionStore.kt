package com.programmersbox.scrcpyviewer.actions

import com.programmersbox.scrcpyviewer.state.JsonFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.nio.file.Path
import java.util.UUID

@Serializable
private data class StoreFile(val actions: List<CustomAction> = emptyList())

/** Custom quick actions in display order, persisted synchronously to [file]. */
class ActionStore(file: Path) {
    private val file = JsonFile(file, StoreFile.serializer()) { StoreFile() }
    private val lock = Any()
    private val _actions = MutableStateFlow(this.file.load().actions)

    val actions: StateFlow<List<CustomAction>> get() = _actions

    /** Non-null when the file on disk could not be read or backed up, or the last write failed. */
    val persistenceError: StateFlow<String?> get() = this.file.error

    fun upsert(action: CustomAction) = mutate {
        val list = _actions.value
        _actions.value = if (list.any { it.id == action.id }) list.map { if (it.id == action.id) action else it } else list + action
    }

    fun delete(id: String) = mutate { _actions.value = _actions.value.filterNot { it.id == id } }

    fun duplicate(id: String): CustomAction = synchronized(lock) {
        val src = _actions.value.first { it.id == id }
        val copy = src.withId(UUID.randomUUID().toString()).withName(uniqueName(src.name))
        _actions.value += copy
        save()
        copy
    }

    /** Moves [id] so it ends at [toIndex] (clamped) in the resulting list. */
    fun move(id: String, toIndex: Int) = mutate {
        val list = _actions.value.toMutableList()
        val from = list.indexOfFirst { it.id == id }
        if (from < 0) return@mutate
        val item = list.removeAt(from)
        list.add(toIndex.coerceIn(0, list.size), item)
        _actions.value = list
    }

    fun replaceAll(actions: List<CustomAction>) = mutate { _actions.value = actions }

    /** True when another action (not [exceptId]) already has [name], ignoring case. */
    fun isNameTaken(name: String, exceptId: String? = null): Boolean =
        _actions.value.any { it.id != exceptId && it.name.equals(name, ignoreCase = true) }

    /** [base] if free, else "[base] (2)", "[base] (3)", ... */
    fun uniqueName(base: String): String {
        if (!isNameTaken(base)) return base
        var n = 2
        while (isNameTaken("$base ($n)")) n++
        return "$base ($n)"
    }

    private fun mutate(block: () -> Unit) = synchronized(lock) { block(); save() }

    private fun save() = file.save(StoreFile(_actions.value))
}
