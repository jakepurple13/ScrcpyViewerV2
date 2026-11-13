package com.programmersbox.scrcpyviewer.ui.mirror.links

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.programmersbox.scrcpyviewer.actions.ActionTemplate
import com.programmersbox.scrcpyviewer.actions.OutputTarget
import com.programmersbox.scrcpyviewer.actions.RunOnceAction
import com.programmersbox.scrcpyviewer.actions.TemplateGroup
import com.programmersbox.scrcpyviewer.adb.ShellRunner
import com.programmersbox.scrcpyviewer.adb.ShellTimeoutException
import com.programmersbox.scrcpyviewer.apps.DeviceApp
import com.programmersbox.scrcpyviewer.links.Before
import com.programmersbox.scrcpyviewer.links.Extra
import com.programmersbox.scrcpyviewer.links.ExtraType
import com.programmersbox.scrcpyviewer.links.LaunchResult
import com.programmersbox.scrcpyviewer.links.LinkEntry
import com.programmersbox.scrcpyviewer.links.LinkKind
import com.programmersbox.scrcpyviewer.links.LinkRequest
import com.programmersbox.scrcpyviewer.links.LinkStore
import com.programmersbox.scrcpyviewer.links.deviceCommand
import com.programmersbox.scrcpyviewer.links.linkKind
import com.programmersbox.scrcpyviewer.links.parseAmStart
import com.programmersbox.scrcpyviewer.links.previewCommand
import com.programmersbox.scrcpyviewer.links.summary
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The last run's outcome as the result strip shows it: [headline] ("Opened in …" or "No app on this device opens this
 * link[ with <app>]"), and [raw], the device's own lines (`Status / LaunchState / TotalTime`, or the `am` error).
 */
data class LinkOutcome(val result: LaunchResult, val headline: String, val raw: String?)

/** Starred links (all devices) first, then this device's recent links not already starred, newest first. */
data class LinkHistory(val starred: List<LinkEntry>, val recent: List<LinkEntry>)

/** Something done on the Links tab. */
sealed interface LinksEvent {
    data class SetText(val text: String) : LinksEvent
    /** Null is "Let Android choose". */
    data class SetPackage(val packageName: String?) : LinksEvent
    data class SetBefore(val before: Before) : LinksEvent
    data object ToggleDetails : LinksEvent
    data class SetAction(val action: String) : LinksEvent
    data class SetCategory(val category: String) : LinksEvent
    data class SetExtra(val extra: Extra) : LinksEvent
    data object Open : LinksEvent
    /** A history row's "Open again". */
    data class Reopen(val entry: LinkEntry) : LinksEvent
    /** A history row was clicked: its link goes into the field. */
    data class Fill(val entry: LinkEntry) : LinksEvent
    data class ToggleStar(val entry: LinkEntry) : LinksEvent
    /** Removes this device's non-starred history. */
    data object Clear : LinksEvent
    /** Opens the Actions editor on a new run-once action for the link (handled by the sheet). */
    data object Save : LinksEvent
}

/**
 * The Links tab of one mirror window: the link form, its command preview, running it on [serial] through [runner]
 * (30 s limit, then an error result: the tab never stays busy), and the history in [store] (per device, stars shared).
 * [apps] are the device's apps from the Apps tab, for labels; null until listed. Snapshot state is written on the UI
 * thread ([scope], the window's scope).
 */
@Stable
class LinksState(
    private val serial: String,
    private val runner: ShellRunner?,
    private val store: LinkStore,
    private val apps: () -> List<DeviceApp>?,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeoutMs: Long = ShellRunner.BUFFERED_TIMEOUT_MS,
) {
    var text by mutableStateOf("")
        private set
    var packageName by mutableStateOf<String?>(null)
        private set
    var before by mutableStateOf(Before.None)
        private set
    var detailsOpen by mutableStateOf(false)
        private set
    var action by mutableStateOf(DEFAULT_ACTION)
        private set
    var category by mutableStateOf(DEFAULT_CATEGORY)
        private set
    var extra by mutableStateOf(Extra(ExtraType.String, "", ""))
        private set
    var running by mutableStateOf(false)
        private set
    var outcome by mutableStateOf<LinkOutcome?>(null)
        private set

    /** Goes up each time a history row fills the field, so the field can flash. */
    var fills by mutableIntStateOf(0)
        private set

    /** Goes up each time a run ends with its outcome, so a failure can shake the result even when it came at once. */
    var runs by mutableIntStateOf(0)
        private set

    private var data by mutableStateOf(store.state.value)

    val result: LaunchResult? get() = outcome?.result

    /** The detected kind and its chip label. */
    val kind: Pair<LinkKind, String> get() = linkKind(text)

    /**
     * What [open] runs, as fields stand. Intent details' fields (action, category, the extra) are used only while it is
     * open: closed, the action is the default [DEFAULT_ACTION] and there is no category or extra.
     */
    val request: LinkRequest
        get() = LinkRequest(
            link = text.trim(),
            packageName = packageName,
            // Force-stop needs an app; a stale choice after "Let Android choose" means nothing.
            before = if (before == Before.ForceStop && packageName == null) Before.None else before,
            action = if (detailsOpen) action else DEFAULT_ACTION,
            category = if (detailsOpen) category else null,
            extra = if (detailsOpen) extra.takeIf { it.key.isNotBlank() } else null,
        )

    val preview: String get() = previewCommand(serial, request)

    val canOpen: Boolean get() = runner != null && !running && kind.first != LinkKind.NotALink

    val history: LinkHistory
        get() {
            val starred = data.starred
            val recent = data.history[serial].orEmpty().filterNot { e -> starred.any { it.sameAs(e) } }
            return LinkHistory(starred, recent)
        }

    fun isStarred(entry: LinkEntry): Boolean = data.starred.any { it.sameAs(entry) }

    /** Follows [store]: another window's runs and stars show here. Run in the window's scope. */
    suspend fun watch() {
        store.state.collect { data = it }
    }

    fun onEvent(event: LinksEvent) {
        when (event) {
            is LinksEvent.SetText -> changeText(event.text)
            is LinksEvent.SetPackage -> packageName = event.packageName?.takeIf { it.isNotBlank() }
            is LinksEvent.SetBefore -> before = event.before
            LinksEvent.ToggleDetails -> detailsOpen = !detailsOpen
            is LinksEvent.SetAction -> action = event.action
            is LinksEvent.SetCategory -> category = event.category
            is LinksEvent.SetExtra -> extra = event.extra
            LinksEvent.Open -> open()
            is LinksEvent.Reopen -> reopen(event.entry)
            is LinksEvent.Fill -> fill(event.entry)
            is LinksEvent.ToggleStar -> toggleStar(event.entry)
            LinksEvent.Clear -> clear()
            LinksEvent.Save -> Unit // the sheet's: it opens the Actions editor with [saveTemplate]
        }
    }

    /** Runs [request] on the device and records the result; ignored while running or when the text isn't a link. */
    fun open() {
        val r = runner ?: return
        if (!canOpen) return
        val req = request
        val isIntent = kind.first == LinkKind.Intent
        // An intent URI names its own app: "Open with" is ignored for it, so it is neither named nor recorded.
        val chosenPackage = if (isIntent) null else req.packageName
        val chosenLabel = chosenPackage?.let(::label)
        running = true
        scope.launch {
            val found = try {
                withTimeoutOrNull(timeoutMs) { r.run(deviceCommand(req), timeoutMs) }?.let { outcomeOf(it.output, chosenLabel, isIntent) }
                    ?: timedOut()
            } catch (e: CancellationException) {
                running = false
                throw e
            } catch (e: ShellTimeoutException) {
                timedOut()
            } catch (e: Exception) {
                LinkOutcome(LaunchResult(ok = false, errorLine = e.message), COULD_NOT_OPEN, e.message ?: e.toString())
            }
            outcome = found
            runs++
            running = false
            val opened = found.result.packageName
            store.record(
                serial,
                LinkEntry(
                    link = req.link,
                    packageName = chosenPackage,
                    appLabel = if (found.result.ok) opened?.let(::label) ?: chosenLabel else null,
                    ok = found.result.ok,
                    detail = if (found.result.ok) found.headline else found.raw ?: found.headline,
                    at = clock(),
                ),
            )
            data = store.state.value
        }
    }

    /** "Open again": the row's link and app, with the other fields as they are. */
    fun reopen(entry: LinkEntry) {
        if (running) return
        fill(entry)
        open()
    }

    fun fill(entry: LinkEntry) {
        changeText(entry.link)
        packageName = entry.packageName
        fills++
    }

    /** A different link makes the last result stale: it goes (the result strip folds away). */
    private fun changeText(value: String) {
        if (value != text) outcome = null
        text = value
    }

    fun toggleStar(entry: LinkEntry) {
        store.star(entry, !isStarred(entry))
        data = store.state.value
    }

    fun clear() {
        store.clear(serial)
        data = store.state.value
    }

    /**
     * A run-once action that opens the link as the fields stand: "Open <host or scheme>", the device command (braces
     * escaped so they stay literal), output Toast. Null when the text isn't a link.
     */
    fun saveTemplate(): ActionTemplate? {
        if (kind.first == LinkKind.NotALink) return null
        val name = "Open ${shortName(text.trim())}"
        val command = deviceCommand(request).replace("{", "{{").replace("}", "}}")
        return ActionTemplate(
            TemplateGroup.Intents, name, "",
            RunOnceAction(id = "link", name = name, icon = SAVED_ICON, command = command, output = OutputTarget.Toast, confirm = false),
        )
    }

    private fun label(pkg: String): String = apps()?.firstOrNull { it.packageName == pkg }?.label ?: pkg

    private fun outcomeOf(output: String, chosenLabel: String?, isIntent: Boolean): LinkOutcome {
        val parsed = parseAmStart(output)
        val lines = output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (parsed.ok) {
            val label = parsed.packageName?.let(::label) ?: chosenLabel ?: "an app"
            val raw = lines.filter { l -> RAW_KEYS.any { l.startsWith("$it:") } }.joinToString(" · ").ifEmpty { null }
            return LinkOutcome(parsed, parsed.summary(label), raw)
        }
        // `am` may fail without an `Error:` line (a force-stop that failed, a security exception): show its last line.
        val raw = parsed.errorLine ?: lines.lastOrNull()
        val with = if (chosenLabel != null && !isIntent) " with $chosenLabel" else ""
        return LinkOutcome(parsed.copy(errorLine = raw), "$NO_APP$with", raw)
    }

    private fun timedOut() = LinkOutcome(LaunchResult(ok = false, errorLine = TIMED_OUT), TIMED_OUT, null)

    companion object {
        const val DEFAULT_ACTION = "android.intent.action.VIEW"
        const val DEFAULT_CATEGORY = "android.intent.category.BROWSABLE"
        const val NO_APP = "No app on this device opens this link"
        const val TIMED_OUT = "Timed out after 30 s"
        const val COULD_NOT_OPEN = "Could not open the link"

        /** No "link" icon in the fixed set of 48; the closest. */
        const val SAVED_ICON = "open_in_new"

        private val RAW_KEYS = listOf("Status", "LaunchState", "TotalTime", "WaitTime")

        /** A web link's host, else the scheme (`myapp`, `geo`, `intent`). */
        fun shortName(link: String): String {
            val scheme = link.substringBefore(':')
            if (scheme.equals("http", true) || scheme.equals("https", true)) {
                // URI rejects characters browsers accept (`{`, spaces): then the authority is cut out by hand.
                val host = runCatching { URI(link).host }.getOrNull()
                    ?: link.substringAfter("://", "").takeWhile { it !in "/?#" }.substringAfterLast('@').substringBefore(':')
                host.takeIf { it.isNotBlank() }?.let { return it }
            }
            return scheme.lowercase()
        }

        private fun LinkEntry.sameAs(other: LinkEntry) = link == other.link && packageName == other.packageName
    }
}
