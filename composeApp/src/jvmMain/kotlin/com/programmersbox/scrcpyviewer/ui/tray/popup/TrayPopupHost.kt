package com.programmersbox.scrcpyviewer.ui.tray.popup

import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.state.AfterScreenshot
import com.programmersbox.scrcpyviewer.ui.devices.deviceTitle
import com.programmersbox.scrcpyviewer.ui.mirror.MirrorController
import com.programmersbox.scrcpyviewer.ui.mirror.arrange.MirrorWindows
import com.programmersbox.scrcpyviewer.ui.mirror.arrange.WindowArranger
import com.programmersbox.scrcpyviewer.ui.mirror.follow.FollowState
import com.programmersbox.scrcpyviewer.ui.mirror.follow.InputFollowing
import com.programmersbox.scrcpyviewer.ui.theme.AppTheme
import com.programmersbox.scrcpyviewer.ui.tray.TrayAction
import com.programmersbox.scrcpyviewer.ui.tray.TrayCommands
import dev.nucleusframework.application.NucleusApplicationScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long the screenshot line under the stage actions stays. */
private const val NOTE_MS = 4_000L

/** What the popup says after a screenshot; null when the markup editor opens instead (a window comes forward). */
internal fun screenshotNote(afterShot: AfterScreenshot): String? = when (afterShot) {
    AfterScreenshot.Thumbnail -> "Saved to Screenshots"
    AfterScreenshot.Copy -> "Copied"
    AfterScreenshot.OpenEditor -> null
}

/** The note for a new screenshot after [previous]: [text] with the next id, so the stage flashes even for the same text. */
internal fun nextScreenshotNote(previous: ScreenshotNote?, text: String): ScreenshotNote =
    ScreenshotNote(text, (previous?.id ?: 0L) + 1)

/** Actions that bring a window forward close the popup; toggles keep it open. */
internal fun closesPopup(action: TrayAction): Boolean = when (action) {
    TrayAction.Focus, TrayAction.Mirror, TrayAction.OpenMain, TrayAction.Settings, TrayAction.Quit -> true
    TrayAction.ShowPopup, is TrayAction.Arrange, is TrayAction.KeepTiled -> false
}

/**
 * The follow link after "Stop following" on [stage]: its leader keeps its other followers. Null when [stage] follows
 * no one.
 */
internal fun stopFollowing(stage: String, follow: FollowState): Pair<String, Set<String>>? {
    val leader = follow.leader ?: return null
    if (stage !in follow.followers) return null
    return leader to withoutFollower(stage, follow).followers
}

/**
 * The menu bar popup: its window ([TrayPopupWindow]) with the popup content, fed from the app's state, and every
 * [PopupEvent] routed to the code the mirror windows and the tray menu already use.
 */
@Composable
fun NucleusApplicationScope.TrayPopup(
    controller: TrayPopupController,
    theme: AppTheme,
    afterShot: AfterScreenshot,
    mirrorController: MirrorController,
    mirrorWindows: MirrorWindows,
    arranger: WindowArranger,
    inputFollowing: InputFollowing,
    trayCommands: TrayCommands,
) {
    val appScope = rememberCoroutineScope()
    val thumbnails = remember {
        ScreenThumbnails(appScope, frames = { mirrorController.open.value.mapValues { (_, s) -> { s.frames.value } } })
    }
    DisposableEffect(thumbnails) { onDispose { thumbnails.clear() } }
    val currentAfterShot = rememberUpdatedState(afterShot)
    // The popup window has its own composition, made fresh on each open. The popup's own state (the picked device,
    // the send panel) lives there and app state is collected there, like a mirror window does.
    TrayPopupWindow(controller, theme) { maxHeight ->
        TrayPopupBody(
            controller, { currentAfterShot.value }, mirrorController, mirrorWindows, arranger, inputFollowing,
            trayCommands, thumbnails, maxHeight,
        )
    }
}

@Composable
private fun TrayPopupBody(
    controller: TrayPopupController,
    afterShot: () -> AfterScreenshot,
    mirrorController: MirrorController,
    mirrorWindows: MirrorWindows,
    arranger: WindowArranger,
    inputFollowing: InputFollowing,
    trayCommands: TrayCommands,
    thumbnails: ScreenThumbnails,
    maxHeight: Int,
) {
    val scope = rememberCoroutineScope()
    val visible by controller.visible.collectAsState()
    val adbDevices by mirrorController.devices.collectAsState()
    val mirrors by mirrorController.open.collectAsState()
    val follow by inputFollowing.state.collectAsState()
    val arrange by arranger.state.collectAsState()


    // Map keys keep the order mirrors opened in, so mirrors adb no longer lists keep their place.
    val devices = remember(adbDevices, mirrors) { popupDevices(adbDevices, LinkedHashSet(mirrors.keys), ::deviceTitle) }

    // Counts opens (not closes), so state reset per open stays as it was while the popup animates shut. The window is
    // new each open, so this is 1 unless the popup is reopened while it is still animating shut.
    val opens = remember { intArrayOf(0) }
    val openId = remember(visible) { if (visible) ++opens[0] else opens[0] }
    // Each open starts at the mirror window focused last, with the send panel shut.
    val openStage = remember(openId) { stageSerial(current = null, lastFocused = mirrorWindows.lastFocused.value, devices) }
    var pick by remember(openId) { mutableStateOf<String?>(null) }
    // One screenshot per mirroring device each open, no refresh. They stay through the close animation, so the stage
    // doesn't fade to its placeholder while it shrinks away, and go with the window.
    LaunchedEffect(openId) { if (visible) thumbnails.capture() }
    DisposableEffect(thumbnails) { onDispose { thumbnails.clear() } }
    val images by thumbnails.images.collectAsState()
    var sendOpen by remember(openId) { mutableStateOf(false) }
    // The user's pick stays while it is listed; devices coming and going may move the stage.
    val stage = stageSerial(current = pick ?: openStage, lastFocused = mirrorWindows.lastFocused.value, devices)

    var note by remember { mutableStateOf<ScreenshotNote?>(null) }
    var noteJob by remember { mutableStateOf<Job?>(null) }
    // A note is about the stage's device: it goes with each open and when the stage moves to another device.
    LaunchedEffect(openId, stage) {
        noteJob?.cancel()
        note = null
    }

    val panel = sendPanel(stage, devices, follow)
    // The panel closes itself when it goes (fewer than two mirrors, or the stage stops mirroring).
    LaunchedEffect(panel is SendPanel.Hidden) { if (panel is SendPanel.Hidden) sendOpen = false }

    val state = TrayPopupUiState(
        devices = devices,
        stage = stage,
        thumbnails = images,
        sendPanel = panel,
        arrange = arrange,
        screenshotNote = note,
        follow = follow,
    )

    fun onEvent(event: PopupEvent) {
        when (event) {
            is PopupEvent.Pick -> pick = event.serial
            is PopupEvent.Act -> {
                trayCommands.dispatch(event.action, event.serial)
                if (closesPopup(event.action)) controller.dismissed()
            }
            // The mirror window's own close path: unsaved marks prompt, GIF exports cancelled, close animation.
            is PopupEvent.Stop -> mirrorController.requestClose(event.serial)
            is PopupEvent.Screenshot -> {
                mirrorController.requestScreenshot(event.serial)
                val text = screenshotNote(afterShot())
                if (text == null) {
                    controller.dismissed()
                } else {
                    noteJob?.cancel()
                    note = nextScreenshotNote(note, text)
                    val shown = note
                    noteJob = scope.launch {
                        delay(NOTE_MS)
                        if (note == shown) note = null
                    }
                }
            }
            PopupEvent.ToggleSend -> sendOpen = !sendOpen
            is PopupEvent.ToggleFollower -> stage?.let { leader ->
                inputFollowing.setFollowers(leader, toggledFollowers(leader, event.serial, follow))
            }
            PopupEvent.StopFollowing -> stage?.let { stopFollowing(it, follow) }?.let { (leader, followers) ->
                inputFollowing.setFollowers(leader, followers)
            }
        }
    }

    TrayPopupContent(state, sendOpen, ::onEvent, Modifier.heightIn(max = maxHeight.dp))
}
