package com.programmersbox.scrcpyviewer.ui.tray.popup

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.programmersbox.scrcpyviewer.ui.common.rememberSelectionHighlight
import com.programmersbox.scrcpyviewer.ui.common.selectionHighlight
import com.programmersbox.scrcpyviewer.ui.common.selectionHighlightTarget
import com.programmersbox.scrcpyviewer.ui.theme.ThemeStyle
import com.programmersbox.scrcpyviewer.ui.theme.currentStyle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.programmersbox.scrcpyviewer.ui.common.LocalReduceMotion
import com.programmersbox.scrcpyviewer.ui.common.layoutSpring
import com.programmersbox.scrcpyviewer.ui.mirror.arrange.ArrangeState
import com.programmersbox.scrcpyviewer.ui.mirror.follow.FollowRole
import com.programmersbox.scrcpyviewer.ui.mirror.follow.FollowState
import com.programmersbox.scrcpyviewer.ui.theme.TextButton
import com.programmersbox.scrcpyviewer.ui.theme.listRow
import com.programmersbox.scrcpyviewer.ui.theme.stylePane
import com.programmersbox.scrcpyviewer.ui.theme.warnColor
import com.programmersbox.scrcpyviewer.ui.tray.TrayAction

/**
 * Everything the popup shows. [stage] is the serial on stage (null with no devices); [thumbnails] are the live screens
 * of mirrors; [follow] gives the stage's role tag. [arrange] is for the Arrange section (null: hidden).
 */
data class TrayPopupUiState(
    val devices: List<PopupDevice>,
    val stage: String?,
    val thumbnails: Map<String, ImageBitmap> = emptyMap(),
    val sendPanel: SendPanel = SendPanel.Hidden,
    val arrange: ArrangeState? = null,
    val screenshotNote: String? = null,
    val follow: FollowState = FollowState(),
)

sealed interface PopupEvent {
    data class Pick(val serial: String) : PopupEvent
    data class Act(val action: TrayAction, val serial: String?) : PopupEvent
    data class Screenshot(val serial: String) : PopupEvent
    data class Stop(val serial: String) : PopupEvent
    data object ToggleSend : PopupEvent
    data class ToggleFollower(val serial: String) : PopupEvent
    data object StopFollowing : PopupEvent
}

private val PORTRAIT_WIDTH = 112.dp
private val LANDSCAPE_WIDTH = 190.dp
private val STRIP_ITEM_WIDTH = 64.dp
private val STAGE_SHAPE = RoundedCornerShape(20.dp)

/**
 * The menu bar popup's content: header, stage, device strip and footer. Stateless; the host owns the window. The device
 * part scrolls when the caller's `heightIn(max)` is shorter than it; header and footer stay put.
 */
@Composable
fun TrayPopupContent(
    state: TrayPopupUiState,
    sendOpen: Boolean,
    onEvent: (PopupEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduce = LocalReduceMotion.current
    // A new screenshot note flashes the stage screen (a short white fade, also under reduce motion).
    val flash = remember { Animatable(0f) }
    val firstNote = remember { booleanArrayOf(true) }
    LaunchedEffect(state.screenshotNote) {
        val skip = firstNote[0]
        firstNote[0] = false
        if (!skip && state.screenshotNote != null) {
            flash.snapTo(0.7f)
            flash.animateTo(0f, tween(350))
        }
    }
    Column(modifier.animateContentSize(if (reduce) tween(150) else layoutSpring())) {
        Header(state.devices.size)
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            Stage(state, sendOpen, { flash.value }, onEvent)
            SendInputPanel(state, sendOpen, onEvent)
            AnimatedVisibility(
                visible = state.devices.isNotEmpty(),
                enter = if (reduce) fadeIn(tween(150)) else expandVertically(layoutSpring()) + fadeIn(tween(150)),
                exit = if (reduce) fadeOut(tween(100)) else shrinkVertically(layoutSpring()) + fadeOut(tween(100)),
            ) { Strip(state, onEvent) }
            ArrangeSection(state, onEvent)
        }
        Footer(onEvent)
    }
}

@Composable
private fun Header(count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Devices", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Text("$count connected", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Stage(state: TrayPopupUiState, sendOpen: Boolean, flash: () -> Float, onEvent: (PopupEvent) -> Unit) {
    val reduce = LocalReduceMotion.current
    // Where each serial last sat in the strip, kept for a device that has left, so the slide still has a direction.
    val order = remember { mutableMapOf<String, Int>() }
    state.devices.forEachIndexed { i, d -> order[d.serial] = i }
    val target = state.devices.firstOrNull { it.serial == state.stage }
    // The exit keeps its own snapshot of the device; the outer animateContentSize animates the height, so the size here snaps.
    AnimatedContent(
        targetState = target,
        modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth(),
        contentKey = { it?.serial },
        transitionSpec = {
            val from = initialState?.serial?.let { order[it] } ?: 0
            val to = targetState?.serial?.let { order[it] } ?: 0
            val dir = if (to >= from) 1 else -1
            val size = SizeTransform(clip = true) { _, _ -> snap() }
            if (reduce) {
                (fadeIn(tween(150)) togetherWith fadeOut(tween(100))).using(size)
            } else {
                (slideInHorizontally(layoutSpring()) { it / 4 * dir } + fadeIn(tween(150)) togetherWith
                    slideOutHorizontally(layoutSpring()) { -it / 4 * dir } + fadeOut(tween(100))).using(size)
            }
        },
        label = "stage",
    ) { device ->
        Box(
            Modifier.fillMaxWidth().stylePane(STAGE_SHAPE).clip(STAGE_SHAPE)
                .background(MaterialTheme.colorScheme.surfaceContainer).padding(12.dp),
        ) {
            // A status change under the same serial (a mirror stopping) crossfades; the outer animateContentSize does the height.
            // Keyed on the status branch only, so title or rawState changes do not crossfade to a copy; each branch keeps its last device.
            val branch = stageBranch(device)
            val lastByBranch = remember { mutableMapOf<StageBranch, PopupDevice?>() }
            if (device != null) lastByBranch[branch] = device
            Crossfade(branch, label = "stage status") { b ->
                val shown = if (b == branch) device else lastByBranch[b]
                when (b) {
                    StageBranch.None -> Text(
                        "No device connected", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp),
                    )
                    StageBranch.Mirroring, StageBranch.Ready -> if (shown != null) DeviceStage(shown, state, sendOpen, flash, onEvent)
                    StageBranch.Attention -> if (shown != null) AttentionStage(shown)
                }
            }
        }
    }
}

private enum class StageBranch { None, Mirroring, Ready, Attention }

private fun stageBranch(device: PopupDevice?) = when (device?.status) {
    null -> StageBranch.None
    PopupStatus.Mirroring -> StageBranch.Mirroring
    PopupStatus.Ready -> StageBranch.Ready
    else -> StageBranch.Attention
}

@Composable
private fun DeviceStage(device: PopupDevice, state: TrayPopupUiState, sendOpen: Boolean, flash: () -> Float, onEvent: (PopupEvent) -> Unit) {
    val thumb = state.thumbnails[device.serial]
    val landscape = thumb != null && thumb.width > thumb.height
    // A landscape screen leaves the details too little room beside it, so they go under it. The switch fades.
    AnimatedContent(
        targetState = landscape,
        transitionSpec = { (fadeIn(tween(150)) togetherWith fadeOut(tween(100))).using(SizeTransform(clip = false) { _, _ -> snap() }) },
        label = "stage shape",
    ) { wide ->
        if (wide) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                StageScreen(thumb, true, flash)
                Details(device, state, sendOpen, onEvent)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StageScreen(thumb, false, flash)
                Box(Modifier.weight(1f)) { Details(device, state, sendOpen, onEvent) }
            }
        }
    }
}

@Composable
private fun StageScreen(thumb: ImageBitmap?, landscape: Boolean, flash: () -> Float) {
    val shape = RoundedCornerShape(14.dp)
    Crossfade(
        thumb, label = "stage screen",
        modifier = Modifier.clip(shape).drawWithContent {
            drawContent()
            drawRect(Color.White, alpha = flash().coerceIn(0f, 1f))
        },
    ) { bitmap ->
        if (bitmap != null) {
            val ratio = (bitmap.width.toFloat() / bitmap.height).coerceIn(0.4f, 2.5f)
            Image(
                bitmap, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.width(if (landscape) LANDSCAPE_WIDTH else PORTRAIT_WIDTH).aspectRatio(ratio).clip(shape),
            )
        } else {
            Box(
                Modifier.width(PORTRAIT_WIDTH).height(200.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PhoneAndroid, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Details(device: PopupDevice, state: TrayPopupUiState, sendOpen: Boolean, onEvent: (PopupEvent) -> Unit) {
    val serial = device.serial
    val mirroring = device.status == PopupStatus.Mirroring
    Column {
        Text(device.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            serial, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Tags(device.status, if (mirroring) roleText(state, serial) else null)
        Spacer(Modifier.height(6.dp))
        if (mirroring) {
            StageAction("Bring to front", Icons.AutoMirrored.Filled.OpenInNew) { onEvent(PopupEvent.Act(TrayAction.Focus, serial)) }
            StageAction("Screenshot", Icons.Filled.PhotoCamera) { onEvent(PopupEvent.Screenshot(serial)) }
            val panel = state.sendPanel
            if (panel !is SendPanel.Hidden) {
                SendToggle(sendOpen, (panel as? SendPanel.Choose)?.selected?.size ?: 0) { onEvent(PopupEvent.ToggleSend) }
            }
            StageAction("Stop mirroring", Icons.Filled.Close) { onEvent(PopupEvent.Stop(serial)) }
        } else {
            StageAction("Mirror", Icons.Filled.PlayArrow) { onEvent(PopupEvent.Act(TrayAction.Mirror, serial)) }
        }
        Crossfade(state.screenshotNote, label = "screenshot note") { note ->
            if (note != null) {
                Text(
                    note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, top = 2.dp),
                )
            }
        }
    }
}

private fun roleText(state: TrayPopupUiState, serial: String): String? = when (val role = state.follow.roleOf(serial)) {
    FollowRole.None -> null
    is FollowRole.Leader -> "Leads ${role.followers.size}"
    is FollowRole.Follower -> "Follows ${state.devices.firstOrNull { it.serial == role.leader }?.title ?: role.leader}"
}

private val ActionPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)

@Composable
private fun StageAction(label: String, icon: ImageVector, onClick: () -> Unit) {
    TextButton(onClick, contentPadding = ActionPadding) {
        Icon(icon, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SendToggle(open: Boolean, count: Int, onClick: () -> Unit) {
    val turn by animateFloatAsState(if (open) 180f else 0f, if (LocalReduceMotion.current) tween(150) else layoutSpring(), label = "send arrow")
    TextButton(onClick, contentPadding = ActionPadding) {
        Text(if (count > 0) "Send input to… ($count)" else "Send input to…", maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(Icons.Filled.ExpandMore, null, Modifier.padding(start = 4.dp).size(18.dp).rotate(turn))
    }
}

/** The status pill and the follow role pill; they crossfade when either changes. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Tags(status: PopupStatus, role: String?) {
    val scheme = MaterialTheme.colorScheme
    Crossfade(status to role, label = "tags") { (s, r) ->
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (s == PopupStatus.Mirroring) Pill("Mirroring", scheme.primaryContainer, scheme.onPrimaryContainer, dot = true)
            else Pill("Ready", scheme.surfaceContainerHighest, scheme.onSurfaceVariant)
            if (r != null) Pill(r, scheme.surfaceContainerHighest, scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Pill(text: String, container: Color, content: Color, dot: Boolean = false) {
    Row(
        Modifier.clip(CircleShape).background(container).padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot) Box(Modifier.padding(end = 5.dp).size(6.dp).clip(CircleShape).background(content))
        Text(text, style = MaterialTheme.typography.labelSmall, color = content, maxLines = 1)
    }
}

@Composable
private fun AttentionStage(device: PopupDevice) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Warning, null, Modifier.size(32.dp), tint = warnColor())
        Column(Modifier.weight(1f)) {
            Text(device.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                device.serial, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            val scheme = MaterialTheme.colorScheme
            Pill(statusLabel(device), scheme.errorContainer, scheme.onErrorContainer)
            Spacer(Modifier.height(6.dp))
            Text(attentionText(device), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun statusLabel(device: PopupDevice): String = when (device.status) {
    PopupStatus.Unauthorized -> "Unauthorized"
    PopupStatus.Offline -> "Offline"
    else -> device.rawState.ifBlank { "Unknown" }
}

private fun attentionText(device: PopupDevice): String = when (device.status) {
    PopupStatus.Unauthorized -> "Unlock the phone and tap Allow on the USB debugging prompt."
    PopupStatus.Offline -> "Reconnect the cable or run adb reconnect"
    else -> device.rawState.ifBlank { "Not ready" }
}

@Composable
private fun Strip(state: TrayPopupUiState, onEvent: (PopupEvent) -> Unit) {
    val reduce = LocalReduceMotion.current
    val shape = RoundedCornerShape(12.dp)
    val material = currentStyle == ThemeStyle.Material
    val highlight = rememberSelectionHighlight()
    // Devices already listed when the strip appears do not grow in; later arrivals do.
    val initial = remember { state.devices.mapTo(HashSet()) { it.serial } }
    LazyRow(
        Modifier.padding(top = 10.dp).then(
            if (material) Modifier.selectionHighlight(highlight, MaterialTheme.colorScheme.secondaryContainer, shape) else Modifier,
        ),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(state.devices, key = { it.serial }) { device ->
            val selected = device.serial == state.stage
            var arrived by remember { mutableStateOf(reduce || device.serial in initial) }
            LaunchedEffect(Unit) { arrived = true }
            val grow by animateFloatAsState(if (arrived) 1f else 0.6f, if (reduce) tween(150) else layoutSpring(), label = "strip grow")
            // The highlight target goes first: it reads the item's resting place, not where the entrance has it.
            val motion = Modifier.selectionHighlightTarget(highlight, selected && material).animateItem(
                fadeInSpec = tween(150), placementSpec = if (reduce) null else layoutSpring(), fadeOutSpec = tween(100),
            ).graphicsLayer { scaleX = grow.coerceAtLeast(0f); scaleY = grow.coerceAtLeast(0f) }
            Box(motion.width(STRIP_ITEM_WIDTH)) {
                if (!material) {
                    // The other styles keep their own selected look; it fades in and out instead of snapping.
                    val fade by animateFloatAsState(if (selected) 1f else 0f, tween(150), label = "strip selected")
                    Box(Modifier.matchParentSize().graphicsLayer { alpha = fade }.listRow(true, shape))
                }
                Column(
                    Modifier.fillMaxWidth().clip(shape)
                        .clickable(role = Role.Button) { onEvent(PopupEvent.Pick(device.serial)) }
                        .semantics { this.selected = selected }
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    StripScreen(device, state.thumbnails[device.serial])
                    Text(
                        device.title, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StripScreen(device: PopupDevice, thumb: ImageBitmap?) {
    Crossfade(thumb, label = "strip screen") { bitmap ->
        if (bitmap != null) {
            val ratio = (bitmap.width.toFloat() / bitmap.height).coerceIn(0.4f, 2.5f)
            Image(
                bitmap, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.height(44.dp).aspectRatio(ratio).clip(RoundedCornerShape(6.dp)),
            )
        } else {
            val warn = device.status == PopupStatus.Unauthorized || device.status == PopupStatus.Offline
            Box(Modifier.size(width = 28.dp, height = 44.dp), contentAlignment = Alignment.Center) {
                Icon(
                    if (warn) Icons.Filled.Warning else Icons.Filled.PhoneAndroid, null, Modifier.size(24.dp),
                    tint = if (warn) warnColor() else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Footer(onEvent: (PopupEvent) -> Unit) {
    Column(Modifier.padding(top = 8.dp)) {
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton({ onEvent(PopupEvent.Act(TrayAction.OpenMain, null)) }) { Text("Open Scrcpy Vwr", maxLines = 1) }
            TextButton({ onEvent(PopupEvent.Act(TrayAction.Settings, null)) }) { Text("Settings…", maxLines = 1) }
            Spacer(Modifier.weight(1f))
            IconButton({ onEvent(PopupEvent.Act(TrayAction.Quit, null)) }) {
                Icon(Icons.Filled.PowerSettingsNew, contentDescription = "Quit")
            }
        }
    }
}
