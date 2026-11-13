package com.programmersbox.scrcpyviewer.links

import com.programmersbox.scrcpyviewer.state.JsonFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.nio.file.Path

/** One opened link. [at] is epoch millis. Two entries are the same link when [link] and [packageName] match. */
@Serializable
data class LinkEntry(
    val link: String,
    val packageName: String? = null,
    val appLabel: String? = null,
    val ok: Boolean,
    val detail: String = "",
    val at: Long,
)

/** [history] is per device serial, newest first; [starred] is global. */
@Serializable
data class LinksData(
    val history: Map<String, List<LinkEntry>> = emptyMap(),
    val starred: List<LinkEntry> = emptyList(),
)

private fun LinkEntry.sameAs(other: LinkEntry) = link == other.link && packageName == other.packageName

/** Per-device link history (capped) and global stars, persisted synchronously to [file]. */
class LinkStore(file: Path) {
    private val file = JsonFile(file, LinksData.serializer()) { LinksData() }
    private val lock = Any()
    private val _state = MutableStateFlow(this.file.load())

    val state: StateFlow<LinksData> get() = _state

    /** Non-null when the file on disk could not be read or backed up, or the last write failed. */
    val error: StateFlow<String?> get() = this.file.error

    /** Puts [entry] at the top of [serial]'s history, replacing an earlier entry for the same link and package. */
    fun record(serial: String, entry: LinkEntry) = mutate {
        val old = it.history[serial].orEmpty()
        val next = (listOf(entry) + old.filterNot { e -> e.sameAs(entry) }).take(HISTORY_CAP)
        it.copy(history = it.history + (serial to next))
    }

    fun star(entry: LinkEntry, on: Boolean) = mutate {
        val rest = it.starred.filterNot { e -> e.sameAs(entry) }
        it.copy(starred = if (on) listOf(entry) + rest else rest)
    }

    /** Empties [serial]'s history; stars stay. */
    fun clear(serial: String) = mutate { it.copy(history = it.history - serial) }

    private fun mutate(block: (LinksData) -> LinksData) = synchronized(lock) {
        _state.value = block(_state.value)
        file.save(_state.value)
    }

    companion object {
        const val HISTORY_CAP = 50
    }
}
