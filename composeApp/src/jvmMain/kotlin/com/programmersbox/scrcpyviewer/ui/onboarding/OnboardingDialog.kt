package com.programmersbox.scrcpyviewer.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.programmersbox.scrcpyviewer.AppIcon
import com.programmersbox.scrcpyviewer.ui.common.LocalReduceMotion
import com.programmersbox.scrcpyviewer.ui.common.animateHeight
import com.programmersbox.scrcpyviewer.ui.common.dialogEntrance
import com.programmersbox.scrcpyviewer.ui.common.dialogWidth
import com.programmersbox.scrcpyviewer.ui.common.layoutSpring
import com.programmersbox.scrcpyviewer.ui.common.stackMotion
import com.programmersbox.scrcpyviewer.ui.theme.Button
import com.programmersbox.scrcpyviewer.ui.theme.Switch
import com.programmersbox.scrcpyviewer.ui.theme.TextButton
import com.programmersbox.scrcpyviewer.ui.theme.styledCorner

/**
 * The consent switches on the last onboarding page and in [PrivacyPromptDialog]: the current values and where a
 * change goes. Each change is saved at once, so closing the page any way keeps what it shows.
 */
data class PrivacyChoices(
    val usage: Boolean,
    val crash: Boolean,
    val onUsage: (Boolean) -> Unit,
    val onCrash: (Boolean) -> Unit,
)

/** Pages in [OnboardingDialog]: the consent page is added only in a build with an analytics key ([privacy] not null). */
fun onboardingPageCount(privacy: PrivacyChoices?): Int = if (privacy == null) 4 else 5

/** A minimum height read at layout time, so the animated value does not recompose the dialog every frame. */
private fun Modifier.minHeightOf(min: State<Dp>): Modifier = layout { measurable, constraints ->
    val floor = min.value.roundToPx().coerceIn(constraints.minHeight, constraints.maxHeight)
    val placeable = measurable.measure(constraints.copy(minHeight = floor))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/**
 * First-run walkthrough. Stateless: the caller owns [page] (screenshots pass a fixed one). [privacy] adds the consent
 * page last; null (a build without an analytics key) leaves it out.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingDialog(
    page: Int,
    onPage: (Int) -> Unit,
    onFinish: () -> Unit,
    privacy: PrivacyChoices?,
) {
    val pageCount = onboardingPageCount(privacy)
    Dialog(onDismissRequest = onFinish, properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.dialogEntrance().dialogWidth(560.dp).testTag("onboarding-card"),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            BoxWithConstraints {
                // The tall page only when the window has room for it, the footer and the padding; otherwise the
                // page fits its content and the card scrolls.
                val reduce = LocalReduceMotion.current
                val pageMinHeight = animateDpAsState(
                    if (maxHeight >= 490.dp) 370.dp else 0.dp,
                    if (reduce) snap() else layoutSpring(),
                    label = "page min height",
                )
                val narrow = maxWidth < 400.dp
                Column(Modifier.verticalScroll(rememberScrollState()).padding(28.dp)) {
                    AnimatedContent(
                        targetState = page,
                        transitionSpec = {
                            val dir = if (targetState > initialState) 1 else -1
                            (slideInHorizontally { it / 4 * dir } + fadeIn()) togetherWith
                                (slideOutHorizontally { -it / 4 * dir } + fadeOut())
                        },
                        modifier = Modifier.fillMaxWidth().minHeightOf(pageMinHeight),
                    ) { p ->
                        when (p) {
                            0 -> WelcomePage()
                            1 -> UsbDebuggingPage()
                            2 -> ConnectPage()
                            3 -> MirrorPage()
                            else -> if (privacy != null) PrivacyPage(privacy) else MirrorPage()
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    LookaheadScope {
                        val scope = this
                        // Under 400 dp the page dots take a full line above the buttons.
                        FlowRow(
                            Modifier.fillMaxWidth().animateHeight(),
                            horizontalArrangement = Arrangement.End,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            itemVerticalAlignment = Alignment.CenterVertically,
                        ) {
                            PageDots(
                                page,
                                pageCount,
                                (if (narrow) Modifier.fillMaxWidth() else Modifier.weight(1f)).stackMotion(scope).testTag("page-dots"),
                            )
                            Row(Modifier.stackMotion(scope), verticalAlignment = Alignment.CenterVertically) {
                                if (page < pageCount - 1) TextButton(onFinish) { Text("Skip") }
                                if (page > 0) TextButton({ onPage(page - 1) }) { Text("Back") }
                                Spacer(Modifier.width(8.dp))
                                if (page < pageCount - 1) {
                                    Button({ onPage(page + 1) }) { Text("Next") }
                                } else {
                                    // The consent page ends with Done: the switches above it are the last choice.
                                    Button(onFinish) { Text(if (privacy != null) "Done" else "Get started") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val PIP_FADE_MILLIS = 150

@Composable
internal fun PageDots(page: Int, count: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val reduce = LocalReduceMotion.current
        repeat(count) { i ->
            val active = i == page
            // The active pip widens with a spring (snaps under reduce motion); its color fades.
            val width by animateDpAsState(
                if (active) 20.dp else 8.dp,
                if (reduce) snap() else layoutSpring(),
                label = "pip width",
            )
            val color by animateColorAsState(
                if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                tween(PIP_FADE_MILLIS),
                label = "pip color",
            )
            Box(Modifier.size(width = width.coerceAtLeast(0.dp), height = 8.dp).background(color, CircleShape))
        }
    }
}

@Composable
internal fun PageHeader(icon: @Composable () -> Unit, title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        icon()
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun IconBadge(icon: ImageVector) {
    Box(
        Modifier.size(64.dp).background(MaterialTheme.colorScheme.primaryContainer, styledCorner(20.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
internal fun FeatureRow(icon: ImageVector, title: String, body: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NumberedStep(n: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(28.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("$n", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary)
        }
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun WelcomePage() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeader(
            icon = { Image(AppIcon.painter, null, Modifier.size(72.dp)) },
            title = "Welcome to Scrcpy Vwr",
            subtitle = "Mirror and control your Android devices from the desktop.",
        )
        Spacer(Modifier.height(4.dp))
        FeatureRow(Icons.Filled.Cast, "Mirror with audio", "Low-latency video and sound, nothing to install on the phone.")
        FeatureRow(Icons.Filled.TouchApp, "Full control", "Your mouse and keyboard drive the device.")
        FeatureRow(Icons.Filled.Tune, "Presets", "Save option sets and reuse them across devices.")
    }
}

@Composable
private fun UsbDebuggingPage() {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageHeader(
            icon = { IconBadge(Icons.Filled.DeveloperMode) },
            title = "Turn on USB debugging",
            subtitle = "One-time setup on each phone or tablet.",
        )
        Spacer(Modifier.height(4.dp))
        NumberedStep(1, "Open Settings › About phone.")
        NumberedStep(2, "Tap Build number 7 times to unlock Developer options.")
        NumberedStep(3, "Open Settings › System › Developer options.")
        NumberedStep(4, "Turn on USB debugging (and Wireless debugging for Wi-Fi).")
    }
}

@Composable
private fun ConnectPage() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeader(
            icon = { IconBadge(Icons.Filled.Usb) },
            title = "Connect a device",
            subtitle = "It shows up in Devices as soon as adb sees it.",
        )
        Spacer(Modifier.height(4.dp))
        FeatureRow(
            Icons.Filled.Usb, "With a USB cable",
            "Plug in, then tap Allow on the phone's \"Allow USB debugging?\" prompt.",
        )
        FeatureRow(
            Icons.Filled.Mouse, "Then cut the cable",
            "Right-click it in Devices › Connect over Wi-Fi, then unplug. Same network required.",
        )
        FeatureRow(
            Icons.Filled.Wifi, "Or pair over Wi-Fi (Android 11+)",
            "No cable: Devices › Pair over Wi-Fi, then scan the QR code.",
        )
    }
}

@Composable
private fun MirrorPage() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeader(
            icon = { IconBadge(Icons.Filled.Cast) },
            title = "Start mirroring",
            subtitle = "A few things worth knowing.",
        )
        Spacer(Modifier.height(4.dp))
        FeatureRow(Icons.Filled.Cast, "Double-click a device", "Or select it, tune options, and press Mirror.")
        FeatureRow(Icons.Filled.FileUpload, "Drag files onto the mirror", "APKs install; other files go to /sdcard/Download/.")
        FeatureRow(Icons.Filled.PhotoCamera, "Screenshots and recordings", "Saved to the capture folder chosen in Settings.")
    }
}

/** The consent page: what is sent, and a switch for each kind. */
@Composable
internal fun PrivacyPage(privacy: PrivacyChoices) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeader(
            icon = { IconBadge(Icons.Filled.Insights) },
            title = "Help improve Scrcpy Vwr",
            subtitle = "Send anonymous statistics and crash reports. No device names, files, apps or commands are ever " +
                "included. You can change this any time in Settings › Privacy.",
        )
        Spacer(Modifier.height(4.dp))
        Column(
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, styledCorner(16.dp))
                .padding(vertical = 4.dp),
        ) {
            PrivacySwitchRow("Usage statistics", privacy.usage, privacy.onUsage)
            PrivacySwitchRow("Crash reports", privacy.crash, privacy.onCrash)
        }
    }
}

/** A label and its switch; the whole row toggles. */
@Composable
private fun PrivacySwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}
