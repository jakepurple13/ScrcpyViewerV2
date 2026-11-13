package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.programmersbox.scrcpyviewer.apps.AppFilter
import com.programmersbox.scrcpyviewer.apps.AppIconLoader
import com.programmersbox.scrcpyviewer.apps.DeviceApp
import com.programmersbox.scrcpyviewer.apps.DeviceApps
import com.programmersbox.scrcpyviewer.apps.matching
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.skia.Image

/** What the Apps tab shows. */
data class AppsUiState(
    /** Null until the first listing finished (or failed: then empty). */
    val apps: List<DeviceApp>? = null,
    val loading: Boolean = false,
    val query: String = "",
    val filter: AppFilter = AppFilter.All,
    /** Loaded icons by package name; a package missing here has none (yet). */
    val icons: Map<String, ImageBitmap> = emptyMap(),
    /** Packages with a launch, force stop or uninstall running. */
    val busy: Set<String> = emptySet(),
    /** The app picked in the Logcat tab (its card shows a "Logcat" badge); null when none is. */
    val logcatPackage: String? = null,
) {
    /** The apps the grid shows: [query] and [filter] applied. */
    val visible: List<DeviceApp> get() = apps.orEmpty().matching(query, filter)

    /** How many apps [filter] would show with the current query. */
    fun count(filter: AppFilter): Int = apps.orEmpty().matching(query, filter).size
}

/** Something done on the Apps tab. */
sealed interface AppsEvent {
    data object Reload : AppsEvent
    data class Search(val query: String) : AppsEvent
    data class Filter(val filter: AppFilter) : AppsEvent

    /** The grid shows [app]: load its icon if it is not loaded. */
    data class NeedIcon(val app: DeviceApp) : AppsEvent
    data class Launch(val app: DeviceApp) : AppsEvent
    data class ForceStop(val app: DeviceApp) : AppsEvent
    data class Uninstall(val app: DeviceApp) : AppsEvent

    /** The package name was put on the clipboard. */
    data class Copied(val app: DeviceApp) : AppsEvent

    /** Pick [app] in the Logcat tab and switch to it; [QuickActions] handles it. */
    data class ShowInLogcat(val app: DeviceApp) : AppsEvent
}

/**
 * The Apps tab of one mirror window: the device's apps (listed the first time the tab shows, again on Reload), their
 * icons (loaded as cards come into view), search and filter, and the per-app commands. Snapshot state is written on
 * the UI thread (the window's coroutine scope).
 */
@Stable
class AppExplorer(
    private val device: DeviceApps?,
    private val iconLoader: AppIconLoader?,
    private val scope: CoroutineScope,
    private val notify: (String) -> Unit,
) {
    private var apps by mutableStateOf<List<DeviceApp>?>(null)
    private var loading by mutableStateOf(false)
    private var query by mutableStateOf("")
    private var filter by mutableStateOf(AppFilter.All)
    private val icons = mutableStateMapOf<String, ImageBitmap>()
    private var busy by mutableStateOf<Set<String>>(emptySet())
    private var loadJob: Job? = null
    private val iconJobs = HashMap<String, Job>()

    val uiState: AppsUiState get() = AppsUiState(apps, loading, query, filter, icons.toMap(), busy)

    /** Lists the apps unless they are listed or being listed. */
    fun ensureLoaded() {
        if (apps == null && !loading) load()
    }

    fun onEvent(event: AppsEvent) {
        when (event) {
            AppsEvent.Reload -> load()
            is AppsEvent.Search -> query = event.query
            is AppsEvent.Filter -> filter = event.filter
            is AppsEvent.NeedIcon -> loadIcon(event.app)
            is AppsEvent.Launch -> command(event.app, "Launched", "Could not launch") { launch(it.packageName) }
            is AppsEvent.ForceStop -> command(event.app, "Force stopped", "Could not force stop") { forceStop(it.packageName) }
            is AppsEvent.Uninstall -> command(event.app, "Uninstalled", "Could not uninstall") { app ->
                uninstall(app.packageName, app.system)
                apps = apps?.filterNot { it.packageName == app.packageName }
            }
            is AppsEvent.Copied -> notify("Copied ${event.app.packageName}")
            is AppsEvent.ShowInLogcat -> Unit // the sheet's, not this tab's
        }
    }

    /** A newer listing replaces one still running. */
    private fun load() {
        val d = device ?: return
        loadJob?.cancel()
        loading = true
        loadJob = scope.launch {
            // Cancelled only by a newer listing, which owns `loading` from then on.
            try {
                val listed = d.list()
                // An updated app may have a new icon: keep only icons of the same package at the same version.
                val before = apps.orEmpty().associateBy { it.packageName }
                val same = listed.filter { before[it.packageName]?.versionCode == it.versionCode }.mapTo(HashSet()) { it.packageName }
                icons.keys.retainAll(same)
                apps = listed
                loading = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (apps == null) apps = emptyList()
                loading = false
                notify("Could not list apps: ${e.message ?: e}")
            }
        }
    }

    private fun loadIcon(app: DeviceApp) {
        val loader = iconLoader ?: return
        if (app.packageName in icons || iconJobs[app.packageName]?.isActive == true) return
        iconJobs[app.packageName] = scope.launch {
            val bytes = runCatching { loader.load(app) }.getOrNull() ?: return@launch
            val bitmap = runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull() ?: return@launch
            icons[app.packageName] = bitmap
        }
    }

    private fun command(app: DeviceApp, done: String, failure: String, block: suspend DeviceApps.(DeviceApp) -> Unit) {
        val d = device ?: return
        if (app.packageName in busy) return
        busy = busy + app.packageName
        scope.launch {
            try {
                d.block(app)
                notify("$done ${app.label}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notify("$failure ${app.label}: ${e.message ?: e}")
            } finally {
                busy = busy - app.packageName
            }
        }
    }
}
