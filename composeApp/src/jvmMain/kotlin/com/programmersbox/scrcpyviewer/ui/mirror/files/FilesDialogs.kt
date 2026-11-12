package com.programmersbox.scrcpyviewer.ui.mirror.files

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.programmersbox.scrcpyviewer.files.Listing
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedDecelerate
import com.programmersbox.scrcpyviewer.ui.common.OPEN_MILLIS
import com.programmersbox.scrcpyviewer.ui.theme.Button
import com.programmersbox.scrcpyviewer.ui.theme.TextButton
import com.programmersbox.scrcpyviewer.ui.theme.styledCorner

/** Dialogs scale from this and rise [DIALOG_RISE] as they open; out in [DIALOG_OUT_MILLIS]. */
private const val DIALOG_SCALE = 0.92f
private val DIALOG_RISE = 8.dp
private const val DIALOG_OUT_MILLIS = 150

/** Names a dialog lists before "and N more". */
private const val LISTED_NAMES = 4

/** "notes.txt", "Telegram/", … up to four, then "and N more". */
internal fun listedNames(names: List<String>, isDir: (String) -> Boolean): List<String> {
    val shown = names.take(LISTED_NAMES).map { if (isDir(it)) "$it/" else it }
    return if (names.size > LISTED_NAMES) shown + "and ${names.size - LISTED_NAMES} more" else shown
}

/**
 * The browser's dialog, inside the tab: a scrim over it (a click on it cancels) and the dialog scaling from 92 % and
 * rising 8 dp (open timing), out in 150 ms. Tab cycles between the dialog's buttons only.
 */
@Composable
internal fun FilesDialogHost(browser: FilesBrowser, motion: Boolean) {
    val dialog = browser.dialog
    // The last dialog, so it keeps its content while it fades out. A plain holder.
    val kept = remember { arrayOfNulls<FilesDialog>(1) }
    if (dialog != null) kept[0] = dialog
    val state = remember { MutableTransitionState(false) }
    state.targetState = dialog != null
    val shown = kept[0] ?: return
    if (!state.currentState && !state.targetState) return
    val rise = with(LocalDensity.current) { DIALOG_RISE.roundToPx() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visibleState = state,
            enter = if (motion) fadeIn(tween(OPEN_MILLIS)) else EnterTransition.None,
            exit = if (motion) fadeOut(tween(DIALOG_OUT_MILLIS)) else ExitTransition.None,
        ) {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        browser.dismissDialog()
                    },
            )
        }
        AnimatedVisibility(
            visibleState = state,
            enter = if (motion) {
                val spec = tween<Float>(OPEN_MILLIS, easing = EmphasizedDecelerate)
                fadeIn(spec) + scaleIn(spec, initialScale = DIALOG_SCALE) +
                    slideInVertically(tween(OPEN_MILLIS, easing = EmphasizedDecelerate)) { rise }
            } else {
                EnterTransition.None
            },
            exit = if (motion) {
                fadeOut(tween(DIALOG_OUT_MILLIS)) + scaleOut(tween(DIALOG_OUT_MILLIS), targetScale = DIALOG_SCALE)
            } else {
                ExitTransition.None
            },
        ) {
            when (shown) {
                is FilesDialog.Delete -> DeleteDialog(browser, shown)
                is FilesDialog.Clash -> ClashDialog(browser, shown)
            }
        }
    }
}

@Composable
private fun DeleteDialog(browser: FilesBrowser, dialog: FilesDialog.Delete) {
    val colors = MaterialTheme.colorScheme
    val names = dialog.names
    // From the whole listing, not the visible rows: a selected folder the filter now hides keeps its "/".
    val dirs = remember(dialog) {
        (browser.listing as? Listing.Entries)?.entries.orEmpty().filter { it.isDir }.mapTo(HashSet()) { it.name }
    }
    val title = if (names.size == 1) "Delete “${names.single()}”?" else "Delete ${names.size} items?"
    DialogCard(
        title,
        buttons = listOf(
            DialogButton("Cancel") { browser.dismissDialog() },
            DialogButton("Delete", danger = true, initialFocus = true) { browser.confirmDelete() },
        ),
    ) {
        Names(listedNames(names) { it in dirs })
        if (dialog.hasFolder) Text("Folders are deleted with everything inside.", fontSize = 13.sp, color = colors.onSurfaceVariant)
        Text("This removes it from the phone and can’t be undone.", fontSize = 13.sp, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun ClashDialog(browser: FilesBrowser, dialog: FilesDialog.Clash) {
    val clashing = dialog.clashing
    val where = folderLabel(dialog.toDir)
    val title = if (clashing.size == 1) "“${clashing.single()}” is already in $where" else "${clashing.size} files are already in $where"
    DialogCard(
        title,
        buttons = listOf(
            DialogButton("Skip", initialFocus = true) { dialog.onChoice(ClashChoice.Skip) },
            DialogButton("Keep both") { dialog.onChoice(ClashChoice.KeepBoth) },
            DialogButton("Replace") { dialog.onChoice(ClashChoice.Replace) },
        ),
    ) {
        if (clashing.size > 1) Names(listedNames(clashing) { false })
    }
}

@Composable
private fun Names(lines: List<String>) {
    Column(Modifier.padding(vertical = 2.dp)) {
        for (line in lines) {
            Text(line, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private class DialogButton(val label: String, val danger: Boolean = false, val initialFocus: Boolean = false, val onClick: () -> Unit)

/** A card with [title], [body] and [buttons] at its end; focus starts on the button marked for it and stays inside. */
@Composable
private fun DialogCard(title: String, buttons: List<DialogButton>, body: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val requesters = remember(buttons.size) { List(buttons.size) { FocusRequester() } }
    // Which button has focus, for Tab's cycle. A plain holder.
    val focused = remember { IntArray(1) { -1 } }
    val start = buttons.indexOfFirst { it.initialFocus }.coerceAtLeast(0)
    LaunchedEffect(Unit) { runCatching { requesters[start].requestFocus() } }
    Surface(
        Modifier.padding(24.dp).widthIn(max = 360.dp)
            .onPreviewKeyEvent { event ->
                if (event.key != Key.Tab) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyDown) {
                    val from = if (focused[0] < 0) start else focused[0]
                    val step = if (event.isShiftPressed) -1 else 1
                    runCatching { requesters[(from + step).mod(buttons.size)].requestFocus() }
                }
                true
            },
        shape = styledCorner(20.dp),
        color = colors.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            body()
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                buttons.forEachIndexed { i, b ->
                    val m = Modifier.focusRequester(requesters[i]).onFocusChanged {
                        if (it.isFocused) focused[0] = i else if (focused[0] == i) focused[0] = -1
                    }
                    if (b.danger) {
                        Button(
                            onClick = b.onClick, modifier = m,
                            colors = ButtonDefaults.buttonColors(containerColor = colors.error, contentColor = colors.onError),
                        ) { Text(b.label) }
                    } else {
                        TextButton(onClick = b.onClick, modifier = m) { Text(b.label) }
                    }
                }
            }
        }
    }
}
