package com.programmersbox.scrcpyviewer.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Which items to fold into the overflow menu so the bar fits [available] px. Returns indices in fold order.
 *
 * Priority 0 never folds. The highest priority folds first (ties: the later index first) until the visible widths,
 * the gaps between them and, once anything is folded, the overflow button plus its gap fit. [spacers] are the
 * indices that take no gap; by default every zero-width entry.
 */
fun foldBar(
    available: Int,
    widths: List<Int>,
    priorities: List<Int>,
    gap: Int,
    overflowWidth: Int,
    spacers: Set<Int> = widths.indices.filter { widths[it] == 0 }.toSet(),
): Set<Int> {
    val folded = linkedSetOf<Int>()
    fun used(): Int {
        val visible = widths.indices.filter { it !in folded && it !in spacers }
        val gaps = (visible.size - 1).coerceAtLeast(0) * gap
        val more = if (folded.isEmpty()) 0 else overflowWidth + gap
        return visible.sumOf { widths[it] } + gaps + more
    }
    while (used() > available) {
        val next = widths.indices
            .filter { it !in folded && it !in spacers && priorities[it] > 0 }
            .maxWithOrNull(compareBy<Int> { priorities[it] }.thenBy { it })
            ?: break
        folded += next
    }
    return folded
}

/**
 * One button-like slot of a [PriorityBar]. [priority] 0 never folds; larger folds first. A folded item shows as
 * [menuLabel] in the overflow menu, calling [onMenuClick] (null shows it disabled). A [grow] item measures at
 * [minWidth] and takes a share of any leftover space.
 *
 * [content] is composed twice, once to measure the item's natural width and once to display it, so it must be
 * free of side effects.
 */
class BarItem(
    val key: String,
    val priority: Int,
    val menuLabel: String,
    val onMenuClick: (() -> Unit)?,
    val minWidth: Dp = Dp.Unspecified,
    val grow: Boolean = false,
    val content: @Composable () -> Unit,
)

/** A weighted spacer slot in a [PriorityBar]'s items: it takes the leftover space. */
object BarSpacer

/**
 * A row that folds its lowest-priority items into a "More" overflow menu when it runs out of width.
 * [items] are [BarItem]s or [BarSpacer]s; callers put [BarSpacer] straight in the list (there is no scope function).
 * Item keys must be unique.
 *
 * The bar fills the incoming max width only when it holds a [BarSpacer] or a [grow] item; otherwise it wraps its
 * content. A non-null [forcedFolded] (item keys) replaces measuring: exactly those items fold, except priority 0 ones.
 */
@Composable
fun PriorityBar(items: List<Any>, modifier: Modifier = Modifier, gap: Dp = 8.dp, forcedFolded: Set<String>? = null) {
    val keys = items.mapNotNull { (it as? BarItem)?.key }
    require(keys.size == keys.toSet().size) { "PriorityBar item keys must be unique: $keys" }
    val reduceMotion = LocalReduceMotion.current
    SubcomposeLayout(modifier.clipToBounds()) { constraints ->
        val gapPx = gap.roundToPx()
        val loose = Constraints(maxWidth = Constraints.Infinity, maxHeight = constraints.maxHeight)
        val bars = items.map { it as? BarItem }
        val spacers = items.indices.filter { bars[it] == null }.toSet()
        val bounded = constraints.maxWidth != Constraints.Infinity
        val fill = bounded && items.indices.any { bars[it] == null || bars[it]!!.grow }

        val widths = items.indices.map { i ->
            val item = bars[i] ?: return@map 0
            if (item.grow && item.minWidth != Dp.Unspecified) item.minWidth.roundToPx()
            else subcompose("m:${item.key}") { Box(Modifier.clearAndSetSemantics { }) { item.content() } }
                .first().measure(loose).width
        }
        val overflowWidth = subcompose("m:more") {
            Box(Modifier.clearAndSetSemantics { }) { OverflowButton(emptyList(), pop = false, reduceMotion = true) }
        }.first().measure(loose).width

        val folded: Set<Int> = when {
            forcedFolded != null ->
                items.indices.filter { bars[it]?.key in forcedFolded && bars[it]!!.priority > 0 }.toSet()
            bounded ->
                foldBar(constraints.maxWidth, widths, bars.map { it?.priority ?: 0 }, gapPx, overflowWidth, spacers)
            else -> emptySet()
        }

        // Folded items stay composed while they animate out; their slot just stops being visible.
        val slots = items.indices.map { i ->
            val item = bars[i] ?: return@map null
            val hidden = i in folded
            subcompose("d:${item.key}") {
                val enter = if (reduceMotion) fadeIn(tween(120)) else expandHorizontally(layoutSpring()) + fadeIn(tween(150))
                val exit = if (reduceMotion) ExitTransition.None else shrinkHorizontally(layoutSpring()) + fadeOut(tween(150))
                AnimatedVisibility(visible = !hidden, enter = enter, exit = exit) {
                    Box(Modifier.clampToBudget().padding(end = gap), propagateMinConstraints = true) { item.content() }
                }
            }.firstOrNull() // empty once the exit animation has finished
        }
        val menu = folded.sorted().mapNotNull { bars[it] }
        val moreSlot = subcompose("d:more") {
            val enter = if (reduceMotion) fadeIn(tween(120)) else expandHorizontally(layoutSpring()) + fadeIn(tween(150))
            val exit = if (reduceMotion) fadeOut(tween(120)) else shrinkHorizontally(layoutSpring()) + fadeOut(tween(150))
            AnimatedVisibility(visible = menu.isNotEmpty(), enter = enter, exit = exit) {
                OverflowButton(menu, pop = true, reduceMotion = reduceMotion)
            }
        }.firstOrNull()

        // More first (its width animates too), then the items that are neither spacer nor growing, so spacers and
        // growers absorb every shrink and grow of the others.
        val morePlaceable = moreSlot?.measure(loose)
        val moreWidth = morePlaceable?.width ?: 0
        val placeables = arrayOfNulls<Placeable>(items.size)
        val growing = items.indices.filter { bars[it]?.grow == true && it !in folded }
        var used = moreWidth
        items.indices.forEach { i ->
            if (bars[i] != null && i !in growing && i !in folded) {
                placeables[i] = slots[i]?.measure(loose)
                used += placeables[i]?.width ?: 0
            }
        }
        val growBase = growing.sumOf { widths[it] + gapPx }
        // Exiting slots measure at their current animated width, never past the room that is left.
        var budget = if (bounded) (constraints.maxWidth - used - growBase).coerceAtLeast(0) else Constraints.Infinity
        items.indices.forEach { i ->
            if (bars[i] != null && i in folded) {
                val slot = slots[i] ?: return@forEach
                val p = slot.measure(Constraints(maxWidth = budget, maxHeight = constraints.maxHeight))
                placeables[i] = p
                used += p.width
                if (bounded) budget -= p.width
            }
        }
        val lastIsItem = items.indices.reversed().firstOrNull { bars[it] == null || placeables[it] != null }
            ?.let { bars[it] != null } ?: false
        val trailingGap = if (morePlaceable == null && lastIsItem) gapPx else 0

        val sharers = spacers.size + growing.size
        val leftover = if (fill) (constraints.maxWidth - used - growBase + trailingGap).coerceAtLeast(0) else 0
        val share = if (sharers == 0) 0 else leftover / sharers
        growing.forEach { i ->
            val w = widths[i] + gapPx + share
            placeables[i] = slots[i]?.measure(Constraints(minWidth = w, maxWidth = w, maxHeight = constraints.maxHeight))
            used += placeables[i]?.width ?: 0
        }
        val contentWidth = used + share * spacers.size

        val height = (placeables.filterNotNull().maxOfOrNull { it.height } ?: 0)
            .coerceAtLeast(morePlaceable?.height ?: 0)
            .coerceIn(constraints.minHeight, constraints.maxHeight)
        val width = if (fill) constraints.maxWidth
        else (contentWidth - trailingGap).coerceIn(constraints.minWidth, if (bounded) constraints.maxWidth else Int.MAX_VALUE)

        layout(width, height) {
            var x = 0
            items.indices.forEach { i ->
                val p = placeables[i]
                if (bars[i] == null) {
                    x += share
                } else if (p != null) {
                    p.place(x, Alignment.CenterVertically.align(p.height, height))
                    x += p.width
                }
            }
            morePlaceable?.place(x, Alignment.CenterVertically.align(morePlaceable.height, height))
        }
    }
}

/** Reports the content's natural width, clamped to the room it was offered; fixed constraints pass through. */
private fun Modifier.clampToBudget(): Modifier = layout { measurable, c ->
    val p = measurable.measure(if (c.minWidth > 0) c else c.copy(maxWidth = Constraints.Infinity))
    layout(minOf(p.width, c.maxWidth), p.height) { p.place(0, 0) }
}.clipToBounds()

@Composable
private fun OverflowButton(folded: List<BarItem>, pop: Boolean, reduceMotion: Boolean) {
    var open by remember { mutableStateOf(false) }
    // The pop: 0.3 -> 1.15 -> 1. Reduced motion has none; the slot's fade covers it.
    val scale = remember { Animatable(if (pop && !reduceMotion) 0.3f else 1f) }
    LaunchedEffect(pop, reduceMotion) {
        if (!pop || reduceMotion) return@LaunchedEffect
        scale.animateTo(1.15f, tween(140))
        scale.animateTo(1f, tween(100))
    }
    Box(Modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value }) {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            folded.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.menuLabel) },
                    enabled = item.onMenuClick != null,
                    onClick = {
                        open = false
                        item.onMenuClick?.invoke()
                    },
                )
            }
        }
    }
}
