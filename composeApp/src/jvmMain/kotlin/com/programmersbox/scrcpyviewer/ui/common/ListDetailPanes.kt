package com.programmersbox.scrcpyviewer.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Keeps the draggable divider inside [minFirst]..[maxFirst] (list pane width, px). The layout
 * calls [consume] with each raw drag delta and applies what it returns; [onFirstPaneMeasured]
 * resyncs with the laid-out list width.
 */
class PaneWidthLimits {
    private var minFirst = 0f
    private var maxFirst = Float.MAX_VALUE
    private var firstWidth = Float.NaN

    fun update(minFirst: Float, maxFirst: Float) {
        this.minFirst = minFirst
        this.maxFirst = maxFirst
    }

    fun onFirstPaneMeasured(width: Int) {
        firstWidth = width.toFloat()
    }

    /** Forgets the laid-out width (the list is not a side pane in a narrow window). */
    fun reset() {
        firstWidth = Float.NaN
    }

    /** The laid-out list width in px, or null before the two-pane layout has been measured. */
    val laidOutFirstWidth: Int? get() = if (firstWidth.isNaN()) null else firstWidth.toInt()

    /** The list width after a keyboard step of [deltaPx], clamped to the limits; null before layout. */
    fun stepped(deltaPx: Float): Int? {
        if (firstWidth.isNaN()) return null
        return (firstWidth + deltaPx).coerceAtMost(maxFirst).coerceAtLeast(minFirst).toInt()
    }

    fun consume(delta: Float): Float {
        if (firstWidth.isNaN()) return delta
        val target = (firstWidth + delta).coerceAtMost(maxFirst).coerceAtLeast(minFirst)
        val applied = target - firstWidth
        firstWidth = target
        return applied
    }
}

/** State of a list-detail screen; hoist it above the navigation so it outlives tab switches. */
@Stable
class ListDetailPanes internal constructor(internal val limits: PaneWidthLimits) {
    /** The list pane's laid-out width in px in the two-pane layout, else null. */
    val listWidthPx: Int? get() = limits.laidOutFirstWidth

    /**
     * The list width the user dragged the divider to in [AdaptiveListDetail]'s two-pane layout, or null
     * for the screen's preferred width.
     */
    var listWidth: Dp? by mutableStateOf(null)

    /**
     * The selected item's detail is pushed over the list: a click on an item sets it (at any width), Back clears it,
     * and it starts clear, so a narrow window opens on the list. Only narrow windows show it (see
     * [AdaptiveListDetail]); two panes ignore it.
     */
    var pushed: Boolean by mutableStateOf(false)

    /** Opens the selected item over the list in a narrow window; call it when the user clicks an item. */
    fun push() {
        pushed = true
    }

    /** Returns a narrow window to the list; the selection stays. */
    fun back() {
        pushed = false
    }
}

@Composable
fun rememberListDetailPanes(): ListDetailPanes = remember { ListDetailPanes(PaneWidthLimits()) }

/** Space between the panes; the drag handle sits in it. */
internal val DividerWidth = 16.dp

/** How far Left/Right move the focused divider. */
internal val KeyboardStep = 24.dp

/** Back affordance for a detail pane header while the detail is pushed over the list. */
@Composable
fun DetailBackButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onBack, modifier = modifier) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to list")
    }
}

/**
 * A detail header row ([content], given the modifier for its width) with a [DetailBackButton] at its start while
 * [onBack] is set. The arrow fades and the header widens or narrows with [layoutSpring] as it comes and goes; reduced
 * motion only fades it.
 */
@Composable
fun BackArrowRow(onBack: (() -> Unit)?, content: @Composable (Modifier) -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    // The arrow leaving still needs something to call.
    val lastBack = remember { arrayOf(onBack) }
    if (onBack != null) lastBack[0] = onBack
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AnimatedVisibility(
            visible = onBack != null,
            enter = if (reduceMotion) fadeIn(tween(150)) else fadeIn(tween(220)) + expandHorizontally(layoutSpring()),
            exit = if (reduceMotion) fadeOut(tween(150)) else fadeOut(tween(120)) + shrinkHorizontally(layoutSpring()),
        ) {
            // Pulled into the header's start padding, so the arrow lines up with the text under it.
            DetailBackButton({ lastBack[0]?.invoke() }, Modifier.pullStart(ArrowInset).padding(end = 4.dp))
        }
        content(Modifier.weight(1f))
    }
}

/** The space around the arrow icon inside its 48 dp button. */
private val ArrowInset = 12.dp

/** Lays the content out [by] further toward the start, taking that much less width. */
private fun Modifier.pullStart(by: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val pull = by.roundToPx().coerceAtMost(placeable.width)
    layout(placeable.width - pull, placeable.height) { placeable.placeRelative(-pull, 0) }
}
