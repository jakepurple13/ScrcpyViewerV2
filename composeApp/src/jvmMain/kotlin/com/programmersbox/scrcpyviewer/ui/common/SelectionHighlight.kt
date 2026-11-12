package com.programmersbox.scrcpyviewer.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.flow.filterNotNull

/**
 * Tracks the selected row's bounds (relative to the list container) and the animated rect drawn behind it.
 * [current] is null until a row has been selected; the first placement snaps, later ones slide.
 */
@Stable
class SelectionHighlightState {
    private var container: LayoutCoordinates? = null
    internal var target by mutableStateOf<Rect?>(null)
        private set

    /** The rect the highlight is drawn at right now (mid-animation while it slides). */
    var current by mutableStateOf<Rect?>(null)
        internal set

    internal fun onContainerPlaced(coordinates: LayoutCoordinates) {
        container = coordinates
    }

    internal fun onRowPlaced(row: LayoutCoordinates) {
        val parent = container?.takeIf { it.isAttached } ?: return
        if (!row.isAttached) return
        target = parent.localBoundingBoxOf(row)
    }
}

@Composable
fun rememberSelectionHighlight(): SelectionHighlightState = remember { SelectionHighlightState() }

/** Marks the selected row; its bounds become the highlight target. Unselected rows are ignored. */
fun Modifier.selectionHighlightTarget(state: SelectionHighlightState, selected: Boolean): Modifier =
    if (selected) onPlaced { state.onRowPlaced(it) } else this

/**
 * On the list container: draws [color] in [shape] behind the content at the selected row, sliding
 * between rows with [layoutSpring]. Snaps on first placement and under reduced motion.
 */
fun Modifier.selectionHighlight(state: SelectionHighlightState, color: Color, shape: Shape): Modifier = composed {
    val reduceMotion = LocalReduceMotion.current
    LaunchedEffect(state, reduceMotion) {
        var animatable: Animatable<Rect, *>? = null
        snapshotFlow { state.target }.filterNotNull().collect { rect ->
            val running = animatable
            if (running == null || reduceMotion) {
                animatable = Animatable(rect, Rect.VectorConverter)
                state.current = rect
            } else {
                running.animateTo(rect, layoutSpring()) { state.current = value }
            }
        }
    }
    onPlaced { state.onContainerPlaced(it) }.drawBehind {
        val rect = state.current ?: return@drawBehind
        val outline: Outline = shape.createOutline(rect.size, LayoutDirection.Ltr, Density(density, fontScale))
        translate(rect.left, rect.top) { drawOutline(outline, color) }
    }
}
