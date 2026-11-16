package com.programmersbox.scrcpyviewer.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.APP_VERSION
import com.programmersbox.scrcpyviewer.AppIcon
import com.programmersbox.scrcpyviewer.BuildInfo
import com.programmersbox.scrcpyviewer.ui.common.COPY_CONFIRM_MILLIS
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedDecelerate
import com.programmersbox.scrcpyviewer.ui.common.OPEN_MILLIS
import com.programmersbox.scrcpyviewer.ui.common.openWebUrl
import com.programmersbox.scrcpyviewer.ui.common.systemReducesMotion
import com.programmersbox.scrcpyviewer.ui.theme.OutlinedButton
import com.programmersbox.scrcpyviewer.ui.theme.TextButton
import androidx.compose.runtime.withFrameMillis
import kotlinx.coroutines.launch
import java.awt.datatransfer.StringSelection

/** The app's repository, opened by About's GitHub button. */
const val SCRCPY_VWR_GITHUB_URL = "https://github.com/jakepurple13/ScrcpyViewerV2"

/** The logo turns and grows in over this long when About opens. */
internal const val ABOUT_LOGO_MILLIS = 500
private const val ABOUT_LOGO_FROM_SCALE = 0.6f
private const val ABOUT_LOGO_FROM_DEGREES = -12f

/** What About shows: this build's versions, the bundled adb's, and the runtime's. */
data class AboutInfo(
    val appVersion: String,
    val scrcpyServerVersion: String,
    /** The bundled adb's platform-tools version (it is downloaded as "latest", so read from the binary); null unknown. */
    val adbVersion: String?,
    val ffmpegVersion: String,
    val javacppVersion: String,
    val javaVersion: String,
    val composeVersion: String,
    val os: String,
) {
    companion object {
        /** This build and runtime, with [adbVersion] as `adb version` reports it (for example "1.0.41 (36.0.0)"). */
        fun current(adbVersion: String?) = AboutInfo(
            appVersion = APP_VERSION,
            scrcpyServerVersion = BuildInfo.SCRCPY_SERVER_VERSION,
            adbVersion = platformToolsVersion(adbVersion),
            ffmpegVersion = BuildInfo.FFMPEG_VERSION,
            javacppVersion = BuildInfo.JAVACPP_VERSION,
            javaVersion = System.getProperty("java.version").orEmpty(),
            composeVersion = BuildInfo.COMPOSE_VERSION,
            os = listOf("os.name", "os.version", "os.arch").map { System.getProperty(it).orEmpty() }
                .let { (name, version, arch) -> "$name $version ($arch)" },
        )
    }
}

/**
 * The platform-tools release in `adb version`'s "1.0.41 (36.0.0)" form ("36.0.0"); the whole string when it has no
 * parenthesised part; null when unknown.
 */
internal fun platformToolsVersion(adbVersion: String?): String? {
    val raw = adbVersion?.trim()?.takeIf { it.isNotEmpty() && it != UNKNOWN_VERSION } ?: return null
    return Regex("""\(([^)]+)\)""").find(raw)?.groupValues?.get(1)?.trim() ?: raw
}

/** The About rows, label to value, in order. */
internal fun aboutRows(info: AboutInfo): List<Pair<String, String>> = listOf(
    "Mirroring" to "scrcpy-server ${info.scrcpyServerVersion}",
    "Android tools" to "adb ${info.adbVersion ?: "…"}",
    "Video" to "FFmpeg ${info.ffmpegVersion}",
    "Runtime" to "Java ${info.javaVersion} · Compose ${info.composeVersion}",
)

/** What Copy details puts on the clipboard: every version and the OS, for a bug report. */
fun aboutDetails(info: AboutInfo): String = listOf(
    "Scrcpy Vwr ${info.appVersion}",
    "scrcpy-server ${info.scrcpyServerVersion}",
    "adb ${info.adbVersion ?: "unknown"}",
    "FFmpeg ${info.ffmpegVersion} (JavaCPP ${info.javacppVersion})",
    "Java ${info.javaVersion} · Compose ${info.composeVersion}",
    "OS ${info.os}",
).joinToString("\n")

/** Copies [text] with the app clipboard (the native pasteboard on macOS, see ProvideAppClipboard). */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun rememberClipboardCopy(): (String) -> Unit {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    return { text -> scope.launch { clipboard.setClipEntry(ClipEntry(StringSelection(text))) } }
}

/**
 * Settings › Help › About: the app's name and version, what it bundles and runs on, and Copy details (which reads
 * "Copied" for 1.5 s), GitHub, Open-source licenses and Close. Opens and closes like [HelpDialog]; the logo turns and
 * grows in over 500 ms (not with reduced motion).
 */
@Composable
fun AboutDialog(
    visible: Boolean,
    info: AboutInfo,
    onOpenLicenses: () -> Unit,
    onDismiss: () -> Unit,
    motion: Boolean = remember { !systemReducesMotion() },
    onCopy: ((String) -> Unit)? = null,
    onOpenUrl: (String) -> Unit = { openWebUrl(it) },
) {
    val copy = onCopy ?: rememberClipboardCopy()
    HelpDialog(visible, onDismiss, motion, cardModifier = Modifier.widthIn(max = 440.dp).fillMaxWidth()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Column(
                Modifier.padding(start = 24.dp, end = 24.dp, top = 26.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AboutLogo(motion)
                Text("Scrcpy Vwr", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "Version ${info.appVersion}",
                    style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                aboutRows(info).forEach { (label, value) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.End, modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            FlowRow(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                CopyDetailsButton { copy(aboutDetails(info)) }
                OutlinedButton(onClick = { onOpenUrl(SCRCPY_VWR_GITHUB_URL) }) { Text("GitHub") }
                OutlinedButton(onClick = onOpenLicenses) { Text("Open-source licenses") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    }
}

@Composable
private fun AboutLogo(motion: Boolean) {
    val progress = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (motion) progress.animateTo(1f, tween(ABOUT_LOGO_MILLIS, easing = EmphasizedDecelerate))
    }
    Image(
        AppIcon.painter, contentDescription = null,
        modifier = Modifier.size(64.dp).graphicsLayer {
            val p = progress.value
            val scale = ABOUT_LOGO_FROM_SCALE + (1f - ABOUT_LOGO_FROM_SCALE) * p
            scaleX = scale
            scaleY = scale
            rotationZ = ABOUT_LOGO_FROM_DEGREES * (1f - p)
            alpha = p
        },
    )
}

/** "Copy details", which reads "Copied" in the secondary container for [COPY_CONFIRM_MILLIS], crossfading both ways. */
@Composable
private fun CopyDetailsButton(onCopy: () -> Unit) {
    // Bumped per click; 0 = not copied. A new value restarts the revert timer.
    var copies by remember { mutableIntStateOf(0) }
    val copied = copies > 0
    LaunchedEffect(copies) {
        if (copies == 0) return@LaunchedEffect
        // Counted in frame time, like the other "Copied" confirmations, so tests can step it.
        val start = withFrameMillis { it }
        while (withFrameMillis { it } - start < COPY_CONFIRM_MILLIS) Unit
        copies = 0
    }
    val tint = tween<Color>(OPEN_MILLIS, easing = EmphasizedDecelerate)
    val container by animateColorAsState(
        if (copied) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, tint, label = "copiedContainer",
    )
    val content by animateColorAsState(
        if (copied) MaterialTheme.colorScheme.onSecondaryContainer else ButtonDefaults.outlinedButtonColors().contentColor,
        tint, label = "copiedContent",
    )
    OutlinedButton(
        onClick = {
            onCopy()
            copies++
        },
        colors = ButtonDefaults.outlinedButtonColors(containerColor = container, contentColor = content),
    ) {
        AnimatedContent(
            targetState = copied,
            transitionSpec = { fadeIn(tween(OPEN_MILLIS, easing = EmphasizedDecelerate)) togetherWith fadeOut(tween(OPEN_MILLIS / 2)) },
            contentAlignment = Alignment.Center,
            label = "copyDetails",
        ) { isCopied -> Text(if (isCopied) "Copied" else "Copy details") }
    }
}
