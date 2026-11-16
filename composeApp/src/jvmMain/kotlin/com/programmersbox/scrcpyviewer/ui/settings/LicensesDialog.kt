package com.programmersbox.scrcpyviewer.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.entity.License
import com.mikepenz.aboutlibraries.ui.compose.produceLibraries
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedAccelerate
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedDecelerate
import com.programmersbox.scrcpyviewer.ui.common.openWebUrl
import com.programmersbox.scrcpyviewer.ui.common.systemReducesMotion
import com.programmersbox.scrcpyviewer.ui.theme.OutlinedTextField
import com.programmersbox.scrcpyviewer.ui.theme.TextButton
import com.programmersbox.scrcpyviewer.ui.theme.styledCorner
import java.net.URI

// --- Data ---------------------------------------------------------------------------------------------------------

/** Where the generated licence list sits on the classpath (composeApp/build.gradle.kts, `aboutLibraries`). */
const val LICENSES_RESOURCE = "/aboutlibraries.json"

/** The tag the bundled tools carry in composeApp/config/libraries. */
private const val BUNDLED_TAG = "bundled"

/** The bundled tools, in the order they are listed. */
internal val BUNDLED_IDS = listOf("bundled:ffmpeg", "bundled:adb", "bundled:scrcpy-server")
internal const val ADB_ID = "bundled:adb"

enum class LicenseGroup(val title: String) { Bundled("Bundled tools"), Libraries("Libraries") }

/** The licence chip's colour family. */
enum class LicenseFamily { Apache, Lgpl, Mit, Other }

data class LicenseLink(val label: String, val url: String)

/** One licence of an entry: its full text when the build found it, else only where to read it. */
data class LicenseText(val name: String, val content: String?, val url: String?)

@Immutable
data class LicenseEntry(
    val id: String,
    val group: LicenseGroup,
    val name: String,
    val version: String?,
    /** The chip: short licence names, for example "Apache 2.0" or "LGPL 3.0+". */
    val licenseLabel: String,
    val family: LicenseFamily,
    /** Full licence names, searched but not shown in the row. */
    val licenseNames: List<String>,
    val developers: String,
    val description: String?,
    val links: List<LicenseLink>,
    val texts: List<LicenseText>,
)

/** The generated licence list on the classpath; null when the build left it out. */
fun readLicensesJson(): String? =
    LicenseEntry::class.java.getResourceAsStream(LICENSES_RESOURCE)?.use { it.readBytes().decodeToString() }

/** The licence list as [LicenseEntry]s; null while it loads. [adbVersion] fills the bundled adb's version. */
@Composable
fun rememberLicenseEntries(adbVersion: String?): State<List<LicenseEntry>?> {
    val libs by produceLibraries { readLicensesJson() ?: """{"libraries":[],"licenses":{}}""" }
    return remember(adbVersion) { derivedStateOf { libs?.let { licenseEntries(it, adbVersion) } } }
}

/** A short chip name for [license]: its SPDX id made readable ("LGPL-3.0-or-later" → "LGPL 3.0+"), else its name. */
internal fun shortLicenseName(license: License): String {
    val spdx = license.spdxId?.takeIf { it.isNotBlank() } ?: return license.name
    return spdx.replace("-or-later", "+").replace("-only", "").replace('-', ' ')
}

internal fun licenseFamily(license: License?): LicenseFamily {
    val key = "${license?.spdxId.orEmpty()} ${license?.name.orEmpty()}".lowercase()
    return when {
        "apache" in key -> LicenseFamily.Apache
        "mit" in key.split(' ', '-') || key.startsWith("mit") -> LicenseFamily.Mit
        "gpl" in key -> LicenseFamily.Lgpl
        else -> LicenseFamily.Other
    }
}

/** "Source (repo ref)" for a GitHub tree link, "Source (AOSP)" for Android's, else "Source". */
internal fun sourceLabel(url: String): String {
    val uri = runCatching { URI(url) }.getOrNull() ?: return "Source"
    val parts = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
    return when {
        uri.host == "github.com" && parts.size >= 4 && parts[2] == "tree" -> "Source (${parts[1]} ${parts[3]})"
        uri.host == "github.com" && parts.size >= 2 -> "Source (${parts[1]})"
        uri.host?.endsWith("googlesource.com") == true -> "Source (AOSP)"
        else -> "Source"
    }
}

/** A website's label: its host and path without the scheme, "www." or a trailing slash. */
internal fun websiteLabel(url: String): String =
    url.substringAfter("://").removePrefix("www.").trimEnd('/')

private fun Library.toEntry(group: LicenseGroup, versionOverride: String?): LicenseEntry {
    // Licences the build identified (SPDX) first: they have the readable names and the texts.
    val licenses = licenses.sortedBy { it.spdxId == null }
    val labels = licenses.map(::shortLicenseName).distinct()
    val chip = when {
        labels.isEmpty() -> "Unknown"
        labels.size <= 2 -> labels.joinToString(" / ")
        else -> labels.take(2).joinToString(" / ") + " +${labels.size - 2}"
    }
    val links = buildList {
        website?.takeIf { it.isNotBlank() }?.let { add(LicenseLink(websiteLabel(it), it)) }
        scm?.url?.takeIf { it.isNotBlank() && it != website }?.let { add(LicenseLink(sourceLabel(it), it)) }
    }
    val by = developers.mapNotNull { it.name?.takeIf(String::isNotBlank) }.distinct()
        .ifEmpty { listOfNotNull(organization?.name?.takeIf(String::isNotBlank)) }
    return LicenseEntry(
        id = uniqueId,
        group = group,
        name = name,
        version = versionOverride ?: artifactVersion?.takeIf { it.isNotBlank() },
        licenseLabel = chip,
        family = licenseFamily(licenses.firstOrNull()),
        licenseNames = licenses.map { it.name },
        developers = by.joinToString(", "),
        description = description?.trim()?.takeIf { it.isNotEmpty() },
        links = links,
        texts = licenses.map { LicenseText(it.name, it.licenseContent?.takeIf(String::isNotBlank), it.url) },
    )
}

/**
 * The entries to list: the bundled tools first (FFmpeg, adb, scrcpy-server), then every library by name. [adbVersion]
 * is the bundled adb's platform-tools version, since the build downloads the latest.
 */
fun licenseEntries(libs: Libs, adbVersion: String?): List<LicenseEntry> {
    val (bundled, libraries) = libs.libraries.partition { it.tag == BUNDLED_TAG }
    val tools = bundled.sortedBy { BUNDLED_IDS.indexOf(it.uniqueId).let { i -> if (i < 0) Int.MAX_VALUE else i } }
        .map { it.toEntry(LicenseGroup.Bundled, if (it.uniqueId == ADB_ID) adbVersion else null) }
    // Modules of one library share a group and a name (koin-core, koin-compose, … are all "Koin"): list it once, as
    // its shortest id, with every licence its modules declare.
    val rest = libraries.groupBy { it.uniqueId.substringBefore(':') to it.name }.values
        .map { modules ->
            val main = modules.minBy { it.uniqueId.length }
            main.copy(licenses = modules.flatMapTo(linkedSetOf()) { it.licenses })
        }
        .sortedBy { it.name.lowercase() }
        .map { it.toEntry(LicenseGroup.Libraries, null) }
    return tools + rest
}

/** The entries whose name, licence or developer contains trimmed [query], case-insensitively; all for a blank one. */
fun filterLicenses(entries: List<LicenseEntry>, query: String): List<LicenseEntry> {
    val q = query.trim()
    if (q.isEmpty()) return entries
    return entries.filter { e ->
        e.name.contains(q, true) || e.licenseLabel.contains(q, true) || e.developers.contains(q, true) ||
            e.licenseNames.any { it.contains(q, true) }
    }
}

/** "<n> entries" when nothing is filtered out, else "<shown> of <n>". */
fun licenseCount(shown: Int, total: Int): String = if (shown == total) "$total entries" else "$shown of $total"

const val LICENSES_EMPTY_TEXT = "Nothing matches. Try a licence name such as \"MIT\" or \"Apache\"."

// --- Motion -------------------------------------------------------------------------------------------------------

/** A row unfolds (and folds) over this long; its chevron turns over [CHEVRON_MILLIS]. */
internal const val UNFOLD_MILLIS = 400
internal const val CHEVRON_MILLIS = 350

/** Search results and the empty state fade in over this long. */
internal const val RESULTS_FADE_MILLIS = 300

// --- UI -----------------------------------------------------------------------------------------------------------

private sealed interface ListItem {
    val key: String

    data class Header(val group: LicenseGroup) : ListItem {
        override val key = "group:${group.name}"
    }

    data class Row(val entry: LicenseEntry) : ListItem {
        override val key = "lib:${entry.id}"
    }

    data object Empty : ListItem {
        override val key = "empty"
    }
}

private fun listItems(shown: List<LicenseEntry>, loaded: Boolean): List<ListItem> = buildList {
    var group: LicenseGroup? = null
    shown.forEach {
        if (it.group != group) {
            group = it.group
            add(ListItem.Header(it.group))
        }
        add(ListItem.Row(it))
    }
    if (loaded && shown.isEmpty()) add(ListItem.Empty)
}

/**
 * Settings › Help › Open-source licenses: a search box over the bundled tools then the libraries. Each row shows the
 * name, version, a licence chip and who makes it; a click unfolds its description, links and full licence text (one
 * row open at a time; the chevron turns). Search matches names, licences and developers; results fade in. [entries]
 * null shows an empty list while they load.
 */
@Composable
fun LicensesDialog(
    visible: Boolean,
    entries: List<LicenseEntry>?,
    onDismiss: () -> Unit,
    motion: Boolean = remember { !systemReducesMotion() },
    onOpenUrl: (String) -> Unit = { openWebUrl(it) },
    initialQuery: String = "",
    initialOpen: String? = null,
) {
    HelpDialog(
        visible, onDismiss, motion,
        cardModifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = 720.dp).fillMaxHeight(),
    ) {
        var query by remember { mutableStateOf(initialQuery) }
        var open by remember { mutableStateOf(initialOpen) }
        val all = entries.orEmpty()
        val shown = remember(all, query) { filterLicenses(all, query) }
        val items = remember(shown, entries != null) { listItems(shown, entries != null) }
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        Column {
            Column(
                Modifier.padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Open-source licenses", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "Scrcpy Vwr is built on these. Thank you to everyone who makes them.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                placeholder = { Text("Search libraries or licences", style = MaterialTheme.typography.bodyMedium, maxLines = 1) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (entries != null) {
                        Text(
                            licenseCount(shown.size, all.size), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 12.dp),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 6.dp, bottom = 10.dp)
                    .focusRequester(focus),
            )
            val fadeIn = tween<Float>(if (motion) RESULTS_FADE_MILLIS else HELP_REDUCED_MILLIS, easing = EmphasizedDecelerate)
            val fadeOut = tween<Float>(HELP_REDUCED_MILLIS, easing = EmphasizedAccelerate)
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, bottom = 8.dp),
            ) {
                items(items, key = { it.key }, contentType = { it::class }) { item ->
                    val anim = Modifier.animateItem(
                        fadeInSpec = fadeIn,
                        placementSpec = if (motion) tween(RESULTS_FADE_MILLIS, easing = EmphasizedDecelerate) else null,
                        fadeOutSpec = fadeOut,
                    )
                    when (item) {
                        is ListItem.Header -> Text(
                            item.group.title.uppercase(), style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = anim.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
                        )
                        is ListItem.Row -> LicenseRow(
                            item.entry, query, open = open == item.entry.id, motion = motion,
                            onToggle = { open = if (open == item.entry.id) null else item.entry.id },
                            onOpenUrl = onOpenUrl, modifier = anim.padding(bottom = 4.dp),
                        )
                        ListItem.Empty -> Text(
                            LICENSES_EMPTY_TEXT, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                            modifier = anim.fillMaxWidth().padding(horizontal = 12.dp, vertical = 28.dp),
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 16.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    }
}

/** [text] with each match of [query] on an accent-tinted background. */
@Composable
private fun highlighted(text: String, query: String): AnnotatedString {
    val tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
    return remember(text, query, tint) {
        val ranges = matchRanges(text, query)
        if (ranges.isEmpty()) AnnotatedString(text)
        else buildAnnotatedString {
            append(text)
            ranges.forEach { addStyle(SpanStyle(background = tint), it.first, it.last + 1) }
        }
    }
}

@Composable
private fun LicenseRow(
    entry: LicenseEntry,
    query: String,
    open: Boolean,
    motion: Boolean,
    onToggle: () -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = styledCorner(14.dp)
    val fill by animateColorAsState(
        if (open) MaterialTheme.colorScheme.surfaceContainerLowest else Color.Transparent,
        tween(if (motion) 250 else HELP_REDUCED_MILLIS), label = "licenseRowFill",
    )
    val turn by animateFloatAsState(
        if (open) 180f else 0f,
        tween(if (motion) CHEVRON_MILLIS else HELP_REDUCED_MILLIS, easing = EmphasizedDecelerate), label = "licenseChevron",
    )
    Column(modifier.fillMaxWidth().clip(shape).drawBehind { drawRect(fill) }) {
        Column(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onToggle).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    highlighted(entry.name, query), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                entry.version?.let {
                    Text(
                        it, style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    )
                }
                LicenseChip(entry.licenseLabel, entry.family)
                Icon(
                    Icons.Filled.ExpandMore, contentDescription = if (open) "Fold" else "Unfold",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp).rotate(turn),
                )
            }
            if (entry.developers.isNotEmpty()) {
                Text(
                    entry.developers, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        val unfold = if (motion) UNFOLD_MILLIS else HELP_REDUCED_MILLIS
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(tween(unfold, easing = EmphasizedDecelerate), expandFrom = Alignment.Top) +
                fadeIn(tween(if (motion) RESULTS_FADE_MILLIS else HELP_REDUCED_MILLIS, delayMillis = if (motion) 100 else 0)),
            exit = shrinkVertically(tween(if (motion) 250 else HELP_REDUCED_MILLIS, easing = EmphasizedAccelerate), shrinkTowards = Alignment.Top) +
                fadeOut(tween(HELP_REDUCED_MILLIS)),
        ) {
            LicenseBody(entry, onOpenUrl)
        }
    }
}

@Composable
private fun LicenseBody(entry: LicenseEntry, onOpenUrl: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        entry.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) }
        if (entry.links.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                entry.links.forEach { LinkPill(it.label) { onOpenUrl(it.url) } }
            }
        }
        entry.texts.forEach { text ->
            if (entry.texts.size > 1) {
                Text(text.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (text.content != null) {
                // A long licence scrolls inside its own box, so the list keeps its place.
                Box(
                    Modifier.fillMaxWidth().heightIn(max = 160.dp).clip(styledCorner(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    SelectionContainer {
                        Text(
                            text.content, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, lineHeight = 17.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            } else if (text.url != null) {
                LinkPill("${text.name} (full text)") { onOpenUrl(text.url) }
            }
        }
    }
}

@Composable
private fun LinkPill(label: String, onClick: () -> Unit) {
    val shape = styledCorner(14.dp)
    Text(
        label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clip(shape).border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** Chip colours per licence family, light and dark (the mockup's green, amber and violet). */
@Composable
private fun chipColors(family: LicenseFamily): Pair<Color, Color> {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return when (family) {
        LicenseFamily.Apache -> if (dark) Color(0xFF1D3A28) to Color(0xFF9FDCB5) else Color(0xFFDCEFE2) to Color(0xFF1F5C38)
        LicenseFamily.Lgpl -> if (dark) Color(0xFF3D3018) to Color(0xFFF0C46A) else Color(0xFFF6E7C4) to Color(0xFF6B4600)
        LicenseFamily.Mit -> if (dark) Color(0xFF2C2546) to Color(0xFFCDBFFF) else Color(0xFFE4DEF6) to Color(0xFF3D2A7A)
        LicenseFamily.Other -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
}

@Composable
private fun LicenseChip(label: String, family: LicenseFamily) {
    val (container, content) = chipColors(family)
    Surface(shape = styledCorner(10.dp), color = container) {
        Text(
            label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = content,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 180.dp).padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
