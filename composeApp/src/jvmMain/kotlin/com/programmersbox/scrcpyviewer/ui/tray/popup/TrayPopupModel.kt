package com.programmersbox.scrcpyviewer.ui.tray.popup

import com.programmersbox.scrcpyviewer.adb.Device
import com.programmersbox.scrcpyviewer.adb.DeviceState
import com.programmersbox.scrcpyviewer.ui.mirror.follow.FollowState

/** What a device row in the menu bar popup shows. */
enum class PopupStatus { Mirroring, Ready, Unauthorized, Offline, Other }

data class PopupDevice(val serial: String, val title: String, val status: PopupStatus, val rawState: String)

/**
 * The popup's devices: adb's list in order, then open mirrors adb no longer lists (titled by serial). An open mirror
 * is [PopupStatus.Mirroring] whatever adb says about its device, like the native tray menu.
 */
fun popupDevices(devices: List<Device>, mirroring: Set<String>, title: (Device) -> String): List<PopupDevice> {
    val listed = devices.mapTo(HashSet()) { it.serial }
    return devices.map { device ->
        val status = when {
            device.serial in mirroring -> PopupStatus.Mirroring
            device.state == DeviceState.Device -> PopupStatus.Ready
            device.state == DeviceState.Unauthorized -> PopupStatus.Unauthorized
            device.state == DeviceState.Offline -> PopupStatus.Offline
            else -> PopupStatus.Other
        }
        PopupDevice(device.serial, title(device), status, device.rawState)
    } + mirroring.filter { it !in listed }.map { PopupDevice(it, it, PopupStatus.Mirroring, "") }
}

/**
 * The device the popup's stage shows: [current] while it is listed, else [lastFocused] if it is a listed mirror, else
 * the first mirroring device, else the first device. Null with no devices.
 */
fun stageSerial(current: String?, lastFocused: String?, devices: List<PopupDevice>): String? {
    if (current != null && devices.any { it.serial == current }) return current
    val mirrors = devices.filter { it.status == PopupStatus.Mirroring }
    return mirrors.firstOrNull { it.serial == lastFocused }?.serial
        ?: mirrors.firstOrNull()?.serial
        ?: devices.firstOrNull()?.serial
}

/** The popup's "send input" section. */
sealed interface SendPanel {
    data object Hidden : SendPanel

    /** The stage can lead: [targets] are the other mirrors, [selected] its followers; [otherLeader] leads someone else. */
    data class Choose(val targets: List<PopupDevice>, val selected: Set<String>, val otherLeader: String?) : SendPanel

    /** The stage already follows [leader]. */
    data class Following(val leader: String) : SendPanel
}

fun sendPanel(stage: String?, devices: List<PopupDevice>, follow: FollowState): SendPanel {
    val mirrors = devices.filter { it.status == PopupStatus.Mirroring }
    if (stage == null || mirrors.size < 2 || mirrors.none { it.serial == stage }) return SendPanel.Hidden
    if (follow.leader != null && follow.leader != stage && stage in follow.followers) {
        return SendPanel.Following(follow.leader)
    }
    val leads = follow.leader == stage
    return SendPanel.Choose(
        targets = mirrors.filter { it.serial != stage },
        selected = if (leads) follow.followers else emptySet(),
        otherLeader = follow.leader.takeIf { !leads },
    )
}

/** The followers to give `InputFollowing.setFollowers(stage, …)` after [target] is toggled. Another leader's link is dropped. */
fun toggledFollowers(stage: String, target: String, follow: FollowState): Set<String> {
    val current = if (follow.leader == stage) follow.followers else emptySet()
    return if (target in current) current - target else current + target
}

/** [follow] without [serial] as a follower; the link ends when none are left. */
fun withoutFollower(serial: String, follow: FollowState): FollowState {
    val rest = follow.followers - serial
    return if (rest.isEmpty()) FollowState() else follow.copy(followers = rest)
}
