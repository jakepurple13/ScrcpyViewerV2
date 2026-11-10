package com.programmersbox.scrcpyviewer.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import com.programmersbox.scrcpyviewer.ui.theme.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.programmersbox.scrcpyviewer.AppIcon
import com.programmersbox.scrcpyviewer.ui.common.dialogEntrance

const val ONBOARDING_PAGE_COUNT = 4

/** First-run walkthrough. Stateless: the caller owns [page] (screenshots pass a fixed one). */
@Composable
fun OnboardingDialog(
    page: Int,
    onPage: (Int) -> Unit,
    onFinish: () -> Unit,
) {
    Dialog(onDismissRequest = onFinish, properties = DialogProperties(dismissOnClickOutside = false)) {
        Surface(
            modifier = Modifier.dialogEntrance().width(560.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(28.dp)) {
                AnimatedContent(
                    targetState = page,
                    transitionSpec = {
                        val dir = if (targetState > initialState) 1 else -1
                        (slideInHorizontally { it / 4 * dir } + fadeIn()) togetherWith
                            (slideOutHorizontally { -it / 4 * dir } + fadeOut())
                    },
                    modifier = Modifier.fillMaxWidth().height(370.dp),
                ) { p ->
                    when (p) {
                        0 -> WelcomePage()
                        1 -> UsbDebuggingPage()
                        2 -> ConnectPage()
                        else -> MirrorPage()
                    }
                }
                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PageDots(page, ONBOARDING_PAGE_COUNT, Modifier.weight(1f))
                    if (page < ONBOARDING_PAGE_COUNT - 1) TextButton(onFinish) { Text("Skip") }
                    if (page > 0) TextButton({ onPage(page - 1) }) { Text("Back") }
                    Spacer(Modifier.width(8.dp))
                    if (page < ONBOARDING_PAGE_COUNT - 1) {
                        Button({ onPage(page + 1) }) { Text("Next") }
                    } else {
                        Button(onFinish) { Text("Get started") }
                    }
                }
            }
        }
    }
}

@Composable
internal fun PageDots(page: Int, count: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { i ->
            val active = i == page
            Box(
                Modifier.size(width = if (active) 20.dp else 8.dp, height = 8.dp)
                    .background(
                        if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        CircleShape,
                    ),
            )
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
        Modifier.size(64.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(20.dp)),
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
