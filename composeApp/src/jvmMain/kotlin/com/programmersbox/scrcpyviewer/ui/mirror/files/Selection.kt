package com.programmersbox.scrcpyviewer.ui.mirror.files

/**
 * Selected rows of the Files tab. [anchor] is where Shift ranges start; [cursor] is the focused row that the arrow
 * keys move from. Every function takes the visible row order and returns a new selection.
 */
data class Selection(
    val names: Set<String> = emptySet(),
    val anchor: String? = null,
    val cursor: String? = null,
) {
    fun click(order: List<String>, name: String): Selection = Selection(setOf(name), name, name)

    /** Selects from [anchor] (or [name] when there is none) to [name], in [order]. */
    fun shiftClick(order: List<String>, name: String): Selection {
        val from = anchor?.takeIf { it in order } ?: name
        return Selection(range(order, from, name), from, name)
    }

    fun toggle(name: String): Selection = Selection(if (name in names) names - name else names + name, name, name)

    fun all(order: List<String>): Selection = copy(names = order.toSet())

    /** Moves [cursor] by [delta] rows; with [extend] the selection runs from [anchor] to the new row. */
    fun move(order: List<String>, delta: Int, extend: Boolean): Selection {
        if (order.isEmpty()) return this
        val at = order.indexOf(cursor)
        val target = when {
            at >= 0 -> order[(at + delta).coerceIn(0, order.lastIndex)]
            delta >= 0 -> order.first()
            else -> order.last()
        }
        if (!extend) return Selection(setOf(target), target, target)
        val from = anchor?.takeIf { it in order } ?: cursor?.takeIf { it in order } ?: target
        return Selection(range(order, from, target), from, target)
    }

    fun clear(): Selection = Selection()

    /** Drops names that are no longer in [order] (after a refresh, filter or hide). */
    fun retain(order: List<String>): Selection {
        val present = order.toSet()
        return Selection(names.filterTo(LinkedHashSet()) { it in present }, anchor?.takeIf { it in present }, cursor?.takeIf { it in present })
    }

    private fun range(order: List<String>, a: String, b: String): Set<String> {
        val i = order.indexOf(a)
        val j = order.indexOf(b)
        if (i < 0 || j < 0) return setOf(b)
        return order.subList(minOf(i, j), maxOf(i, j) + 1).toSet()
    }
}
