package com.programmersbox.scrcpyviewer.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * One JSON document on disk with safe loading and atomic writes. A corrupt file is moved to `.bak` and
 * [load] returns the default; if it cannot be read or backed up, [error] is set and [save] becomes a
 * no-op so the unreadable file is never overwritten. A failed write sets [error] too (never throws into the
 * caller); the next successful write clears it.
 */
internal class JsonFile<T>(
    private val file: Path,
    private val serializer: KSerializer<T>,
    private val default: () -> T,
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val _error = MutableStateFlow<String?>(null)
    /** Set when the file on disk could not be read or backed up: it must never be overwritten. */
    @Volatile private var readOnly = false

    /** The file name a corrupt file was moved to by [load], or null when none was. */
    @Volatile var backedUpTo: String? = null
        private set

    /**
     * Non-null when the file on disk could not be read or backed up (persisting is then disabled), or when the last
     * write failed.
     */
    val error: StateFlow<String?> get() = _error

    fun load(): T {
        if (!Files.exists(file)) return default()
        val text = try {
            Files.readString(file)
        } catch (e: IOException) {
            readOnly = true
            _error.value = "Could not read ${file.fileName}: ${e.message}"
            return default()
        }
        return try {
            json.decodeFromString(serializer, text)
        } catch (e: SerializationException) {
            backUpCorrupt(e)
        } catch (e: IllegalArgumentException) {
            backUpCorrupt(e)
        }
    }

    fun save(value: T) {
        if (readOnly) return
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        try {
            file.toAbsolutePath().parent?.let { Files.createDirectories(it) }
            Files.writeString(tmp, json.encodeToString(serializer, value))
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            }
            _error.value = null
        } catch (e: IOException) {
            runCatching { Files.deleteIfExists(tmp) }
            _error.value = "Could not save ${file.fileName}: ${e.message ?: e}"
        }
    }

    private fun backUpCorrupt(cause: Exception): T {
        try {
            val bak = file.resolveSibling(file.fileName.toString() + ".bak")
            Files.move(file, bak, StandardCopyOption.REPLACE_EXISTING)
            backedUpTo = bak.fileName.toString()
        } catch (e: Exception) {
            readOnly = true
            _error.value = "${file.fileName} is unreadable (${cause.message}) and could not be backed up: ${e.message}"
        }
        return default()
    }
}
