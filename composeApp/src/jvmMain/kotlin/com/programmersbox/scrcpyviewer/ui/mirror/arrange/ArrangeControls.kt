package com.programmersbox.scrcpyviewer.ui.mirror.arrange

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AutoAwesomeMotion
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.SpaceDashboard
import androidx.compose.material.icons.outlined.ViewWeek
import androidx.compose.material.icons.outlined.WebAsset
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.Os
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedDecelerate
import com.programmersbox.scrcpyviewer.ui.mirror.follow.FollowMotion
import com.programmersbox.scrcpyviewer.ui.shortcuts.Shortcut
import com.programmersbox.scrcpyviewer.ui.shortcuts.ShortcutAction
import androidx.compose.runtime.withFrameMillis
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

const val ARRANGE_LABEL = "Arrange mirror windows"
const val ARRANGE_TITLE = "ARRANGE MIRROR WINDOWS"
const val KEEP_TILED = "Keep tiled as windows open and close"
const val NOT_MOVABLE_NOTE = "Your desktop doesn't let apps move windows"

/** The picked layout's check mark scales in over this long. */
const val CHECK_IN_MILLIS = 300

/**
 * After a pick, the menu stays open this long so the check mark is seen moving (most of its scale-in), then closes and
 * the windows move. As in the approved mockup.
 */
const val PICK_CLOSE_DELAY_MILLIS = 160L

/** The layout's entry in the mirror window's Arrange menu. */
val ArrangeLayout.menuLabel: String
    get() = when (this) {
        ArrangeLayout.AsPlaced -> "As placed"
        ArrangeLayout.Grid -> "Grid"
        ArrangeLayout.SideBySide -> "Side by side"
        ArrangeLayout.Focus -> "Focus this window"
        ArrangeLayout.Cascade -> "Cascade"
    }

/** The shortcut action that applies the layout (its id is the key in `Settings.shortcuts`). */
val ArrangeLayout.shortcutAction: ShortcutAction
    get() = when (this) {
        ArrangeLayout.AsPlaced -> ShortcutAction.ArrangeFree
        ArrangeLayout.Grid -> ShortcutAction.ArrangeGrid
        ArrangeLayout.SideBySide -> ShortcutAction.ArrangeRow
        ArrangeLayout.Focus -> ShortcutAction.ArrangeFocus
        ArrangeLayout.Cascade -> ShortcutAction.ArrangeCascade
    }

private val ArrangeLayout.icon: ImageVector
    get() = when (this) {
        ArrangeLayout.AsPlaced -> Icons.Outlined.WebAsset
        ArrangeLayout.Grid -> Icons.Outlined.GridView
        ArrangeLayout.SideBySide -> Icons.Outlined.ViewWeek
        ArrangeLayout.Focus -> Icons.Outlined.SpaceDashboard
        ArrangeLayout.Cascade -> Icons.Outlined.AutoAwesomeMotion
    }

/** One layout entry of the Arrange menu: [checked] on the current layout, [shortcut] its key label once set. */
data class ArrangeItem(
    val layout: ArrangeLayout,
    val label: String,
    val checked: Boolean,
    val shortcut: String?,
    val enabled: Boolean,
)

/** The menu's five layout entries for [state], with the shortcuts in [shortcuts] (`Settings.shortcuts`). */
fun arrangeItems(state: ArrangeState, shortcuts: Map<String, String>, mac: Boolean = Os.isMac): List<ArrangeItem> =
    ArrangeLayout.entries.map { layout ->
        ArrangeItem(
            layout = layout,
            label = layout.menuLabel,
            checked = layout == state.layout,
            shortcut = shortcuts[layout.shortcutAction.id]?.let(Shortcut::decode)?.label(mac),
            enabled = state.movable,
        )
    }

/**
 * What a mirror window's title bar needs for its Arrange button. [onPick] applies a layout with this window as the
 * Focus window.
 */
data class ArrangeStrip(
    val state: ArrangeState,
    val shortcuts: Map<String, String>,
    val onPick: (ArrangeLayout) -> Unit,
    val onKeepTiled: (Boolean) -> Unit,
    /** The system asks for reduced motion: the check mark fades instead of scaling, and the menu closes at once. */
    val reduceMotion: Boolean = false,
)

/** The Arrange control for a window, only while at least two mirror windows are open ([openWindows]). */
fun arrangeStrip(
    openWindows: Int,
    state: ArrangeState,
    shortcuts: Map<String, String>,
    onPick: (ArrangeLayout) -> Unit,
    onKeepTiled: (Boolean) -> Unit,
    reduceMotion: Boolean = false,
): ArrangeStrip? =
    if (openWindows < 2) null else ArrangeStrip(state, shortcuts, onPick, onKeepTiled, reduceMotion)

/** [arrangeStrip] for the window of [serial]: picking Focus puts this window in the large area. */
@Composable
fun rememberArrangeStrip(
    serial: String,
    arranger: WindowArranger,
    open: StateFlow<Map<String, *>>,
    shortcuts: Map<String, String>,
    reduceMotion: Boolean,
): ArrangeStrip? {
    val state by arranger.state.collectAsState()
    val sessions by open.collectAsState()
    val onPick = remember(serial, arranger) { { layout: ArrangeLayout -> arranger.apply(layout, focus = serial) } }
    val onKeepTiled = remember(arranger) { { on: Boolean -> arranger.setKeepTiled(on) } }
    return arrangeStrip(sessions.size, state, shortcuts, onPick, onKeepTiled, reduceMotion)
}

/** The title bar's Arrange icon button (a grid) and its menu. */
@Composable
fun ArrangeButton(arrange: ArrangeStrip, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { open = !open }, Modifier.size(32.dp)) {
            Icon(Icons.Outlined.GridView, contentDescription = ARRANGE_LABEL, Modifier.size(20.dp))
        }
        ArrangeMenu(open, { open = false }, arrange)
    }
}

/**
 * The Arrange menu: the title, the five layouts (a check on the current one, its shortcut when set) and Keep tiled.
 * When the desktop does not let the app move windows ([ArrangeState.movable] false) the entries are disabled under
 * [NOT_MOVABLE_NOTE].
 *
 * Picking a layout moves the check mark to it at once (it scales in over [CHECK_IN_MILLIS] while the old one scales
 * out), then after [PICK_CLOSE_DELAY_MILLIS] the menu closes and [ArrangeStrip.onPick] runs, so the windows move as
 * the menu fades. Keep tiled toggles in place and leaves the menu open.
 */
@Composable
fun ArrangeMenu(expanded: Boolean, onDismiss: () -> Unit, arrange: ArrangeStrip) {
    val colors = MaterialTheme.colorScheme
    // Outlives the menu's content, which leaves the composition once the menu has closed.
    val scope = rememberCoroutineScope()
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        // The content is composed afresh each time the menu opens: no pick pending, the check on the current layout.
        var picked by remember { mutableStateOf<ArrangeLayout?>(null) }
        val state = arrange.state
        Text(
            ARRANGE_TITLE,
            Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
        )
        if (!state.movable) {
            Row(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 6.dp).widthIn(max = 280.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Outlined.Info, contentDescription = null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                Text(NOT_MOVABLE_NOTE, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
        for (item in arrangeItems(state, arrange.shortcuts)) {
            val checked = picked?.let { it == item.layout } ?: item.checked
            DropdownMenuItem(
                text = { Text(item.label, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
                leadingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CheckMark(checked, arrange.reduceMotion, Modifier.testTag(checkTag(item.layout)))
                        Spacer(Modifier.width(8.dp))
                        Icon(item.layout.icon, contentDescription = null, Modifier.size(18.dp))
                    }
                },
                trailingIcon = item.shortcut?.let { label ->
                    { Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1) }
                },
                onClick = {
                    if (picked != null) return@DropdownMenuItem
                    picked = item.layout
                    scope.launch {
                        // On the frame clock, in step with the check's animation.
                        if (!arrange.reduceMotion) {
                            val start = withFrameMillis { it }
                            while (withFrameMillis { it } - start < PICK_CLOSE_DELAY_MILLIS) Unit
                        }
                        onDismiss()
                        arrange.onPick(item.layout)
                    }
                },
                enabled = item.enabled,
                modifier = Modifier.semantics {
                    role = Role.RadioButton
                    selected = checked
                },
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp), color = colors.outlineVariant)
        DropdownMenuItem(
            // Wraps: the menu is at most 280 dp wide and the copy is longer.
            text = { Text(KEEP_TILED, maxLines = 2) },
            leadingIcon = { Checkbox(checked = state.keepTiled, onCheckedChange = null, enabled = state.movable) },
            onClick = { arrange.onKeepTiled(!state.keepTiled) },
            enabled = state.movable,
            modifier = Modifier.semantics {
                role = Role.Checkbox
                toggleableState = ToggleableState(state.keepTiled)
            },
        )
    }
}

/** The tag of [layout]'s check mark (tests measure its scale). */
fun checkTag(layout: ArrangeLayout) = "arrange-check-${layout.name}"

/** The check before a layout: scales in (emphasized) and out over [CHECK_IN_MILLIS]; a short fade with reduced motion. */
@Composable
private fun CheckMark(checked: Boolean, reduceMotion: Boolean, modifier: Modifier = Modifier) {
    val target = if (checked) 1f else 0f
    val scale by animateFloatAsState(
        if (reduceMotion) 1f else target,
        tween(CHECK_IN_MILLIS, easing = EmphasizedDecelerate),
        label = "check scale",
    )
    val alpha by animateFloatAsState(
        if (reduceMotion) target else 1f,
        tween(FollowMotion.REDUCED_FADE_MILLIS, easing = LinearEasing),
        label = "check fade",
    )
    Icon(
        Icons.Filled.Check,
        contentDescription = null,
        // [modifier] inside the layer: a tag on it measures the scaled check.
        Modifier.size(18.dp).graphicsLayer {
            scaleX = scale
            scaleY = scale
            this.alpha = alpha
        }.then(modifier),
        tint = MaterialTheme.colorScheme.primary,
    )
}
