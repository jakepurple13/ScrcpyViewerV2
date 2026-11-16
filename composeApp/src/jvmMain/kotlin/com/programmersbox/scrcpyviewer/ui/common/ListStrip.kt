package com.programmersbox.scrcpyviewer.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.programmersbox.scrcpyviewer.ui.theme.styledCorner

/** One entry of a [ListStrip]: [avatar] sits in a 40 dp circle, [label] sits under it and in its tooltip. */
@Immutable
data class StripItem(
    val key: Any,
    val label: String,
    val avatar: @Composable () -> Unit,
    val badgeColor: Color? = null,
)

private val AvatarSize = 40.dp
private val BadgeSize = 12.dp
private val BadgeRing = 2.dp
private val LabelMaxWidth = 64.dp

/** Longest fade allowed under reduced motion. */
private const val ReducedColorMillis = 150

/**
 * The narrow list: a `Show list` chevron on top, one avatar per [items] (the [selectedKey] one filled), and
 * [actions] at the bottom. It is always [StripWidth] wide, however long a label is.
 */
@Composable
fun ListStrip(
    items: List<StripItem>,
    selectedKey: Any?,
    onSelect: (Any) -> Unit,
    onShowList: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier.width(StripWidth).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconButton(onClick = onShowList) {
            Icon(Icons.Filled.ChevronRight, contentDescription = "Show list")
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            for (item in items) {
                StripEntry(item, selected = item.key == selectedKey, onClick = { onSelect(item.key) })
            }
        }
        actions()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StripEntry(item: StripItem, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val animation = tween<Color>(if (LocalReduceMotion.current) ReducedColorMillis else 220)
    val fill by animateColorAsState(if (selected) colors.primary else colors.surfaceVariant, animation, label = "stripFill")
    val onFill by animateColorAsState(if (selected) colors.onPrimary else colors.onSurfaceVariant, animation, label = "stripOnFill")
    TooltipArea(tooltip = { LabelTooltip(item.label) }) {
        Column(
            Modifier
                .padding(vertical = 4.dp)
                .clickable(onClickLabel = "Open ${item.label}", role = Role.Button, onClick = onClick)
                .semantics { contentDescription = item.label; this.selected = selected },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(AvatarSize + BadgeRing)) {
                Box(
                    Modifier.size(AvatarSize).clip(CircleShape).background(fill),
                    contentAlignment = Alignment.Center,
                ) {
                    CompositionLocalProvider(LocalContentColor provides onFill) { item.avatar() }
                }
                item.badgeColor?.let { badge ->
                    Box(
                        Modifier.align(Alignment.BottomEnd).size(BadgeSize)
                            .background(colors.surface, CircleShape)
                            .padding(BadgeRing)
                            .background(badge, CircleShape),
                    )
                }
            }
            Text(
                item.label,
                Modifier.widthIn(max = LabelMaxWidth),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Opens the full list over the open item: a swap icon, the open item's [label] and a caret. */
@Composable
fun ListSwitcherChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.semantics { contentDescription = "Switch item" },
        shape = styledCorner(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(Modifier.height(32.dp).padding(start = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.SwapHoriz, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, Modifier.widthIn(max = 160.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, Modifier.size(20.dp))
        }
    }
}
