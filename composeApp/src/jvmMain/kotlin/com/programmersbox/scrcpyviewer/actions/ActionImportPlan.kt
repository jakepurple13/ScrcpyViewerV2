package com.programmersbox.scrcpyviewer.actions

import com.programmersbox.scrcpyviewer.presets.ConflictChoice
import java.util.IdentityHashMap
import java.util.UUID

data class ActionImportItem(
    val action: CustomAction,
    /** The action whose name this one shares: an existing action, or an earlier one in the same file ([inFile]). */
    val conflictsWith: CustomAction?,
    val choice: ConflictChoice,
    val include: Boolean,
    val inFile: Boolean = false,
)

fun CustomAction.withId(id: String): CustomAction = when (this) {
    is ToggleAction -> copy(id = id)
    is RunOnceAction -> copy(id = id)
}

fun CustomAction.withName(name: String): CustomAction = when (this) {
    is ToggleAction -> copy(name = name)
    is RunOnceAction -> copy(name = name)
}

object ActionImportPlan {
    /**
     * One item per incoming action. A name clash (case-insensitive) with an earlier action in the same file is a
     * conflict with that action, defaulting to [ConflictChoice.KeepBoth]; otherwise a clash with an existing action
     * defaults to [ConflictChoice.Replace].
     */
    fun plan(existing: List<CustomAction>, incoming: List<CustomAction>): List<ActionImportItem> =
        incoming.mapIndexed { i, a ->
            val earlier = incoming.subList(0, i).firstOrNull { it.name.equals(a.name, ignoreCase = true) }
            if (earlier != null) return@mapIndexed ActionImportItem(a, earlier, ConflictChoice.KeepBoth, include = true, inFile = true)
            val clash = existing.firstOrNull { it.name.equals(a.name, ignoreCase = true) }
            ActionImportItem(a, clash, ConflictChoice.Replace, include = true)
        }

    fun apply(
        existing: List<CustomAction>,
        items: List<ActionImportItem>,
        newId: () -> String = { UUID.randomUUID().toString() },
    ): List<CustomAction> {
        val result = existing.toMutableList()
        // The id each incoming action ended up with, for a later in-file clash that replaces it.
        val placed = IdentityHashMap<CustomAction, String>()
        fun add(action: CustomAction) {
            val added = action.withId(newId()).withName(uniqueName(action.name, result))
            result += added
            placed[action] = added.id
        }
        for (item in items) {
            if (!item.include) continue
            val conflict = item.conflictsWith
            if (conflict == null) {
                add(item.action)
                continue
            }
            when (item.choice) {
                ConflictChoice.Skip -> Unit
                ConflictChoice.Replace -> {
                    // An earlier incoming action that was not imported leaves nothing to replace.
                    val targetId = if (item.inFile) placed[conflict] else conflict.id
                    val i = result.indexOfFirst { it.id == targetId }
                    when {
                        targetId == null -> add(item.action)
                        i >= 0 -> result[i] = item.action.withId(targetId)
                        else -> result += item.action.withId(targetId)
                    }
                    if (targetId != null) placed[item.action] = targetId
                }
                ConflictChoice.KeepBoth -> add(item.action)
            }
        }
        return result
    }

    private fun uniqueName(base: String, taken: List<CustomAction>): String {
        if (taken.none { it.name.equals(base, ignoreCase = true) }) return base
        var n = 2
        while (taken.any { it.name.equals("$base ($n)", ignoreCase = true) }) n++
        return "$base ($n)"
    }
}
