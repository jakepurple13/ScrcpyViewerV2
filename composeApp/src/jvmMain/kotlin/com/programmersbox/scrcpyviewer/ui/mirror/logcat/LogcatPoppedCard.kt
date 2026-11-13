package com.programmersbox.scrcpyviewer.ui.mirror.logcat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedDecelerate
import com.programmersbox.scrcpyviewer.ui.mirror.systemReducesMotion
import com.programmersbox.scrcpyviewer.ui.theme.Button
import com.programmersbox.scrcpyviewer.ui.theme.FilledTonalButton

/** How far the own-window card rises as it fades in. */
private val CARD_RISE = 12.dp
private const val SWAP_MILLIS = 400

/**
 * The sheet's Logcat tab: the panel itself ([LogcatTab]), or, while [LogcatController.popped], a card saying it is in
 * its own window. The two cross-fade; the card rises 12 dp as it comes in. [motion] is false under the OS's reduce
 * motion.
 */
@Composable
fun LogcatSheetTab(
    controller: LogcatController,
    sheetWidth: Dp,
    motion: Boolean = remember { !systemReducesMotion() },
) {
    val rise = with(LocalDensity.current) { CARD_RISE.roundToPx() }
    AnimatedContent(
        controller.popped,
        Modifier.fillMaxSize(),
        transitionSpec = {
            when {
                !motion -> EnterTransition.None togetherWith ExitTransition.None
                // To the card: it rises; back to the panel: a plain cross-fade.
                targetState -> (fadeIn(tween(SWAP_MILLIS, delayMillis = 60)) +
                    slideInVertically(tween(SWAP_MILLIS, easing = EmphasizedDecelerate)) { rise }) togetherWith fadeOut(tween(160))
                else -> fadeIn(tween(300, delayMillis = 60)) togetherWith fadeOut(tween(160))
            }
        },
    ) { popped ->
        if (popped) {
            LogcatPoppedCard(onShow = controller::raise, onDock = controller::dock)
        } else {
            LogcatTab(controller, sheetWidth, motion)
        }
    }
}

/** "Logcat is open in its own window", with Show window ([onShow]) and Move back here ([onDock]). */
@Composable
fun LogcatPoppedCard(onShow: () -> Unit, onDock: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().background(scheme.surfaceContainerLowest).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(64.dp).background(scheme.primaryContainer, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, Modifier.size(30.dp), tint = scheme.onPrimaryContainer)
        }
        Text(
            "Logcat is open in its own window",
            style = MaterialTheme.typography.titleSmall, color = scheme.onSurface, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Text(
            "It keeps running while you use the other tabs. Close that window to move it back here.",
            Modifier.widthIn(max = 300.dp),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 19.5.sp),
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onShow) { Text("Show window") }
            FilledTonalButton(
                onClick = onDock,
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = scheme.surfaceContainerHighest, contentColor = scheme.onSurface),
            ) { Text("Move back here") }
        }
    }
}
