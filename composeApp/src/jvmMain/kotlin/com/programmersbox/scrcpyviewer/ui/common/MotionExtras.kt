package com.programmersbox.scrcpyviewer.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** A "back out" curve: overshoots past the target by about 10% and settles, for things that pop in. */
val OvershootEasing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)

/** Dialog entrance: scale 0.92 → 1 and fade in. */
const val DIALOG_ENTRANCE_MILLIS = 300
private const val DIALOG_ENTRANCE_SCALE = 0.92f

/** Staggered list entrance: each item fades in and rises [STAGGER_RISE_DP] over [STAGGER_ITEM_MILLIS]. */
const val STAGGER_ITEM_MILLIS = 300
const val STAGGER_STEP_MILLIS = 45
const val STAGGER_RISE_DP = 8

/** Items past this index start with the last item's delay, so a long list is not slow to fill in. */
const val STAGGER_MAX_STEPS = 7

/** How long item [index] waits before its entrance: [STAGGER_STEP_MILLIS] per index, capped at [STAGGER_MAX_STEPS]. */
fun staggerDelayMillis(index: Int): Int = index.coerceIn(0, STAGGER_MAX_STEPS) * STAGGER_STEP_MILLIS

/**
 * Scales the content from 0.92 to 1 about its center while fading it in, once, when it first appears. For dialogs
 * that open on their own (no button to grow from). Runs in the layer, so the content is not re-laid out per frame.
 */
fun Modifier.dialogEntrance(): Modifier = this then DialogEntranceElement

private data object DialogEntranceElement : ModifierNodeElement<EntranceNode>() {
    override fun create() = EntranceNode(trigger = Unit, delayMillis = 0, riseDp = 0, fromScale = DIALOG_ENTRANCE_SCALE, millis = DIALOG_ENTRANCE_MILLIS)
    override fun update(node: EntranceNode) = Unit
    override fun InspectorInfo.inspectableProperties() {
        name = "dialogEntrance"
    }
}

/**
 * One item of a staggered list: fades in and rises 8dp after [staggerDelayMillis] of [index]. It plays when the item
 * first appears and again whenever [trigger] changes (pass the query or the result keys), not on other recompositions.
 */
fun Modifier.staggeredEntrance(index: Int, trigger: Any?): Modifier = this then StaggerElement(index, trigger)

private data class StaggerElement(val index: Int, val trigger: Any?) : ModifierNodeElement<EntranceNode>() {
    override fun create() = EntranceNode(trigger, staggerDelayMillis(index), STAGGER_RISE_DP, fromScale = 1f, millis = STAGGER_ITEM_MILLIS)
    override fun update(node: EntranceNode) = node.update(trigger, staggerDelayMillis(index))
    override fun InspectorInfo.inspectableProperties() {
        name = "staggeredEntrance"
        properties["index"] = index
        properties["trigger"] = trigger
    }
}

private class EntranceNode(
    private var trigger: Any?,
    private var delayMillis: Int,
    private val riseDp: Int,
    private val fromScale: Float,
    private val millis: Int,
) : Modifier.Node(), LayoutModifierNode {
    private val progress = Animatable(0f)
    private var job: Job? = null

    override fun onAttach() = play()

    fun update(trigger: Any?, delayMillis: Int) {
        this.delayMillis = delayMillis
        if (trigger == this.trigger) return
        this.trigger = trigger
        play()
    }

    private fun play() {
        job?.cancel()
        job = coroutineScope.launch {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(millis, delayMillis = delayMillis, easing = EmphasizedDecelerate))
        }
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        val rise = riseDp.dp.toPx()
        return layout(placeable.width, placeable.height) {
            // Read in the layer block, so each frame only redraws.
            placeable.placeWithLayer(0, 0) {
                val p = progress.value
                alpha = p
                if (rise != 0f) translationY = (1f - p) * rise
                if (fromScale != 1f) {
                    val s = fromScale + (1f - fromScale) * p
                    scaleX = s
                    scaleY = s
                    transformOrigin = TransformOrigin.Center
                }
            }
        }
    }
}
