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

    /**
     * Moves [id] within its own source group only. [toGroupIndex] counts that group's other members:
     * the action lands just before the [toGroupIndex]-th one, or after the last one when past the end.
     */
    fun moveInGroup(id: String, toGroupIndex: Int) = mutate {
        val list = _actions.value.toMutableList()
        val from = list.indexOfFirst { it.id == id }
        if (from < 0) return@mutate
        val item = list.removeAt(from)
        val members = list.withIndex().filter { it.value.source == item.source }.map { it.index }
        val n = toGroupIndex.coerceAtLeast(0)
        val at = when {
            members.isEmpty() -> list.size
            n < members.size -> members[n]
            else -> members.last() + 1
        }
        list.add(at, item)
        _actions.value = list
    }

    /** Marks [id] as user-owned without changing its position. */
    fun markAsUser(id: String) = mutate {
        _actions.value = _actions.value.map { if (it.id == id) it.withSource(ActionSource.User) else it }
    }

    /**
     * Applies AI-made [incoming] actions in one save. An id that existed before the call is updated in
     * place (position and source kept). Anything else is appended as [ActionSource.Ai] with a unique name,
     * keeping its id unless another action already has it.
     */
    fun applyInbox(incoming: List<CustomAction>): List<InboxChange> = synchronized(lock) {
        val existingIds = _actions.value.map { it.id }.toSet()
        val changes = incoming.map { action ->
            if (action.id in existingIds) {
                val existing = _actions.value.first { it.id == action.id }
                _actions.value = _actions.value.map { if (it.id == action.id) action.withSource(existing.source) else it }
                InboxChange.Updated(action.name)
            } else {
                val id = if (_actions.value.any { it.id == action.id }) UUID.randomUUID().toString() else action.id
                val name = uniqueName(action.name)
                _actions.value += action.withId(id).withName(name).withSource(ActionSource.Ai)
                InboxChange.Added(name)
            }
        }
        save()
        changes
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
