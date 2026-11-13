package com.programmersbox.scrcpyviewer.ui.devices.info

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PhonelinkErase
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.ui.common.CopyButton
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedAccelerate
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedDecelerate
import com.programmersbox.scrcpyviewer.ui.common.staggeredEntrance
import com.programmersbox.scrcpyviewer.ui.mirror.systemReducesMotion
import com.programmersbox.scrcpyviewer.ui.theme.TextButton
import com.programmersbox.scrcpyviewer.ui.theme.warnColor
import kotlinx.coroutines.delay

/** Not-ready message in, cards out (and back): fade with the cards shrinking to [NOT_READY_SCALE]. */
internal const val NOT_READY_MILLIS = 200
internal const val NOT_READY_SCALE = 0.96f

/** The failed state's message and Retry leave quickly so the placeholders can take over. */
private const val FAILED_OUT_MILLIS = 150

/** What the cards area shows; the pane animates between these, never between values within one. */
private sealed interface Body {
    data class NotReady(val message: String) : Body
    data object Loading : Body
    data class Failed(val message: String) : Body
    data object Ready : Body
}

private fun bodyOf(state: DeviceInfoUiState): Body = when {
    state.notReady != null -> Body.NotReady(state.notReady)
    state.static is Load.Failed -> Body.Failed(state.static.message)
    state.static is Load.Ready -> Body.Ready
    else -> Body.Loading
}

/**
 * The Devices screen's Info tab for one device: its name, then six cards (battery, storage, memory, display, network,
 * software) in an adaptive grid, then "Updated N s ago" and "Copy as text". Placeholders pulse until the first read,
 * a failed read offers Retry, and an unauthorized or offline device shows the device row's message instead.
 *
 * The screen swaps the whole pane when the selected device changes; this pane animates only its own states.
 * [reduceMotion] (the OS asks for less motion) makes every change an instant swap or a short fade.
 */
@Composable
fun DeviceInfoPane(
    state: DeviceInfoUiState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = remember { systemReducesMotion() },
) {
    CompositionLocalProvider(LocalInfoReduceMotion provides reduceMotion) {
        val body = bodyOf(state)
        Column(modifier.fillMaxSize()) {
            Text(
                state.title ?: state.serial.orEmpty(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            AnimatedContent(
                targetState = body,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = { bodyTransition(reduceMotion) },
                contentAlignment = Alignment.TopCenter,
                label = "infoBody",
            ) { shown ->
                when (shown) {
                    is Body.NotReady -> NotReadyMessage(shown.message)
                    Body.Loading -> Placeholders()
                    is Body.Failed -> FailedMessage(shown.message, onRetry)
                    Body.Ready -> Cards(state, reduceMotion)
                }
            }
            Footer(state, showing = body == Body.Ready)
        }
    }
}

/** The spec's Motion rows for the cards area. */
private fun AnimatedContentTransitionScope<Body>.bodyTransition(reduce: Boolean): ContentTransform {
    if (reduce) return crossFade(reduce = true)
    val from = initialState
    val to = targetState
    return when {
        // Cards fade and shrink to 96% while the message fades in; the reverse when the device is back.
        from is Body.NotReady || to is Body.NotReady -> {
            val enter: EnterTransition = fadeIn(tween(NOT_READY_MILLIS, easing = EmphasizedDecelerate)) +
                if (to is Body.NotReady) EnterTransition.None
                else scaleIn(tween(NOT_READY_MILLIS, easing = EmphasizedDecelerate), initialScale = NOT_READY_SCALE)
            val exit: ExitTransition = fadeOut(tween(NOT_READY_MILLIS, easing = EmphasizedAccelerate)) +
                if (from is Body.NotReady) ExitTransition.None
                else scaleOut(tween(NOT_READY_MILLIS, easing = EmphasizedAccelerate), targetScale = NOT_READY_SCALE)
            enter togetherWith exit
        }
        // Error and Retry fade out, then the placeholders fade in.
        from is Body.Failed ->
            fadeIn(tween(CROSSFADE_MILLIS, delayMillis = FAILED_OUT_MILLIS, easing = EmphasizedDecelerate)) togetherWith
                fadeOut(tween(FAILED_OUT_MILLIS, easing = EmphasizedAccelerate))
        // Placeholders cross-fade into the cards (each card also plays its staggered entrance).
        else -> crossFade(reduce = false)
    }
}

@Composable
private fun Cards(state: DeviceInfoUiState, reduce: Boolean) {
    // Kept while the cards leave, so they fade out with what they showed.
    val static = rememberLastReady(state.static) ?: return
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        InfoGrid(CARD_COUNT) { index, sizing ->
            val m = if (reduce) sizing else sizing.staggeredEntrance(index, trigger = state.serial)
            when (index) {
                0 -> BatteryCard(state.live, m)
                1 -> StorageCard(static.storage, m)
                2 -> MemoryCard(state.live, m)
                3 -> DisplayCard(static.display, m)
                4 -> NetworkCard(static.network, m)
                else -> SoftwareCard(static.software, m)
            }
        }
    }
}

@Composable
private fun Placeholders() {
    val pulse = rememberPulse()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        InfoGrid(CARD_COUNT) { index, sizing -> PlaceholderCard(index, pulse, sizing) }
    }
}

@Composable
private fun FailedMessage(message: String, onRetry: () -> Unit) {
    CenteredMessage {
        Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(28.dp))
        Text("Couldn't read device info", style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun NotReadyMessage(message: String) {
    CenteredMessage {
        Icon(Icons.Filled.PhonelinkErase, contentDescription = null, tint = warnColor(), modifier = Modifier.size(28.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
}

@Composable
private fun CenteredMessage(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 360.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { content() }
    }
}

/** "Updated N s ago" (or "Last updated …" in the warning color after a failed poll) and "Copy as text". */
@Composable
private fun Footer(state: DeviceInfoUiState, showing: Boolean) {
    val reduce = LocalInfoReduceMotion.current
    val fade = if (reduce) REDUCED_FADE_MILLIS else CROSSFADE_MILLIS
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        val updatedAt = state.liveUpdatedAt
        AnimatedVisibility(
            visible = showing && updatedAt != null,
            enter = fadeIn(tween(fade, easing = EmphasizedDecelerate)),
            exit = fadeOut(tween(fade, easing = EmphasizedAccelerate)),
        ) {
            // Holds the last time while fading out.
            val at = rememberLastNonNull(updatedAt)
            if (at != null) UpdatedAge(at, state.liveStale)
        }
        Spacer(Modifier.weight(1f))
        AnimatedVisibility(
            visible = showing,
            enter = fadeIn(tween(fade, easing = EmphasizedDecelerate)),
            exit = fadeOut(tween(fade, easing = EmphasizedAccelerate)),
        ) {
            CopyButton(
                text = {
                    InfoText.report(
                        title = state.title ?: state.serial.orEmpty(),
                        serial = state.serial.orEmpty(),
                        static = (state.static as? Load.Ready)?.value,
                        live = (state.live as? Load.Ready)?.value,
                    )
                },
                label = "Copy as text",
            )
        }
    }
}

@Composable
private fun rememberLastNonNull(value: Long?): Long? = rememberLastReady(if (value != null) Load.Ready(value) else Load.Loading)

/** Seconds since [at], ticking once a second; the number ticks like the card numbers. */
@Composable
private fun UpdatedAge(at: Long, stale: Boolean) {
    val now by produceState(System.currentTimeMillis(), at) {
        while (true) {
            value = System.currentTimeMillis()
            // Wake on the next whole second after [at], so the count turns over evenly.
            delay(1_000 - ((value - at) % 1_000).coerceIn(0, 999))
        }
    }
    val color by animateColorAsState(
        if (stale) warnColor() else MaterialTheme.colorScheme.onSurfaceVariant, tween(CROSSFADE_MILLIS), label = "ageColor",
    )
    val reduce = LocalInfoReduceMotion.current
    val style = MaterialTheme.typography.bodySmall
    Row(verticalAlignment = Alignment.CenterVertically) {
        AnimatedContent(if (stale) "Last updated" else "Updated", transitionSpec = { crossFade(reduce) }, label = "agePrefix") {
            Text(it, style = style, color = color)
        }
        Text(" ", style = style)
        TickingText(ageText(now - at), style = style, color = color)
    }
}

/** "just now", "12 s ago", "3 min ago". */
internal fun ageText(elapsedMillis: Long): String {
    val seconds = (elapsedMillis / 1_000).coerceAtLeast(0)
    return when {
        seconds < 1 -> "just now"
        seconds < 60 -> "$seconds s ago"
        else -> "${seconds / 60} min ago"
    }
}
