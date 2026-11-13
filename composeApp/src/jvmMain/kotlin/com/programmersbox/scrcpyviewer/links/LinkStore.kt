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
    /** True when the run itself failed (timeout, adb or transport error): [ok] false says nothing about the link. */
    val error: Boolean = false,
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

    private val _notice = MutableStateFlow(
        this.file.backedUpTo?.let { "${file.fileName} was unreadable; saved a copy as $it and started fresh" },
    )

    /** Set for this app run when a corrupt file was backed up and the store started empty, until [dismissNotice]. */
    val notice: StateFlow<String?> get() = _notice

    fun dismissNotice() {
        _notice.value = null
    }

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
