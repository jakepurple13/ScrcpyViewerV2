package com.programmersbox.scrcpyviewer.ui.mirror.files

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.programmersbox.scrcpyviewer.ui.common.CLOSE_MILLIS
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedAccelerate
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedDecelerate
import com.programmersbox.scrcpyviewer.ui.common.OPEN_MILLIS
import com.programmersbox.scrcpyviewer.ui.theme.TextButton
import com.programmersbox.scrcpyviewer.ui.theme.styledCorner
import kotlinx.coroutines.delay

/** The snackbar rises this far as it shows, and stays this long (longer with an action). */
private val SNACK_RISE = 16.dp
private const val SNACK_MILLIS = 4_000L
private const val SNACK_ACTION_MILLIS = 8_000L

/**
 * The browser's latest result, rising 16 dp and fading in; its action (such as Open) runs from the button. While the
 * selection bar shows, the snackbar sits above it ([SELECTION_BAR_HEIGHT]), moving with the bar's open and close timing.
 */
@Composable
internal fun FilesSnackbar(browser: FilesBrowser, motion: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val snack = browser.snack
    // The last snack, so it keeps its text while it leaves. A plain holder.
    val kept = remember { arrayOfNulls<Snack>(1) }
    if (snack != null) kept[0] = snack
    LaunchedEffect(snack) {
        if (snack == null) return@LaunchedEffect
        delay(if (snack.actionLabel != null) SNACK_ACTION_MILLIS else SNACK_MILLIS)
        browser.dismissSnack(snack)
    }
    val barShown = browser.selection.names.isNotEmpty()
    val lift by animateDpAsState(
        if (barShown) SELECTION_BAR_HEIGHT else 0.dp,
        when {
            !motion -> snap()
            barShown -> tween(OPEN_MILLIS, easing = EmphasizedDecelerate)
            else -> tween(CLOSE_MILLIS, easing = EmphasizedAccelerate)
        },
    )
    val rise = with(LocalDensity.current) { SNACK_RISE.roundToPx() }
    AnimatedVisibility(
        snack != null,
        modifier = modifier.padding(bottom = lift),
        enter = if (motion) {
            slideInVertically(tween(OPEN_MILLIS, easing = EmphasizedDecelerate)) { rise } + fadeIn(tween(OPEN_MILLIS))
        } else {
            EnterTransition.None
        },
        exit = if (motion) {
            slideOutVertically(tween(CLOSE_MILLIS, easing = EmphasizedAccelerate)) { rise } + fadeOut(tween(CLOSE_MILLIS))
        } else {
            ExitTransition.None
        },
    ) {
        val shown = kept[0] ?: return@AnimatedVisibility
        Surface(
            Modifier.fillMaxWidth().padding(10.dp),
            shape = styledCorner(10.dp),
            color = colors.inverseSurface,
            contentColor = colors.inverseOnSurface,
            shadowElevation = 6.dp,
        ) {
            Row(Modifier.heightIn(min = 44.dp).padding(start = 14.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(shown.message, Modifier.weight(1f).padding(vertical = 8.dp), fontSize = 13.sp)
                val label = shown.actionLabel
                val action = shown.action
                if (label != null && action != null) {
                    TextButton(
                        onClick = {
                            browser.dismissSnack(shown)
                            action()
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = colors.inversePrimary),
                    ) { Text(label) }
                }
            }
        }
    }
}
