package com.programmersbox.scrcpyviewer.ui.actions

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.ToggleOn
import androidx.compose.material3.Button
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
import com.programmersbox.scrcpyviewer.ui.onboarding.IconBadge
import com.programmersbox.scrcpyviewer.ui.onboarding.PageDots

private class IntroPage(val icon: ImageVector, val title: String, val body: String)

private val introPages = listOf(
    IntroPage(
        Icons.Filled.Terminal, "Your own quick actions",
        "Quick actions run adb shell commands from the mirror window's quick actions sheet. Every action you make works on every device.",
    ),
    IntroPage(
        Icons.Filled.ToggleOn, "Toggle or run once",
        "A toggle has an on command, an off command, and an optional read command that decides whether it shows on or off; " +
            "the read command runs each time the sheet opens.\n\n" +
            "A run-once action has one command; its output goes to a toast, the Shell tab, or nowhere, and it can ask before running.",
    ),
    IntroPage(
        Icons.Filled.DataObject, "Variables",
        "Put {name} in a run-once command and it asks for a value when it runs: free text, a choice from a list, or an installed app. " +
            "The last value you used is remembered for each device.",
    ),
    IntroPage(
        Icons.Filled.IosShare, "Test, then share",
        "Use Test run or Test read on a connected device before saving. Pick which actions to export; " +
            "importing shows every command before anything is added.",
    ),
)

/** Number of pages in [ActionsIntroDialog]. */
val ACTIONS_INTRO_PAGE_COUNT = introPages.size

/** How actions work, in the onboarding dialog's style. Stateless: the caller owns [page] (screenshots pass a fixed one). */
@Composable
fun ActionsIntroDialog(page: Int, onPage: (Int) -> Unit, onFinish: () -> Unit) {
    Dialog(onDismissRequest = onFinish, properties = DialogProperties(dismissOnClickOutside = false)) {
        Surface(
            modifier = Modifier.width(560.dp),
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
                    modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp),
                ) { p ->
                    val content = introPages[p]
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        IconBadge(content.icon)
                        Spacer(Modifier.height(16.dp))
                        Text(content.title, style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(10.dp))
                        Text(content.body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PageDots(page, ACTIONS_INTRO_PAGE_COUNT, Modifier.weight(1f))
                    if (page < ACTIONS_INTRO_PAGE_COUNT - 1) TextButton(onFinish) { Text("Skip") }
                    if (page > 0) TextButton({ onPage(page - 1) }) { Text("Back") }
                    Spacer(Modifier.width(8.dp))
                    if (page < ACTIONS_INTRO_PAGE_COUNT - 1) Button({ onPage(page + 1) }) { Text("Next") }
                    else Button(onFinish) { Text("Got it") }
                }
            }
        }
    }
}
