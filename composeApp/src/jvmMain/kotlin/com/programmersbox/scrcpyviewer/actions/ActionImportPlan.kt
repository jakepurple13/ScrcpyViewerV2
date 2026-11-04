package com.programmersbox.scrcpyviewer.actions

import com.programmersbox.scrcpyviewer.presets.ConflictChoice
import java.util.UUID

data class ActionImportItem(
    val action: CustomAction,
    val conflictsWith: CustomAction?,
    val choice: ConflictChoice,
    val include: Boolean,
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
     * One item per incoming action. A name clash (case-insensitive) with an existing action defaults to
     * [ConflictChoice.Replace]. Clashes between incoming actions are not conflicts: [apply] renames the later one.
     */
    fun plan(existing: List<CustomAction>, incoming: List<CustomAction>): List<ActionImportItem> = incoming.map { a ->
        val clash = existing.firstOrNull { it.name.equals(a.name, ignoreCase = true) }
        ActionImportItem(a, clash, ConflictChoice.Replace, include = true)
    }

    fun apply(
        existing: List<CustomAction>,
        items: List<ActionImportItem>,
        newId: () -> String = { UUID.randomUUID().toString() },
    ): List<CustomAction> {
        val result = existing.toMutableList()
        for (item in items) {
            if (!item.include) continue
            val conflict = item.conflictsWith
            if (conflict == null) {
                result += item.action.withId(newId()).withName(uniqueName(item.action.name, result))
                continue
            }
            when (item.choice) {
                ConflictChoice.Skip -> Unit
                ConflictChoice.Replace -> {
                    val i = result.indexOfFirst { it.id == conflict.id }
                    val replaced = item.action.withId(conflict.id)
                    if (i >= 0) result[i] = replaced else result += replaced
                }
                ConflictChoice.KeepBoth ->
                    result += item.action.withId(newId()).withName(uniqueName(item.action.name, result))
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
