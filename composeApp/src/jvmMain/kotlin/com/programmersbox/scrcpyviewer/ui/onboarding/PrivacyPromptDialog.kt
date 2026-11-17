package com.programmersbox.scrcpyviewer.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.programmersbox.scrcpyviewer.state.Settings
import com.programmersbox.scrcpyviewer.ui.common.dialogEntrance
import com.programmersbox.scrcpyviewer.ui.common.dialogWidth
import com.programmersbox.scrcpyviewer.ui.theme.Button

/**
 * Whether to show [PrivacyPromptDialog]: a build with an analytics key, a user who finished the first-run guide before
 * it had the consent page, and no other one-time dialog up ([otherDialogShowing]; the prompt waits for it to close).
 */
fun showPrivacyPrompt(settings: Settings, telemetryEnabled: Boolean, otherDialogShowing: Boolean): Boolean =
    telemetryEnabled && settings.onboardingSeen && !settings.privacyPromptSeen && !otherDialogShowing

/**
 * The onboarding consent page on its own, shown once to users who saw the guide before it had one. Esc counts as
 * Done: the switches save as they change, so both keep what they show.
 */
@Composable
fun PrivacyPromptDialog(privacy: PrivacyChoices, onDone: () -> Unit) {
    Dialog(onDismissRequest = onDone, properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.dialogEntrance().dialogWidth(560.dp).testTag("privacy-prompt-card"),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(28.dp)) {
                PrivacyPage(privacy)
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(onDone) { Text("Done") }
                }
            }
        }
    }
}
