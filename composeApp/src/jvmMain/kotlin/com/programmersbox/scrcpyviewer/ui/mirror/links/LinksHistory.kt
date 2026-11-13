package com.programmersbox.scrcpyviewer.ui.mirror.links

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.programmersbox.scrcpyviewer.links.LinkEntry
import com.programmersbox.scrcpyviewer.ui.common.CLOSE_MILLIS
import com.programmersbox.scrcpyviewer.ui.common.OPEN_MILLIS
import com.programmersbox.scrcpyviewer.ui.theme.TextButton
import com.programmersbox.scrcpyviewer.ui.theme.warnColor
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/** A history list item: a link (starred or recent), or the divider between the starred and the recent ones. */
internal sealed interface HistoryItem {
    val key: Any

    data class Entry(val entry: LinkEntry, val starred: Boolean) : HistoryItem {
        // Starring keeps the key: the row moves rather than being replaced.
        override val key: Any get() = EntryKey(entry.link, entry.packageName)
    }

    data object Divider : HistoryItem {
        override val key: Any get() = "divider"
    }
}

private data class EntryKey(val link: String, val packageName: String?)

/** Starred first, then the divider when there are both, then this device's recent links. */
internal fun historyItems(history: LinkHistory): List<HistoryItem> = buildList {
    history.starred.forEach { add(HistoryItem.Entry(it, starred = true)) }
    if (history.starred.isNotEmpty() && history.recent.isNotEmpty()) add(HistoryItem.Divider)
    history.recent.forEach { add(HistoryItem.Entry(it, starred = false)) }
}

/**
 * One shown item. [leaving]: gone from the list, folding away (it stays in place until its exit ends); [entering]: new
 * since the list first showed, so it expands in.
 */
internal class Slot<T>(val key: Any, var item: T, val entering: Boolean) {
    var leaving = false
}

private class Slots<T> {
    var shown: List<Slot<T>> = emptyList()
    var started = false

    /** Goes up each time surviving items change their order: rows then slide to their new places (FLIP). */
    var reorders = 0
}

/**
 * The items to show for [items]: the new list, plus items that just left ([Slot.leaving]), kept where they were until
 * their exit ends and they are dropped. The second value is true when items that stayed changed order.
 */
internal fun <T> mergeSlots(old: List<Slot<T>>, items: List<T>, key: (T) -> Any, animateNew: Boolean): Pair<List<Slot<T>>, Boolean> {
    val byKey = old.associateBy { it.key }
    val keys = items.map(key)
    val keySet = keys.toHashSet()
    val stayedBefore = old.filter { !it.leaving && it.key in keySet }.map { it.key }
    val result = items.mapTo(mutableListOf()) { item ->
        val k = key(item)
        byKey[k]?.also {
            it.item = item
            it.leaving = false
        } ?: Slot(k, item, entering = animateNew)
    }
    // Items that left stay after the item that came before them, so they fold away where they were.
    old.forEachIndexed { index, slot ->
        if (slot.key in keySet) return@forEachIndexed
        slot.leaving = true
        val before = old.subList(0, index).lastOrNull { prev -> result.any { it === prev } }
        val at = if (before == null) 0 else result.indexOfFirst { it === before } + 1
        result.add(at, slot)
    }
    val stayedSet = stayedBefore.toHashSet()
    val stayedNow = keys.filter { it in stayedSet }
    return result to (stayedBefore != stayedNow)
}

/**
 * The History heading with Clear, and the rows: starred first (all devices), a divider, then this device's recent
 * links. New rows expand in (420 ms); starring moves a row to its new place with a FLIP slide (420 ms); Clear folds the
 * recent rows away (300 ms). Without [motion] rows appear and go with a short fade and move at once.
 */
@Composable
internal fun HistorySection(links: LinksState, onEvent: (LinksEvent) -> Unit, motion: Boolean, now: () -> Long) {
    val history = links.history
    val items = historyItems(history)
    val holder = remember { Slots<HistoryItem>() }
    var pruned by remember { mutableIntStateOf(0) }
    // Merged during composition on purpose: the rows drawn this frame must already include the leaving ones. Only
    // [holder], a plain object no one else reads, is written (never snapshot state), and the merge is idempotent for the
    // same [items], so a repeated pass changes nothing.
    val shown = remember(items, pruned) {
        val (merged, reordered) = mergeSlots(holder.shown, items, HistoryItem::key, animateNew = holder.started)
        holder.shown = merged
        holder.started = true
        if (reordered) holder.reorders++
        merged
    }
    val reorders = holder.reorders
    // Re-reads the clock each half minute, so "<n> min ago" moves on.
    val clock by produceState(now()) {
        while (true) {
            delay(30_000)
            value = now()
        }
    }
    AnimatedVisibility(items.isNotEmpty(), enter = expandIn(ROW_IN_MILLIS, motion), exit = foldOut(FOLD_MILLIS, motion)) {
        Row(Modifier.padding(top = HISTORY_TOP).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("History", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = { onEvent(LinksEvent.Clear) }, enabled = history.recent.isNotEmpty()) { Text("Clear") }
        }
    }
    Column(Modifier.fillMaxWidth()) {
        for (slot in shown) {
            key(slot.key) {
                val visible = remember { MutableTransitionState(!slot.entering) }
                visible.targetState = !slot.leaving
                if (slot.leaving) {
                    LaunchedEffect(Unit) {
                        snapshotFlow { visible.isIdle && !visible.currentState }.first { it }
                        holder.shown = holder.shown.filterNot { it === slot }
                        pruned++
                    }
                }
                AnimatedVisibility(
                    visibleState = visible,
                    modifier = Modifier.flip(reorders, motion).testTag(historyTag(slot.item)),
                    enter = expandIn(ROW_IN_MILLIS, motion),
                    exit = foldOut(FOLD_MILLIS, motion),
                ) {
                    when (val item = slot.item) {
                        HistoryItem.Divider -> HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        is HistoryItem.Entry -> HistoryRow(item.entry, item.starred, onEvent, clock, motion)
                    }
                }
            }
        }
    }
}

private class Flip {
    var lastY = Float.NaN
    var seen = 0
    var from = 0f
}

/**
 * FLIP: when [reorders] changed since this item was last placed and the item now sits elsewhere in its column, it
 * starts drawn where it was and slides to its new place over [FLIP_MILLIS] (a slide under way carries on from where it
 * is). Other moves (rows above growing or folding) follow the layout as it changes.
 */
@Composable
private fun Modifier.flip(reorders: Int, motion: Boolean): Modifier {
    val flip = remember { Flip().also { it.seen = reorders } }
    val progress = remember { mutableFloatStateOf(1f) }
    var runs by remember { mutableIntStateOf(0) }
    LaunchedEffect(runs) {
        if (runs == 0) return@LaunchedEffect
        var start = -1L
        while (progress.floatValue < 1f) {
            withFrameNanos { t ->
                if (start < 0) start = t
                progress.floatValue = ((t - start) / (FLIP_MILLIS * 1_000_000f)).coerceAtMost(1f)
            }
        }
    }
    return onPlaced { coordinates ->
        // Called before the inner layout below places the row: the offset it reads is already the new one.
        val y = coordinates.positionInParent().y
        if (flip.seen != reorders) {
            flip.seen = reorders
            if (motion && !flip.lastY.isNaN() && y != flip.lastY) {
                val current = flip.from * (1f - FastOutSlowInEasing.transform(progress.floatValue))
                flip.from = current + (flip.lastY - y)
                progress.floatValue = 0f
                runs++
            }
        }
        flip.lastY = y
    }.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) {
            val offset = flip.from * (1f - FastOutSlowInEasing.transform(progress.floatValue))
            placeable.placeRelative(0, offset.roundToInt())
        }
    }
}

/** A tooltip of [text] on hover, in the app's tooltip style. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tip(text: String, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    TooltipArea(
        tooltip = {
            Surface(shape = RoundedCornerShape(4.dp), color = colors.inverseSurface) {
                Text(
                    text, Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall, color = colors.inverseOnSurface,
                )
            }
        },
    ) { content() }
}

/**
 * One history row: outcome dot, the link, "<app or No app found> · <time ago>", Open again (shown on hover or focus)
 * and the star. Clicking the row puts its link in the field.
 */
@Composable
private fun HistoryRow(entry: LinkEntry, starred: Boolean, onEvent: (LinksEvent) -> Unit, now: Long, motion: Boolean) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var focusWithin by remember { mutableStateOf(false) }
    val again by animateFloatAsState(if (hovered || focusWithin) 1f else 0f, tween(if (motion) 200 else 0))
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .onFocusChanged { focusWithin = it.hasFocus }
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = androidx.compose.material3.ripple(), role = Role.Button) {
                onEvent(LinksEvent.Fill(entry))
            }
            .padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(if (entry.ok) colors.primary else colors.error, CircleShape))
        Column(Modifier.weight(1f).padding(start = 10.dp, top = 4.dp, bottom = 4.dp)) {
            Text(
                entry.link, fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, style = MaterialTheme.typography.bodySmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val who = if (entry.ok) entry.appLabel ?: entry.packageName ?: "Opened" else "No app found"
            Text(
                "$who · ${timeAgo(entry.at, now)}", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Tip("Open again") {
            IconButton(onClick = { onEvent(LinksEvent.Reopen(entry)) }, Modifier.size(32.dp).graphicsLayer { alpha = again }) {
                Icon(Icons.Filled.Replay, contentDescription = "Open again", Modifier.size(18.dp))
            }
        }
        val name = if (starred) "Unstar" else "Star"
        val tint by animateColorAsState(if (starred) warnColor() else colors.onSurfaceVariant, tween(if (motion) OPEN_MILLIS else 0))
        Tip(name) {
            IconButton(onClick = { onEvent(LinksEvent.ToggleStar(entry)) }, Modifier.size(32.dp)) {
                Crossfade(starred, animationSpec = tween(if (motion) CLOSE_MILLIS else 0), label = "star") { on ->
                    Icon(if (on) Icons.Filled.Star else Icons.Filled.StarBorder, contentDescription = name, Modifier.size(18.dp), tint = tint)
                }
            }
        }
    }
}
