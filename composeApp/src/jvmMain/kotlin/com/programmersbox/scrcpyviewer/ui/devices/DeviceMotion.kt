package com.programmersbox.scrcpyviewer.ui.devices

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.programmersbox.scrcpyviewer.ui.theme.styledCorner
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.adb.Device
import com.programmersbox.scrcpyviewer.adb.DeviceState
import com.programmersbox.scrcpyviewer.ui.common.CLOSE_MILLIS
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedAccelerate
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedDecelerate
import com.programmersbox.scrcpyviewer.ui.theme.warnColor
import kotlinx.coroutines.flow.first
import kotlin.math.ceil

// ---------------------------------------------------------------------------------------------------------------------
// Device arrives and leaves
// ---------------------------------------------------------------------------------------------------------------------

/** How long a newly connected device's row takes to slide and grow in. */
internal const val DEVICE_ARRIVE_MILLIS = 420

/** How long a disconnected device's row takes to slide out and collapse. */
internal const val DEVICE_LEAVE_MILLIS = 220

/** How long a new row's "just arrived" tint takes to fade back to the normal row color. */
internal const val DEVICE_GLOW_MILLIS = 1_400

/**
 * Devices that show up this soon after adb connects belong to the first load: the tracker may publish "connected"
 * and the device list a moment apart, and those rows should not play the arrival motion.
 */
internal const val INITIAL_LOAD_GRACE_MILLIS = 600L

/**
 * One row of the device list. [present] is false while a disconnected device plays its exit; [arrived] is true for
 * a device that connected after the first load, so its row slides in and glows.
 */
internal data class DeviceEntry(val device: Device, val present: Boolean, val arrived: Boolean) {
    val serial: String get() = device.serial
}

/**
 * The rows to show for [devices], given the rows shown before ([previous], null for the first load).
 *
 * Every current device keeps the list's order. A device that left stays at its old place with `present = false`
 * until its exit finishes (see [dropDeparted]). A device not in [previous] is an arrival when [animateArrivals];
 * on the first load nothing is.
 */
internal fun nextDeviceEntries(previous: List<DeviceEntry>?, devices: List<Device>, animateArrivals: Boolean): List<DeviceEntry> {
    if (previous == null) return devices.map { DeviceEntry(it, present = true, arrived = false) }
    val old = previous.associateBy { it.serial }
    val result = devices.mapTo(ArrayList()) { device ->
        val before = old[device.serial]
        DeviceEntry(device, present = true, arrived = before?.arrived ?: animateArrivals)
    }
    // Put each departed row back after the nearest row that preceded it before, so it leaves from where it was.
    val current = devices.mapTo(HashSet()) { it.serial }
    previous.forEachIndexed { index, entry ->
        if (entry.serial in current) return@forEachIndexed
        val anchor = (index - 1 downTo 0).map { previous[it].serial }.firstOrNull { s -> result.any { it.serial == s } }
        val at = if (anchor == null) 0 else result.indexOfFirst { it.serial == anchor } + 1
        result.add(at, entry.copy(present = false))
    }
    return result
}

/** [entries] without [serial] once its exit has finished, unless it came back meanwhile. */
internal fun dropDeparted(entries: List<DeviceEntry>, serial: String): List<DeviceEntry> =
    entries.filterNot { it.serial == serial && !it.present }

/**
 * The device list's rows for [devices], with departed devices kept until their exit ends. While adb is not
 * [live] (starting or restarting) there is no list, and the next list adb reports counts as the first load.
 */
@Composable
internal fun rememberDeviceEntries(devices: List<Device>, live: Boolean): DeviceEntriesState {
    val holder = remember { DeviceEntriesState() }
    holder.update(devices, live, System.currentTimeMillis())
    return holder
}

/**
 * Keeps the rows in plain fields, worked out from the inputs during composition (the same inputs give the same
 * rows), so composition never writes snapshot state. A finished exit bumps [version] to recompose without the row.
 */
internal class DeviceEntriesState {
    private val version = mutableIntStateOf(0)
    private var current: List<DeviceEntry> = emptyList()
    private var loaded: List<DeviceEntry>? = null
    private var liveSince = 0L
    private var lastDevices: List<Device>? = null

    val entries: List<DeviceEntry>
        get() {
            version.intValue
            return current
        }

    fun update(devices: List<Device>, live: Boolean, now: Long) {
        if (!live) {
            loaded = null
            lastDevices = null
            current = emptyList()
            return
        }
        if (devices === lastDevices) return
        if (lastDevices == null) liveSince = now
        lastDevices = devices
        val next = nextDeviceEntries(loaded, devices, animateArrivals = now - liveSince > INITIAL_LOAD_GRACE_MILLIS)
        loaded = next
        current = next
    }

    fun exited(serial: String) {
        val next = dropDeparted(current, serial)
        if (next.size == current.size) return
        loaded = next
        current = next
        version.intValue++
    }
}

/**
 * One device row that slides in from the left and grows (a device that just connected), or slides right, fades and
 * collapses (a device that disconnected). Rows from the first load appear without motion.
 */
@Composable
internal fun AnimatedDeviceItem(entry: DeviceEntry, onExited: (String) -> Unit, content: @Composable (glow: Boolean) -> Unit) {
    key(entry.serial) {
        val visible = remember { MutableTransitionState(!entry.arrived) }
        visible.targetState = entry.present
        val slide = with(LocalDensity.current) { 16.dp.roundToPx() }
        AnimatedVisibility(
            visibleState = visible,
            enter = slideInHorizontally(tween(DEVICE_ARRIVE_MILLIS, easing = EmphasizedDecelerate)) { -slide } +
                expandVertically(tween(DEVICE_ARRIVE_MILLIS, easing = EmphasizedDecelerate), expandFrom = Alignment.Top) +
                fadeIn(tween(DEVICE_ARRIVE_MILLIS, easing = EmphasizedDecelerate)),
            exit = slideOutHorizontally(tween(DEVICE_LEAVE_MILLIS, easing = EmphasizedAccelerate)) { slide } +
                fadeOut(tween(DEVICE_LEAVE_MILLIS, easing = EmphasizedAccelerate)) +
                shrinkVertically(tween(DEVICE_LEAVE_MILLIS, easing = EmphasizedAccelerate), shrinkTowards = Alignment.Top),
        ) {
            content(entry.arrived)
        }
        if (!entry.present) {
            LaunchedEffect(visible) {
                snapshotFlow { visible.isIdle && !visible.currentState }.first { it }
                onExited(entry.serial)
            }
        }
    }
}

/**
 * The "just arrived" tint behind a new row: starts at primaryContainer and fades to [base] over ~1.4s.
 * With [glow] false (rows from the first load) it draws [base] only.
 */
@Composable
internal fun Modifier.arrivalGlow(glow: Boolean, base: Color): Modifier {
    val amount = remember { Animatable(if (glow) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (amount.value > 0f) {
            // Hold the tint briefly while the row lands, then let it ebb away.
            amount.animateTo(0f, tween(DEVICE_GLOW_MILLIS - 250, delayMillis = 250, easing = FastOutSlowInEasing))
        }
    }
    val tint = MaterialTheme.colorScheme.primaryContainer
    return drawBehind { drawRect(lerp(base, tint, amount.value)) }
}

// ---------------------------------------------------------------------------------------------------------------------
// Morphing status chip
// ---------------------------------------------------------------------------------------------------------------------

internal enum class ChipTone { Connected, Pending, Problem }

/** What a device row's connection chip says: "USB", "Connecting over Wi-Fi…" or "Wi-Fi · 192.168.1.31". */
internal data class ChipStatus(val label: String, val wifi: Boolean, val tone: ChipTone)

internal fun chipStatus(device: Device, switching: Boolean): ChipStatus {
    val tone = when (device.state) {
        DeviceState.Device -> ChipTone.Connected
        DeviceState.Unauthorized -> ChipTone.Pending
        DeviceState.Offline, DeviceState.Other -> ChipTone.Problem
    }
    return when {
        switching -> ChipStatus("Connecting over Wi-Fi…", wifi = false, tone = ChipTone.Pending)
        device.isTcp -> ChipStatus(wifiHost(device.serial)?.let { "Wi-Fi · $it" } ?: "Wi-Fi", wifi = true, tone = tone)
        else -> ChipStatus("USB", wifi = false, tone = tone)
    }
}

private val Ipv4WithPort = Regex("""^(\d{1,3}(?:\.\d{1,3}){3}):\d+$""")

/** The IP address of an `ip:port` adb serial; null for mDNS serials (`adb-…._adb-tls-connect._tcp`). */
internal fun wifiHost(serial: String): String? = Ipv4WithPort.matchEntire(serial)?.groupValues?.get(1)

private const val CHIP_RESIZE_MILLIS = 300
private const val CHIP_LABEL_MILLIS = 260
private const val DOT_PULSE_MILLIS = 900

/**
 * The connection chip after a device's name. When the status changes the chip's width eases to fit, the new label
 * rises in over the old one, and while a connection is pending its dot pulses.
 */
@Composable
internal fun StatusChip(status: ChipStatus, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (status.wifi) scheme.primaryContainer else scheme.surfaceContainerHigh, tween(CHIP_RESIZE_MILLIS), label = "chip container",
    )
    val content by animateColorAsState(
        if (status.wifi) scheme.onPrimaryContainer else scheme.onSurfaceVariant, tween(CHIP_RESIZE_MILLIS), label = "chip content",
    )
    val dotColor by animateColorAsState(
        when (status.tone) {
            ChipTone.Connected -> scheme.primary
            ChipTone.Pending -> warnColor()
            ChipTone.Problem -> scheme.error
        },
        tween(CHIP_RESIZE_MILLIS),
        label = "chip dot",
    )
    Row(
        modifier
            .clip(styledCorner(6.dp))
            .background(container)
            .animateContentSize(tween(CHIP_RESIZE_MILLIS, easing = EmphasizedDecelerate))
            .padding(horizontal = 6.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        StatusDot(dotColor, pulsing = status.tone == ChipTone.Pending && status.label.endsWith("…"))
        AnimatedContent(
            targetState = status.label,
            transitionSpec = {
                (fadeIn(tween(CHIP_LABEL_MILLIS, easing = EmphasizedDecelerate)) +
                    slideInVertically(tween(CHIP_LABEL_MILLIS, easing = EmphasizedDecelerate)) { it / 4 }) togetherWith
                    fadeOut(tween(CHIP_LABEL_MILLIS / 2, easing = EmphasizedAccelerate)) using null
            },
            contentAlignment = Alignment.CenterStart,
            label = "chip label",
        ) { label ->
            Text(label, style = MaterialTheme.typography.labelSmall, color = content, maxLines = 1)
        }
    }
}

/** A 6dp status dot; [pulsing] grows it to 1.6x and dims it to half, every 900ms. */
@Composable
private fun StatusDot(color: Color, pulsing: Boolean) {
    val pulse = if (pulsing) {
        val transition = rememberInfiniteTransition(label = "status dot")
        transition.animateFloat(
            initialValue = 0f, targetValue = 0f,
            animationSpec = infiniteRepeatable(
                keyframes {
                    durationMillis = DOT_PULSE_MILLIS
                    0f at 0 using FastOutSlowInEasing
                    1f at DOT_PULSE_MILLIS / 2 using FastOutSlowInEasing
                    0f at DOT_PULSE_MILLIS
                },
                RepeatMode.Restart,
            ),
            label = "status dot pulse",
        )
    } else {
        null
    }
    Box(
        Modifier
            .size(6.dp)
            .graphicsLayer {
                val p = pulse?.value ?: 0f
                scaleX = 1f + 0.6f * p
                scaleY = 1f + 0.6f * p
                alpha = 1f - 0.5f * p
            }
            .clip(CircleShape)
            .background(color),
    )
}

// ---------------------------------------------------------------------------------------------------------------------
// Restart adb: spin, settle, check
// ---------------------------------------------------------------------------------------------------------------------

/** One full turn of the refresh icon while adb restarts. */
internal const val SPIN_MILLIS_PER_TURN = 700

/** Degrees per millisecond while spinning. */
private const val SPIN_SPEED = 360f / SPIN_MILLIS_PER_TURN

/** How far past the resting turn the icon swings before it springs back. */
private const val SETTLE_OVERSHOOT = 14f

/** How long the success check stays on the restart button. */
internal const val RESTART_CHECK_MILLIS = 1_400L

/**
 * The whole turn the spinning icon comes to rest on from [angle]: the next multiple of 360, at least 100 degrees
 * ahead so the icon has room to slow down instead of stopping dead.
 */
internal fun settleTarget(angle: Float): Float {
    val next = ceil(angle / 360f) * 360f
    return if (next - angle < 100f) next + 360f else next
}

/**
 * Spins the icon while [restarting]; when it ends, slows it to rest on a whole turn with a slight overshoot,
 * then calls [onSettled]. Read [rotation] in a graphics layer.
 */
@Composable
internal fun rememberSpinSettle(restarting: Boolean, onSettled: () -> Unit): Animatable<Float, *> {
    val rotation = remember { Animatable(0f) }
    var spun by remember { mutableStateOf(false) }
    LaunchedEffect(restarting) {
        if (restarting) {
            spun = true
            while (true) {
                rotation.animateTo(rotation.value + 360f, tween(SPIN_MILLIS_PER_TURN, easing = LinearEasing))
            }
        } else if (spun) {
            spun = false
            val target = settleTarget(rotation.value)
            val distance = target + SETTLE_OVERSHOOT - rotation.value
            // An ease-out whose starting slope is 2 begins at the spin's speed when it lasts 2 * distance / speed.
            val millis = (2f * distance / SPIN_SPEED).toInt()
            rotation.animateTo(target + SETTLE_OVERSHOOT, tween(millis, easing = CubicBezierEasing(0.25f, 0.5f, 0.5f, 1f)))
            rotation.animateTo(target, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow))
            rotation.snapTo(0f)
            onSettled()
        }
    }
    return rotation
}

/** How long a settled restart button waits for the restart's outcome before giving up on the check. */
internal const val RESTART_OUTCOME_WAIT_MILLIS = 1_500L


/** The small check that pops onto the restart button after a restart worked. */
@Composable
internal fun RestartCheckBadge(visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = scaleIn(spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium)) + fadeIn(tween(120)),
        exit = scaleOut(tween(CLOSE_MILLIS, easing = EmphasizedAccelerate), targetScale = 0.4f) +
            fadeOut(tween(CLOSE_MILLIS, easing = EmphasizedAccelerate)),
    ) {
        Box(
            Modifier.size(16.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "adb restarted",
                Modifier.size(11.dp),
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}
